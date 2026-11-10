package com.nexbox.core.core.system.am;

import com.nexbox.core.utils.Slog;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.IEmpty;
import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.entity.UnbindRecord;
import com.nexbox.core.entity.am.RunningServiceInfo;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.proxy.record.ProxyServiceRecord;


public class ActiveServices {
    public static final String TAG = "ActiveServices";

    private final Map<ComponentName, RunningServiceRecord> mRunningServiceRecords = new HashMap<>();
    private final Map<IBinder, RunningServiceRecord> mRunningTokens = new HashMap<>();
    private final Map<IBinder, Map<Intent.FilterComparison, ConnectedServiceRecord>> mConnectedServices = new HashMap<>();
    private final Map<IBinder, IBinder.DeathRecipient> mConnectionDeaths = new HashMap<>();

    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        return startService(intent, resolvedType, requireForeground, userId, null, 0L);
    }

    ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId,
                               ProcessRecord preparedProcess, long expectedGeneration) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return null;

        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        intent = explicitServiceIntent(intent, serviceInfo);
        Slog.d(TAG, "action=startService stage=resolved package=" + serviceInfo.packageName
                + " service=" + serviceInfo.name + " userId=" + userId
                + " requireForeground=" + requireForeground);
        ProcessRecord processRecord;
        if (preparedProcess != null || expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(preparedProcess, serviceInfo, userId, expectedGeneration);
            processRecord = preparedProcess;
        } else {
            processRecord = BProcessManagerService.get().startProcessLocked(serviceInfo.packageName,
                    serviceInfo.processName, userId, -1, Binder.getCallingPid());
        }
        if (processRecord == null) {
            if (GmsCompat.isCorePackage(serviceInfo.packageName)) return null;
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }
        boolean existed = findRunningServiceRecord(intent) != null;
        RunningServiceRecord runningServiceRecord = getOrCreateRunningServiceRecord(intent);
        runningServiceRecord.mServiceInfo = serviceInfo;
        int previousStartId = runningServiceRecord.mStartId.get();
        runningServiceRecord.mStartId.set(runningServiceRecord.mNextStartId.incrementAndGet());
        final Intent stubServiceIntent = createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
        boolean accepted = false;
        long identity = Binder.clearCallingIdentity();
        try {
            ComponentName receipt = NexBoxCore.getContext().startService(stubServiceIntent);
            accepted = receipt != null;
            Slog.d(TAG, "action=startService stage=systemReceipt package=" + serviceInfo.packageName
                    + " service=" + serviceInfo.name + " userId=" + userId + " accepted=" + accepted);
            return accepted ? new ComponentName(serviceInfo.packageName, serviceInfo.name) : null;
        } finally {
            Binder.restoreCallingIdentity(identity);
            if (!accepted) {
                runningServiceRecord.mStartId.set(previousStartId);
                if (!existed) {
                    mRunningServiceRecords.remove(intent.getComponent());
                    mRunningTokens.remove(runningServiceRecord);
                }
            }
        }
    }

    public int stopService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.serviceInfo == null) return 0;
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        intent = explicitServiceIntent(intent, serviceInfo);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
            if (runningServiceRecord == null) {
                return 0;
            }
            runningServiceRecord.mStartId.set(0);
            ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(
                    serviceInfo.packageName, serviceInfo.processName, userId);
            if (processRecord == null) {
                mRunningServiceRecords.remove(intent.getComponent());
                mRunningTokens.remove(runningServiceRecord);
                return 0;
            }
            if (runningServiceRecord.mBindCount.get() > 0) return 1;
            try {
                Intent stopped = createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
                processRecord.bActivityThread.stopService(stopped);
                mRunningServiceRecords.remove(intent.getComponent(), runningServiceRecord);
                mRunningTokens.remove(runningServiceRecord);
                return 1;
            } catch (RemoteException failure) {
                throw new IllegalStateException("Could not stop the virtual service", failure);
            }
        }
    }

    public Intent bindService(Intent intent, final IBinder binder, String resolvedType, int userId) {
        return bindService(intent, binder, resolvedType, userId, null, 0L);
    }

    Intent bindService(Intent intent, final IBinder binder, String resolvedType, int userId,
                        ProcessRecord preparedProcess, long expectedGeneration) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return intent;
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        intent = explicitServiceIntent(intent, serviceInfo);
        ProcessRecord processRecord;
        if (preparedProcess != null || expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(preparedProcess, serviceInfo, userId, expectedGeneration);
            processRecord = preparedProcess;
        } else {
            processRecord = BProcessManagerService.get().startProcessLocked(serviceInfo.packageName,
                    serviceInfo.processName, userId, -1, Binder.getCallingPid());
        }

        if (processRecord == null) {
            if (GmsCompat.isCorePackage(serviceInfo.packageName)) return null;
            throw new RuntimeException("Unable to create " + serviceInfo.name);
        }

        RunningServiceRecord runningServiceRecord;
        synchronized (mRunningServiceRecords) {
            runningServiceRecord = getOrCreateRunningServiceRecord(intent);
            runningServiceRecord.mServiceInfo = serviceInfo;

            if (binder != null) {
                Map<Intent.FilterComparison, ConnectedServiceRecord> connections = mConnectedServices.get(binder);
                if (connections == null) {
                    connections = new HashMap<>();
                    IBinder.DeathRecipient death = () -> removeConnection(binder);
                    try {
                        binder.linkToDeath(death, 0);
                    } catch (RemoteException e) {
                        throw new IllegalStateException("Service connection is already dead", e);
                    }
                    mConnectedServices.put(binder, connections);
                    mConnectionDeaths.put(binder, death);
                }
                Intent.FilterComparison key = new Intent.FilterComparison(intent);
                ConnectedServiceRecord existing = connections.get(key);
                if (existing == null || existing.mRecord != runningServiceRecord) {
                    ConnectedServiceRecord connection = new ConnectedServiceRecord();
                    connection.mRecord = runningServiceRecord;
                    connections.put(key, connection);
                    runningServiceRecord.incrementBindCountAndGet();
                }
            }
        }
        return createStubServiceIntent(intent, serviceInfo, processRecord, runningServiceRecord);
    }

    public void unbindService(IBinder binder, int userId) {
        removeConnection(binder);
    }

    private void removeConnection(IBinder binder) {
        synchronized (mRunningServiceRecords) {
            Map<Intent.FilterComparison, ConnectedServiceRecord> connections = mConnectedServices.remove(binder);
            IBinder.DeathRecipient death = mConnectionDeaths.remove(binder);
            if (death != null) binder.unlinkToDeath(death, 0);
            if (connections == null) return;
            for (ConnectedServiceRecord connection : connections.values()) {
                connection.mRecord.mBindCount.updateAndGet(count -> Math.max(0, count - 1));
            }
        }
    }

    public void stopServiceToken(ComponentName className, IBinder token, int userId) {
        stopServiceTokenIfCurrent(className, token, -1, userId);
    }

    public boolean stopServiceTokenIfCurrent(ComponentName className, IBinder token, int startId, int userId) {
        RunningServiceRecord runningServiceByToken = findRunningServiceByToken(token);
        if (runningServiceByToken == null || className == null
                || !className.equals(runningServiceByToken.mIntent.getComponent())
                || runningServiceByToken.mStartId.get() == 0
                || (startId >= 0 && startId != runningServiceByToken.mStartId.get())) return false;
        return stopService(runningServiceByToken.mIntent, null, userId) == 1;
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
        if (proxyIntent == null)
            return;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        if (proxyServiceRecord.mServiceIntent == null || proxyServiceRecord.mToken == null) return;
        proxyIntent = proxyServiceRecord.mServiceIntent;
        RunningServiceRecord current = findRunningServiceRecord(proxyIntent);
        if (current == null || current != proxyServiceRecord.mToken) return;
        RunningServiceRecord remove = mRunningServiceRecords.remove(proxyIntent.getComponent());
        if (remove != null) {
            mRunningTokens.remove(remove);
        }
    }

    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        if (proxyIntent == null)
            return null;
        ProxyServiceRecord proxyServiceRecord = ProxyServiceRecord.create(proxyIntent);
        if (proxyServiceRecord.mServiceIntent == null || proxyServiceRecord.mToken == null) return null;
        ComponentName component = proxyServiceRecord.mServiceIntent.getComponent();

        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(proxyServiceRecord.mServiceIntent);
        if (runningServiceRecord == null || runningServiceRecord != proxyServiceRecord.mToken)
            return null;
        UnbindRecord record = new UnbindRecord();
        record.setComponentName(component);
        record.setBindCount(runningServiceRecord.mBindCount.get());
        record.setStartId(runningServiceRecord.mStartId.get());
        return record;
    }

    private Intent createStubServiceIntent(Intent targetIntent, ServiceInfo serviceInfo, ProcessRecord processRecord, RunningServiceRecord runningServiceRecord) {
        Intent stub = new Intent();
        ComponentName stubComp = new ComponentName(NexBoxCore.getHostPkg(), ProxyManifest.getProxyService(processRecord.bpid));
        stub.setComponent(stubComp);
        stub.setAction(UUID.randomUUID().toString());
        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord, processRecord.userId,
                runningServiceRecord.mStartId.get(), processRecord);
        return stub;
    }

    private RunningServiceRecord getOrCreateRunningServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
        if (runningServiceRecord == null) {
            runningServiceRecord = new RunningServiceRecord();
            runningServiceRecord.mIntent = intent;
            mRunningServiceRecords.put(intent.getComponent(), runningServiceRecord);
            mRunningTokens.put(runningServiceRecord, runningServiceRecord);
        }
        return runningServiceRecord;
    }

    private RunningServiceRecord findRunningServiceRecord(Intent intent) {
        return intent == null ? null : mRunningServiceRecords.get(intent.getComponent());
    }

    private static Intent explicitServiceIntent(Intent intent, ServiceInfo info) {
        return new Intent(intent).setComponent(new ComponentName(info.packageName, info.name));
    }

    private RunningServiceRecord findRunningServiceByToken(IBinder token) {
        return mRunningTokens.get(token);
    }

    public RunningServiceInfo getRunningServiceInfo(String callerPackage, int userId, long userGeneration) {
        Map<ComponentName, RunningServiceRecord> snapshot;
        List<ServiceInfo> services = new ArrayList<>();
        synchronized (this) {
            snapshot = new HashMap<>(mRunningServiceRecords);
            for (RunningServiceRecord record : snapshot.values()) services.add(new ServiceInfo(record.mServiceInfo));
        }
        RunningServiceInfo info = new RunningServiceInfo();
        info.mRunningServiceInfoList = BProcessManagerService.get()
                .getRunningServicesAsUser(callerPackage, userId, userGeneration, services);
        synchronized (this) {
            info.mRunningServiceInfoList.removeIf(service -> mRunningServiceRecords.get(service.service) != snapshot.get(service.service));
        }
        return info;
    }

    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        ResolveInfo resolveInfo = resolveService(intent, resolvedType, userId);
        if (resolveInfo == null)
            return null;
        ProcessRecord processRecord =
                BProcessManagerService.get().findProcessRecord(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.processName, userId);
        if (processRecord == null)
            return null;
        try {
            return processRecord.bActivityThread.peekService(intent);
        } catch (RemoteException e) {
            Slog.e("ActiveServices", "Container operation failed", e);
        }
        return null;
    }

    private ResolveInfo resolveService(Intent intent, String resolvedType, int userId) {
        return BPackageManagerService.get().resolveService(intent, 0, resolvedType, userId);
    }

    public static class RunningServiceRecord extends IEmpty.Stub {
        
        private final AtomicInteger mStartId = new AtomicInteger(0);
        private final AtomicInteger mNextStartId = new AtomicInteger(0);
        private final AtomicInteger mBindCount = new AtomicInteger(0);
        

        private ServiceInfo mServiceInfo;
        private Intent mIntent;

        public int decrementBindCountAndGet() {
            return mBindCount.decrementAndGet();
        }

        public int incrementBindCountAndGet() {
            return mBindCount.incrementAndGet();
        }
    }

    public static class ConnectedServiceRecord {
        private RunningServiceRecord mRecord;
    }
}
