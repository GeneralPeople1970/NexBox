package com.nexbox.core.core.system.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.UnbindRecord;
import com.nexbox.core.entity.am.PendingResultData;
import com.nexbox.core.entity.am.ReceiverData;
import com.nexbox.core.entity.am.RunningAppProcessInfo;
import com.nexbox.core.entity.am.RunningServiceInfo;
import com.nexbox.core.utils.Slog;

import static android.content.pm.PackageManager.GET_META_DATA;


public class BActivityManagerService extends IBActivityManagerService.Stub implements ISystemService {
    public static final String TAG = "BActivityManagerService";
    private static final long PROCESS_QUERY_FAILURE_LOG_INTERVAL_MS = 10_000L;
    private static final BActivityManagerService sService = new BActivityManagerService();
    private final Map<Integer, UserSpace> mUserSpace = new HashMap<>();
    private final BPackageManagerService mPms = BPackageManagerService.get();
    private final BroadcastManager mBroadcastManager;
    private final AtomicLong mLastProcessQueryFailureLog = new AtomicLong(-PROCESS_QUERY_FAILURE_LOG_INTERVAL_MS);

    public void removeUser(int userId) {
        synchronized (mUserSpace) { mUserSpace.remove(userId); }
    }

    /** Called in-process after the guarded name save, without holding the lifecycle lock. */
    public void refreshUserTaskLabels(BUserInfo user, Bundle result) {
        ActivityStack stack;
        synchronized (mUserSpace) {
            UserSpace space = mUserSpace.get(user.id);
            stack = space == null ? null : space.mStack;
        }
        RecentTaskLabels.refresh(user, stack, result);
    }

    public static BActivityManagerService get() {
        return sService;
    }

    public BActivityManagerService() {
        mBroadcastManager = BroadcastManager.startSystem(this, mPms);
    }

    @Override
    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        PreparedServiceLaunch launch = prepareServiceLaunch(intent, resolvedType, userId);
        if (launch == null || launch.process == null) return null;
        synchronized (launch.space.mActiveServices) {
            synchronized (mPms.getLifecycleLock()) {
                requireServiceLaunch(launch, resolvedType, userId);
                return launch.space.mActiveServices.startService(launch.intent, resolvedType, requireForeground,
                        userId, launch.process, launch.generation);
            }
        }
    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (providerInfo == null) return null;
        int callingPid = Binder.getCallingPid();
        ProcessRecord caller;
        synchronized (mPms.getLifecycleLock()) {
            caller = com.nexbox.core.core.system.xposed.XposedCallerGuard.requireGuest();
            if (captureCallerGeneration(caller.userId) != caller.userGeneration
                    || providerInfo.packageName == null || providerInfo.name == null) {
                throw new SecurityException("Provider caller identity is unavailable");
            }
            // Resolve installed metadata in the caller's actual profile. In particular,
            // unknown PIDs must never become user 0, and parcels cannot invent a provider.
            providerInfo = mPms.getProviderInfo(new ComponentName(providerInfo.packageName, providerInfo.name),
                    PackageManager.GET_META_DATA, caller.userId);
            if (providerInfo == null || providerInfo.authority == null || providerInfo.authority.isEmpty()) {
                throw new SecurityException("Provider is not registered in the caller's profile");
            }
        }
        boolean google = GmsCompat.isCorePackage(providerInfo.packageName);
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(providerInfo.packageName,
                providerInfo.processName, caller.userId, -1, callingPid, caller.userGeneration);
        if (processRecord == null) {
            if (google) return null; // Backoff/circuit-open is a normal unavailable-provider result.
            throw new RuntimeException("Unable to create process " + providerInfo.name);
        }
        if (google && (processRecord.bActivityThread == null
                || !processRecord.bActivityThread.asBinder().isBinderAlive())) {
            BProcessManagerService.get().onGoogleBinderDied(processRecord);
            return null;
        }
        synchronized (mPms.getLifecycleLock()) {
            if (captureCallerGeneration(caller.userId) != caller.userGeneration
                    || BProcessManagerService.get().findProcessByPid(callingPid) != caller) {
                throw new SecurityException("Provider caller expired while connecting");
            }
            requirePreparedProcess(processRecord, providerInfo, caller.userId, caller.userGeneration);
        }
        try {
            IBinder provider = processRecord.bActivityThread.acquireContentProviderClient(providerInfo);
            return google && provider != null && !provider.isBinderAlive() ? null : provider;
        } catch (DeadObjectException failure) {
            if (google) {
                BProcessManagerService.get().onGoogleBinderDied(processRecord);
                Slog.w(TAG, "stage=googleProviderUnavailable reason=dead_client provider=" + providerInfo.name
                        + " package=" + processRecord.getPackageName() + " userId=" + processRecord.userId
                        + " generation=" + processRecord.userGeneration + " targetProcess=" + processRecord.processName
                        + " targetPid=" + processRecord.pid);
                return null;
            }
            Slog.e(TAG, "Provider client died", failure);
            return null;
        } catch (Throwable t) {
            Slog.e("BActivityManagerService", "Container operation failed", t);
            return null;
        }
    }

    @Override
    public int resolveCallingUid(int callerPid) {
        return BProcessManagerService.get().resolveCallingUid(callerPid);
    }

    @Override
    public AppConfig resolveProviderCaller(int callerPid, IBinder expectedProcessToken) {
        return BProcessManagerService.get().resolveProviderCaller(callerPid, expectedProcessToken);
    }

    @Override
    public Intent sendBroadcast(Intent intent, String resolvedType, int userId) {
        return mBroadcastManager.prepare(intent, resolvedType, null, null, null, userId);
    }

    @Override
    public Intent prepareBroadcast(Intent intent, String resolvedType, String[] permissions, String[] excludedPermissions, String[] excludedPackages, int userId) {
        return mBroadcastManager.prepare(intent, resolvedType, permissions, excludedPermissions, excludedPackages, userId);
    }

    @Override
    public void registerBroadcastReceiver(IBinder receiver, android.content.IntentFilter filter, String permission, int flags) throws RemoteException {
        mBroadcastManager.registerDynamic(receiver, filter, permission, flags);
    }

    @Override
    public void unregisterBroadcastReceiver(IBinder receiver) {
        mBroadcastManager.unregisterDynamic(receiver);
    }

    /** Trusted in-process notifications only; never exported through AIDL. */
    public void sendSystemBroadcast(Intent intent, int userId, long generation) {
        mBroadcastManager.sendSystemBroadcast(intent, userId, generation);
    }
    @Override
    public IBinder peekService(Intent intent, String resolvedType, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.peekService(intent, resolvedType, userId);
        }
    }

    @Override
    public void onActivityCreated(int taskId, IBinder token, IBinder activityRecord) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        BUserInfo user = process == null ? null : BUserManagerService.get().getUserInfo(process.userId);
        if (process == null || user == null || user.createTime != process.userGeneration
                || !(activityRecord instanceof ActivityRecord) || token == null || taskId < 0) {
            throw new SecurityException("Activity task owner is unavailable");
        }
        ActivityRecord record = (ActivityRecord) activityRecord;
        if (record.userId != process.userId || record.userGeneration != process.userGeneration
                || record.info == null || BProcessManagerService.get().findProcessRecord(
                        record.info.packageName, record.info.processName, record.userId) != process) {
            throw new SecurityException("Activity task belongs to another process generation");
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityCreated(process, taskId, token, record);
        }
    }

    @Override
    public void onActivityResumed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityResumed(process.userId, token);
        }
    }

    @Override
    public void onActivityDestroyed(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onActivityDestroyed(process.userId, token);
        }
    }

    @Override
    public void onFinishActivity(IBinder token) throws RemoteException {
        int callingPid = Binder.getCallingPid();
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(callingPid);
        if (process == null) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(process.userId);
        synchronized (userSpace.mStack) {
            userSpace.mStack.onFinishActivity(process.userId, token);
        }
    }

    @Override
    public RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId) throws RemoteException {
        long generation = 0L;
        try {
            generation = captureCallerGeneration(userId);
            RunningAppProcessInfo appProcessInfo = new RunningAppProcessInfo();
            appProcessInfo.mAppProcessInfoList = BProcessManagerService.get()
                    .getRunningAppProcessesAsUser(callerPackage, userId, generation);
            if (captureCallerGeneration(userId) != generation) {
                throw new SecurityException("Process query user lifetime expired");
            }
            return appProcessInfo;
        } catch (RuntimeException failure) {
            logProcessQueryFailure("queryRunningProcesses", callerPackage, userId, generation, failure);
            throw failure;
        }
    }

    @Override
    public RunningServiceInfo getRunningServices(String callerPackage, int userId) throws RemoteException {
        long generation = 0L;
        try {
            generation = captureCallerGeneration(userId);
            UserSpace userSpace = getOrCreateSpaceLocked(userId);
            RunningServiceInfo info = userSpace.mActiveServices.getRunningServiceInfo(callerPackage, userId, generation);
            if (captureCallerGeneration(userId) != generation) {
                throw new SecurityException("Service query user lifetime expired");
            }
            return info;
        } catch (RuntimeException failure) {
            logProcessQueryFailure("queryRunningServices", callerPackage, userId, generation, failure);
            throw failure;
        }
    }

    private void logProcessQueryFailure(String action, String packageName, int userId, long generation, RuntimeException failure) {
        if (!Slog.isLoggable(TAG, android.util.Log.WARN)) return;
        long now = SystemClock.elapsedRealtime();
        long previous = mLastProcessQueryFailureLog.get();
        if (now - previous >= PROCESS_QUERY_FAILURE_LOG_INTERVAL_MS
                && mLastProcessQueryFailureLog.compareAndSet(previous, now)) {
            Slog.w(TAG, "action=" + action + " stage=failed package=" + packageName
                    + " userId=" + userId + " generation=" + generation
                    + " reason=" + failure.getClass().getSimpleName(), failure);
        }
    }

    @Override
    public void scheduleBroadcastReceiver(Intent intent, PendingResultData pendingResultData, int userId) {
        // Legacy public ingress cannot authenticate host wrappers or a host PendingResult token.
        throw new SecurityException("Use the authenticated broadcast carrier");
    }

    @Override
    public void finishBroadcast(PendingResultData data) {
        mBroadcastManager.finish(data);
    }
    @Override
    public String getCallingPackage(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingPackage(token, userId);
        }
    }

    @Override
    public ComponentName getCallingActivity(IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mStack) {
            return userSpace.mStack.getCallingActivity(token, userId);
        }
    }

    @Override
    public void getIntentSender(IBinder target, String packageName, int uid, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = new PendingIntentRecord();
            record.uid = uid;
            record.packageName = packageName;
            userSpace.mIntentSenderRecords.put(target, record);
        }
    }

    @Override
    public String getPackageForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.packageName;
            }
        }
        return null;
    }

    @Override
    public int getUidForIntentSender(IBinder target, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mIntentSenderRecords) {
            PendingIntentRecord record = userSpace.mIntentSenderRecords.get(target);
            if (record != null) {
                return record.uid;
            }
        }
        return -1;
    }

    @Override
    public void onStartCommand(Intent intent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }

    @Override
    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }

    @Override
    public void onServiceDestroy(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }

    @Override
    public int stopService(Intent intent, String resolvedType, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.stopService(intent, resolvedType, userId);
        }
    }

    @Override
    public Intent bindService(Intent service, IBinder binder, String resolvedType, int userId) throws RemoteException {
        PreparedServiceLaunch launch = prepareServiceLaunch(service, resolvedType, userId);
        if (launch == null) return service;
        if (launch.process == null) return null;
        synchronized (launch.space.mActiveServices) {
            synchronized (mPms.getLifecycleLock()) {
                requireServiceLaunch(launch, resolvedType, userId);
                return launch.space.mActiveServices.bindService(launch.intent, binder, resolvedType,
                        userId, launch.process, launch.generation);
            }
        }
    }

    private static final class PreparedServiceLaunch {
        UserSpace space;
        Intent intent;
        ApplicationInfo application;
        String name;
        String processName;
        ProcessRecord process;
        long generation;
    }

    private PreparedServiceLaunch prepareServiceLaunch(Intent intent, String resolvedType, int userId) {
        if (intent == null) throw new IllegalArgumentException("A service intent is required");
        PreparedServiceLaunch launch = new PreparedServiceLaunch();
        launch.intent = new Intent(intent);
        synchronized (mPms.getLifecycleLock()) {
            launch.generation = captureCallerGeneration(userId);
            launch.space = getOrCreateSpaceLocked(userId);
            ResolveInfo target = mPms.resolveService(launch.intent, 0, resolvedType, userId);
            if (target == null || target.serviceInfo == null) return null;
            launch.application = copyPendingApplication(target.serviceInfo.applicationInfo);
            launch.name = target.serviceInfo.name;
            launch.processName = target.serviceInfo.processName;
        }
        launch.process = BProcessManagerService.get().startProcessLocked(launch.application.packageName,
                launch.processName, userId, -1, Binder.getCallingPid(), launch.generation);
        if (launch.process == null && !GmsCompat.isCorePackage(launch.application.packageName)) {
            throw new IllegalStateException("Service process could not start");
        }
        return launch;
    }

    private void requireServiceLaunch(PreparedServiceLaunch launch, String resolvedType, int userId) {
        if (captureCallerGeneration(userId) != launch.generation) throw new SecurityException("Service user lifetime expired");
        requirePendingSpace(launch.space, launch.application, userId, launch.generation);
        ResolveInfo current = mPms.resolveService(launch.intent, 0, resolvedType, userId);
        if (current == null || current.serviceInfo == null
                || !PendingLaunchPolicy.isSameResolvedTarget(launch.application.packageName, launch.name,
                launch.processName, current.serviceInfo.packageName, current.serviceInfo.name, current.serviceInfo.processName)
                || !samePendingApplication(launch.application, current.serviceInfo.applicationInfo)) {
            throw new SecurityException("Service target changed while connecting");
        }
        requirePreparedProcess(launch.process, current.serviceInfo, userId, launch.generation);
    }

    @Override
    public void unbindService(IBinder binder, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public void stopServiceToken(ComponentName className, IBinder token, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.stopServiceToken(className, token, userId);
        }
    }

    @Override
    public AppConfig initProcess(String packageName, String processName, int userId) throws RemoteException {
        long generation = captureCallerGeneration(userId);
        ProcessRecord processRecord = BProcessManagerService.get().startProcessLocked(
                packageName, processName, userId, -1, Binder.getCallingPid(), generation);
        if (processRecord == null)
            return null;
        return processRecord.getClientConfig();
    }

    @Override
    public boolean stopServiceTokenIfCurrent(ComponentName className, IBinder token, int startId, int userId) {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.stopServiceTokenIfCurrent(className, token, startId, userId);
        }
    }

    @Override
    public Bundle resolveMediaCreationOwner(int callerPid) {
        return BProcessManagerService.get().resolveMediaCreationOwner(callerPid);
    }

    @Override
    public void restartProcess(String packageName, String processName, int userId) throws RemoteException {
        requireCurrentUserCaller(userId);
        BProcessManagerService.get().restartAppProcess(packageName, processName, userId);
    }

    @Override
    public void startActivity(Intent intent, int userId) {
        int result = startActivityForGeneration(userId, captureCallerGeneration(userId),
                intent, null, null, null, -1, -1, null);
        if (result < 0) throw new IllegalStateException("Activity dispatch failed: " + result);
    }

    @Override
    public void startActivityIfMatches(Intent intent, int userId, long expectedUserCreateTime) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        if (intent == null || userId < 0 || expectedUserCreateTime <= 0L) {
            throw new IllegalArgumentException("A captured launch target is required");
        }
        String packageName = intent.getComponent() == null ? intent.getPackage()
                : intent.getComponent().getPackageName();
        if (packageName == null || packageName.isEmpty()) {
            throw new IllegalArgumentException("A package-scoped launch intent is required");
        }
        try {
            // The existing deferred path checks generation before process preparation,
            // then rechecks the exact space/process under stack -> lifecycle ordering.
            startPendingActivity(intent, userId, expectedUserCreateTime, packageName);
            Slog.i(TAG, "action=startActivity stage=requestDispatched package=" + packageName
                    + " userId=" + userId + " generation=" + expectedUserCreateTime
                    + " startupComplete=unknown");
        } catch (RuntimeException failure) {
            Slog.w(TAG, "action=startActivity stage=requestRejected package=" + packageName
                    + " userId=" + userId + " generation=" + expectedUserCreateTime, failure);
            throw failure;
        }
    }

    @Override
    public int startActivityAms(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) throws RemoteException {
        return startActivityForGeneration(userId, captureCallerGeneration(userId), intent, resolvedType,
                resultTo, resultWho, requestCode, flags, options);
    }

    @Override
    public int startActivities(int userId, Intent[] intent, String[] resolvedType, IBinder resultTo, Bundle options) throws RemoteException {
        if (intent == null || resolvedType == null || intent.length != resolvedType.length) {
            throw new IllegalArgumentException("Activity intents and resolved types must match");
        }
        long generation;
        synchronized (mPms.getLifecycleLock()) {
            generation = captureCallerGeneration(userId);
            for (int i = 0; i < intent.length; i++) {
                ResolveInfo target = mPms.resolveActivity(intent[i], PackageManager.GET_ACTIVITIES,
                        resolvedType[i], userId);
                if (target == null || target.activityInfo == null) {
                    return com.nexbox.core.utils.compat.ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
            }
        }
        for (int i = 0; i < intent.length; i++) {
            int result = startActivityForGeneration(userId, generation, intent[i], resolvedType[i],
                    resultTo, null, -1, 0, options);
            if (result < 0) return result;
        }
        return 0;
    }

    private long captureCallerGeneration(int userId) {
        synchronized (mPms.getLifecycleLock()) {
            requireCurrentUserCaller(userId);
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null || user.createTime <= 0L) throw new SecurityException("Activity user lifetime expired");
            return user.createTime;
        }
    }

    private int startActivityForGeneration(int userId, long generation, Intent intent, String resolvedType,
                                           IBinder resultTo, String resultWho, int requestCode, int flags,
                                           Bundle options) {
        if (intent == null) throw new IllegalArgumentException("A launch intent is required");
        Intent selected = new Intent(intent);
        UserSpace space;
        ApplicationInfo application;
        String activityName;
        String processName;
        synchronized (mPms.getLifecycleLock()) {
            if (captureCallerGeneration(userId) != generation) throw new SecurityException("Activity user lifetime expired");
            space = getOrCreateSpaceLocked(userId);
            ResolveInfo target = mPms.resolveActivity(selected, PackageManager.GET_ACTIVITIES, resolvedType, userId);
            if (target == null || target.activityInfo == null) {
                return com.nexbox.core.utils.compat.ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
            }
            application = copyPendingApplication(target.activityInfo.applicationInfo);
            activityName = target.activityInfo.name;
            processName = target.activityInfo.processName;
        }
        ProcessRecord prepared = BProcessManagerService.get().startProcessLocked(application.packageName,
                processName, userId, -1, Binder.getCallingPid(), generation);
        if (prepared == null) return com.nexbox.core.utils.compat.ActivityManagerCompat.START_CANCELED;
        synchronized (space.mStack) {
            synchronized (mPms.getLifecycleLock()) {
                if (captureCallerGeneration(userId) != generation) throw new SecurityException("Activity user lifetime expired");
                requirePendingSpace(space, application, userId, generation);
                ResolveInfo current = mPms.resolveActivity(selected, PackageManager.GET_ACTIVITIES, resolvedType, userId);
                if (current == null || current.activityInfo == null
                        || !PendingLaunchPolicy.isSameResolvedTarget(application.packageName, activityName, processName,
                        current.activityInfo.packageName, current.activityInfo.name, current.activityInfo.processName)
                        || !samePendingApplication(application, current.activityInfo.applicationInfo)) {
                    throw new SecurityException("Activity target changed while connecting");
                }
                requirePreparedProcess(prepared, current.activityInfo, userId, generation);
                return space.mStack.startActivityLocked(userId, selected, resolvedType, resultTo,
                        resultWho, requestCode, flags, options, prepared, generation);
            }
        }
    }

    private UserSpace getOrCreateSpaceLocked(int userId) {
        synchronized (mPms.getLifecycleLock()) {
            requireCurrentUserCaller(userId);
            synchronized (mUserSpace) {
                UserSpace userSpace = mUserSpace.get(userId);
                if (userSpace != null)
                    return userSpace;
                userSpace = new UserSpace();
                mUserSpace.put(userId, userSpace);
                return userSpace;
            }
        }
    }

    private void requireCurrentUserCaller(int userId) {
        com.nexbox.core.core.system.user.BUserInfo user =
                com.nexbox.core.core.system.user.BUserManagerService.get().getUserInfo(userId);
        if (userId < 0 || user == null) throw new IllegalArgumentException("Virtual user does not exist");
        int pid = Binder.getCallingPid();
        if (pid == android.os.Process.myPid() && Binder.getCallingUid() == NexBoxCore.getHostUid()) return;
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(pid);
        if (caller == null) {
            com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
            return;
        }
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller.userId != userId
                || caller.userGeneration != user.createTime || caller.bActivityThread == null
                || !caller.bActivityThread.asBinder().isBinderAlive()) {
            throw new SecurityException("Activity/service request belongs to another user lifetime");
        }
    }

    @Override
    public void systemReady() {
        mBroadcastManager.startup();
    }

    @Override public Bundle pendingIntentOperation(String operation, Bundle request) {
        return VirtualPendingIntents.operation(operation, request);
    }
    @Override public Bundle documentOperation(String operation, Bundle request) {
        return com.nexbox.core.media.PrivateDocuments.operation(operation, request);
    }
    public Intent prepareDocumentCandidate(Intent intent, int userId) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        UserSpace space = getOrCreateSpaceLocked(userId);
        synchronized (space.mStack) { return space.mStack.prepareHostDocumentActivity(intent, userId); }
    }
    public void dispatchPendingBroadcast(Intent intent, int userId, long generation, String creator) {
        mBroadcastManager.dispatchPending(intent, userId, generation, creator);
    }

    /** Prepare without global locks; dispatch only into the originally captured user space. */
    public void startPendingActivity(Intent intent, int userId, long generation, String creator) {
        Intent selected = new Intent(intent);
        UserSpace space;
        ApplicationInfo creatorInfo;
        ApplicationInfo targetApplication;
        String targetActivityName;
        String targetProcess;
        synchronized (mPms.getLifecycleLock()) {
            creatorInfo = copyPendingApplication(requirePendingCreator(creator, userId, generation));
            space = getOrCreateSpaceLocked(userId);
            ResolveInfo target = mPms.resolveActivity(selected, PackageManager.GET_ACTIVITIES, selected.getType(), userId);
            if (target == null || target.activityInfo == null
                    || !canAccessPendingComponent(creator, target.activityInfo, target.activityInfo.permission, userId)) {
                throw new SecurityException("Pending activity not accessible");
            }
            // ActivityInfo.name is the real target for aliases. Keep the original intent
            // so final resolution still checks the alias's exported/permission contract.
            targetActivityName = target.activityInfo.name;
            targetApplication = copyPendingApplication(target.activityInfo.applicationInfo);
            targetProcess = target.activityInfo.processName;
        }
        ProcessRecord prepared = BProcessManagerService.get().startProcessLocked(targetApplication.packageName,
                targetProcess, userId, -1, Binder.getCallingPid(), generation);
        if (prepared == null) throw new IllegalStateException("Pending activity process could not start");
        // Match ordinary launch ordering (per-user stack -> lifecycle), never the reverse.
        synchronized (space.mStack) {
            synchronized (mPms.getLifecycleLock()) {
                requirePendingSpace(space, creatorInfo, userId, generation);
                ResolveInfo current = mPms.resolveActivity(selected, PackageManager.GET_ACTIVITIES, selected.getType(), userId);
                if (current == null || current.activityInfo == null
                        || !PendingLaunchPolicy.isSameResolvedTarget(targetApplication.packageName, targetActivityName,
                        targetProcess, current.activityInfo.packageName, current.activityInfo.name, current.activityInfo.processName)
                        || !samePendingApplication(targetApplication, current.activityInfo.applicationInfo)
                        || !canAccessPendingComponent(creator, current.activityInfo, current.activityInfo.permission, userId)) {
                    throw new SecurityException("Pending activity is no longer accessible");
                }
                requirePreparedProcess(prepared, current.activityInfo, userId, generation);
                int result = space.mStack.startActivityLocked(userId, selected, selected.getType(), null,
                        null, -1, -1, null, prepared, generation);
                if (result < 0) throw new IllegalStateException("Pending activity dispatch failed: " + result);
            }
        }
    }

    public ComponentName startPendingService(Intent intent, String resolvedType, boolean requireForeground,
                                             int userId, long generation, String creator) {
        Intent selected = new Intent(intent);
        UserSpace space;
        ApplicationInfo creatorInfo;
        ApplicationInfo targetApplication;
        String targetProcess;
        synchronized (mPms.getLifecycleLock()) {
            creatorInfo = copyPendingApplication(requirePendingCreator(creator, userId, generation));
            space = getOrCreateSpaceLocked(userId);
            ResolveInfo target = mPms.resolveService(selected, 0, resolvedType, userId);
            if (target == null || target.serviceInfo == null
                    || !canAccessPendingComponent(creator, target.serviceInfo, target.serviceInfo.permission, userId)) {
                throw new SecurityException("Pending service not accessible");
            }
            selected.setComponent(new ComponentName(target.serviceInfo.packageName, target.serviceInfo.name));
            targetApplication = copyPendingApplication(target.serviceInfo.applicationInfo);
            targetProcess = target.serviceInfo.processName;
        }
        ProcessRecord prepared = BProcessManagerService.get().startProcessLocked(selected.getComponent().getPackageName(),
                targetProcess, userId, -1, Binder.getCallingPid(), generation);
        if (prepared == null) return null;
        synchronized (space.mActiveServices) {
            synchronized (mPms.getLifecycleLock()) {
                requirePendingSpace(space, creatorInfo, userId, generation);
                ResolveInfo current = mPms.resolveService(selected, 0, resolvedType, userId);
                if (current == null || current.serviceInfo == null
                        || !samePendingApplication(targetApplication, current.serviceInfo.applicationInfo)
                        || !canAccessPendingComponent(creator, current.serviceInfo, current.serviceInfo.permission, userId)) {
                    throw new SecurityException("Pending service is no longer accessible");
                }
                requirePreparedProcess(prepared, current.serviceInfo, userId, generation);
                return space.mActiveServices.startService(selected, resolvedType, requireForeground, userId,
                        prepared, generation);
            }
        }
    }

    private ApplicationInfo requirePendingCreator(String creator, int userId, long generation) {
        com.nexbox.core.core.system.user.BUserInfo user =
                com.nexbox.core.core.system.user.BUserManagerService.get().getUserInfo(userId);
        if (user == null || generation <= 0L || user.createTime != generation) {
            throw new SecurityException("PendingIntent user lifetime expired");
        }
        ApplicationInfo info = mPms.getApplicationInfo(creator, 0, userId);
        if (info == null) throw new SecurityException("PendingIntent creator is no longer installed");
        return info;
    }

    private void requirePendingSpace(UserSpace expectedSpace, ApplicationInfo expectedCreator,
                                      int userId, long generation) {
        com.nexbox.core.core.system.user.BUserInfo user =
                com.nexbox.core.core.system.user.BUserManagerService.get().getUserInfo(userId);
        ApplicationInfo creator = requirePendingCreator(expectedCreator.packageName, userId, generation);
        synchronized (mUserSpace) {
            if (!PendingLaunchPolicy.isCurrentSpace(generation, user == null ? 0L : user.createTime,
                    expectedSpace, mUserSpace.get(userId))) {
                throw new SecurityException("PendingIntent user space was replaced");
            }
        }
        if (!samePendingApplication(expectedCreator, creator)) {
            throw new SecurityException("PendingIntent creator changed while connecting");
        }
    }

    private static boolean samePendingApplication(ApplicationInfo expected, ApplicationInfo current) {
        return expected != null && current != null && expected.uid == current.uid
                && Objects.equals(expected.packageName, current.packageName)
                && Objects.equals(expected.sourceDir, current.sourceDir)
                && Arrays.equals(expected.splitSourceDirs, current.splitSourceDirs);
    }

    private static ApplicationInfo copyPendingApplication(ApplicationInfo source) {
        if (source == null) throw new SecurityException("PendingIntent application metadata unavailable");
        ApplicationInfo copy = new ApplicationInfo(source);
        if (source.splitSourceDirs != null) copy.splitSourceDirs = source.splitSourceDirs.clone();
        return copy;
    }

    /** The owning user-space lock and lifecycle lock are held; this never starts/retries a process. */
    static void requirePreparedProcess(ProcessRecord expected, ComponentInfo component, int userId, long generation) {
        ProcessRecord current = component == null ? null : BProcessManagerService.get()
                .findProcessRecord(component.packageName, component.processName, userId);
        if (expected == null || component == null || !Objects.equals(expected.processName, component.processName)
                || !PendingLaunchPolicy.isCurrentProcess(userId, generation, expected.userId, expected.userGeneration,
                expected, current, expected.bActivityThread != null && expected.appThread != null
                        && expected.bActivityThread.asBinder().isBinderAlive())) {
            throw new SecurityException("Prepared PendingIntent process is no longer current");
        }
    }

    public boolean canAccessPendingComponent(String creator, android.content.pm.ComponentInfo target, String permission, int userId) {
        return creator.equals(target.packageName) || target.exported && mBroadcastManager.hasPermission(creator, permission, userId);
    }
}
