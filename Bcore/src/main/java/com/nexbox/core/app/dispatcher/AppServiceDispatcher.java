package com.nexbox.core.app.dispatcher;

import com.nexbox.core.utils.Slog;

import android.accounts.AccountManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.accounts.AccountAuthenticatorTransport;
import com.nexbox.core.entity.ServiceRecord;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.UnbindRecord;
import com.nexbox.core.proxy.record.ProxyServiceRecord;

import static android.app.Service.START_NOT_STICKY;



public class AppServiceDispatcher {
    public static final String TAG = "AppServiceDispatcher";

    private static final AppServiceDispatcher sServiceDispatcher = new AppServiceDispatcher();

    private final Map<ComponentName, ServiceRecord> mService = new HashMap<>();

    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    private final Handler mHandler = NexBoxCore.get().getHandler();

    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);
        Intent intent = serviceRecord.mServiceIntent;
        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (intent == null || serviceInfo == null)
            return null;



        Service service = getOrCreateService(serviceRecord);
        if (service == null)
            return null;
        intent.setExtrasClassLoader(service.getClassLoader());

        ServiceRecord record = findRecord(intent);
        record.setProxyIntent(proxyIntent);
        record.incrementAndGetBindCount(intent);
        if (record.hasBinder(intent)) {
            if (record.isRebind(intent)) {
                service.onRebind(intent);
                record.setRebind(intent, false);
            }
            return record.getBinder(intent);
        }

        try {
            IBinder iBinder = service.onBind(intent);
            if (AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())) {
                iBinder = AccountAuthenticatorTransport.export(iBinder);
            }
            record.addBinder(intent, iBinder);
            return iBinder;
        } catch (Throwable e) {
            Slog.e("AppServiceDispatcher", "Container operation failed", e);
        }
        return null;
    }

    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (stubRecord.mServiceIntent == null || stubRecord.mServiceInfo == null) {
            return START_NOT_STICKY;
        }
        Slog.d(TAG, "action=startService stage=dispatch package=" + stubRecord.mServiceInfo.packageName
                + " service=" + stubRecord.mServiceInfo.name + " userId=" + stubRecord.mUserId
                + " startId=" + stubRecord.mStartId);


        Service service = getOrCreateService(stubRecord);
        if (service == null)
            return START_NOT_STICKY;
        stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());
        ServiceRecord record = findRecord(stubRecord.mServiceIntent);
        record.setProxyIntent(proxyIntent);
        record.setStartId(stubRecord.mStartId);
        try {
            int i = service.onStartCommand(stubRecord.mServiceIntent, flags, stubRecord.mStartId);
            NexBoxCore.getBActivityManager().onStartCommand(proxyIntent, stubRecord.mUserId);
            return i;
        } catch (Throwable e) {
            Slog.e("AppServiceDispatcher", "Container operation failed", e);
        }
        return START_NOT_STICKY;
    }

    public void onDestroy() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onDestroy();
                } catch (Throwable e) {
                    Slog.e("AppServiceDispatcher", "Container operation failed", e);
                } finally {
                    NexBoxCore.getBActivityManager().onServiceDestroy(record.getProxyIntent(), NexBoxCore.getUserId());
                }
            }
        }
        mService.clear();

    }

    public void onConfigurationChanged(Configuration newConfig) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onConfigurationChanged(newConfig);
                } catch (Throwable e) {
                    Slog.e("AppServiceDispatcher", "Container operation failed", e);
                }
            }
        }

    }

    public void onLowMemory() {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onLowMemory();
                } catch (Throwable e) {
                    Slog.e("AppServiceDispatcher", "Container operation failed", e);
                }
            }
        }

    }

    public void onTrimMemory(int level) {
        if (mService.size() > 0) {
            for (ServiceRecord record : mService.values()) {
                try {
                    record.getService().onTrimMemory(level);
                } catch (Throwable e) {
                    Slog.e("AppServiceDispatcher", "Container operation failed", e);
                }
            }
        }
        
    }

    public boolean onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);
        if (!belongsToCurrentProcess(stubRecord)) {
            return false;
        }
        Intent intent = stubRecord.mServiceIntent;

        try {
            UnbindRecord unbindRecord = NexBoxCore.getBActivityManager().onServiceUnbind(proxyIntent, NexBoxCore.getUserId());
            if (unbindRecord == null)
                return false;

            ServiceRecord record = findRecord(intent);
            if (record == null || record.getService() == null)
                return false;
            Service service = record.getService();

            stubRecord.mServiceIntent.setExtrasClassLoader(service.getClassLoader());

            boolean destroy = unbindRecord.getStartId() == 0 && unbindRecord.getBindCount() == 0;
            boolean lastConnection = record.decreaseConnectionCount(intent);
            if (lastConnection) {
                boolean b = service.onUnbind(intent);
                if (destroy) {
                    service.onDestroy();
                    NexBoxCore.getBActivityManager().onServiceDestroy(proxyIntent, NexBoxCore.getUserId());
                    mService.remove(intent.getComponent());
                }
                record.setRebind(intent, b);

            }
        } catch (Throwable e) {
            Slog.e("AppServiceDispatcher", "Container operation failed", e);
        }
        return false;
    }

    public IBinder peekService(Intent intent) {
        ServiceRecord record = findRecord(intent);
        if (record == null) {
            return null;
        }
        return record.getBinder(intent);
    }

    public void stopService(Intent proxyIntent) {
        ProxyServiceRecord stopped = ProxyServiceRecord.create(proxyIntent);
        Intent intent = stopped.mServiceIntent;
        if (!belongsToCurrentProcess(stopped)) return;
        mHandler.post(() -> {
            ServiceRecord record = findRecord(intent);
            if (record == null || record.getService() == null) return;
            if (!stopped.mToken.equals(ProxyServiceRecord.create(record.getProxyIntent()).mToken)) return;
            try {
                record.getService().onDestroy();
            } catch (Throwable e) {
                Slog.e("AppServiceDispatcher", "Container operation failed", e);
            } finally {
                mService.remove(intent.getComponent(), record);
                NexBoxCore.getBActivityManager().onServiceDestroy(record.getProxyIntent(), NexBoxCore.getUserId());
            }
        });
    }

    private ServiceRecord findRecord(Intent intent) {
        return intent == null ? null : mService.get(intent.getComponent());
    }

    private Service getOrCreateService(ProxyServiceRecord proxyServiceRecord) {
        if (!belongsToCurrentProcess(proxyServiceRecord)) return null;
        Intent intent = proxyServiceRecord.mServiceIntent;
        ServiceInfo serviceInfo = proxyServiceRecord.mServiceInfo;
        IBinder token = proxyServiceRecord.mToken;

        ServiceRecord record = findRecord(intent);
        if (record != null && record.getService() != null) {
            if (token != null && token.equals(ProxyServiceRecord.create(record.getProxyIntent()).mToken)) {
                return record.getService();
            }
            // A stop followed by a new start owns a new server token. Late stop messages
            // must not destroy the replacement instance, even when its component is equal.
            try {
                record.getService().onDestroy();
            } finally {
                mService.remove(intent.getComponent(), record);
            }
        }
        Service service = NexBoxCore.currentActivityThread().createService(serviceInfo, token);
        if (service == null)
            return null;
        record = new ServiceRecord();
        record.setService(service);
        mService.put(intent.getComponent(), record);
        return service;
    }

    private boolean belongsToCurrentProcess(ProxyServiceRecord record) {
        AppConfig current = BActivityThread.getAppConfig();
        if (current == null || record.mServiceIntent == null || record.mServiceInfo == null
                || record.mToken == null || record.mProcessToken == null
                || !record.mProcessToken.equals(current.token) || record.mUserId != current.userId
                || !current.packageName.equals(record.mServiceInfo.packageName)) return false;
        String process = record.mServiceInfo.processName;
        if (process == null || process.isEmpty()) process = record.mServiceInfo.packageName;
        if (process.startsWith(":")) process = record.mServiceInfo.packageName + process;
        return process.equals(current.processName);
    }
}
