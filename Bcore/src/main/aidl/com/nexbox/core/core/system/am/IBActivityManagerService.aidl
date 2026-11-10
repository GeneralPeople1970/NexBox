package com.nexbox.core.core.system.am;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.ComponentName;
import android.content.pm.ServiceInfo;
import android.content.pm.ProviderInfo;
import android.os.IBinder;
import java.lang.String;
import android.app.IServiceConnection;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.UnbindRecord;
import android.os.Bundle;
import com.nexbox.core.entity.am.RunningAppProcessInfo;
import com.nexbox.core.entity.am.PendingResultData;
import com.nexbox.core.entity.am.RunningServiceInfo;

interface IBActivityManagerService {
    AppConfig initProcess(String packageName, String processName, int userId);
    void restartProcess(String packageName, String processName, int userId);

    void startActivity(in Intent intent, int userId);
    int startActivityAms(int userId, in Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, in Bundle options);
    int startActivities(int userId, in Intent[] intent, in String[] resolvedType, IBinder resultTo, in Bundle options);

    ComponentName startService(in Intent intent, String resolvedType, boolean requireForeground, int userId);
    int stopService(in Intent intent,in String resolvedType, int userId);

    Intent bindService(in Intent service, in IBinder binder, String resolvedType, int userId);
    void unbindService(in IBinder binder, int userId);

    void stopServiceToken(in ComponentName className, in IBinder token, int userId);

    void onStartCommand(in Intent proxyIntent, int userId);
    UnbindRecord onServiceUnbind(in Intent proxyIntent, int userId);
    void onServiceDestroy(in Intent proxyIntent, int userId);

    IBinder acquireContentProviderClient(in ProviderInfo providerInfo);
    Intent sendBroadcast(in Intent intent, String resolvedType, int userId);
    IBinder peekService(in Intent intent, String resolvedType, int userId);

    void onActivityCreated(int taskId, IBinder token, IBinder activityRecord);
    void onActivityResumed(IBinder token);
    void onActivityDestroyed(IBinder token);
    void onFinishActivity(IBinder token);

    RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId);
    RunningServiceInfo getRunningServices(String callerPackage, int userId);

    void scheduleBroadcastReceiver(in Intent intent, in PendingResultData pendingResultData, int userId);
    void finishBroadcast(in PendingResultData data);

    String getCallingPackage(in IBinder token, int userId);
    ComponentName getCallingActivity(in IBinder token, int userId);

    void getIntentSender(in IBinder target, String packageName, int uid, int userId);
    String getPackageForIntentSender(in IBinder target, int userId);
    int getUidForIntentSender(in IBinder target, int userId);

    // Host media-provider gateway only. The pid comes from its incoming Binder call.
    Bundle resolveMediaCreationOwner(int callerPid);

    // Guest Binder identity hook only. Resolves a live caller in the requesting guest's user.
    int resolveCallingUid(int callerPid);

    // Async provider channels bind the real synchronous caller to its server-issued lifetime token.
    AppConfig resolveProviderCaller(int callerPid, IBinder expectedProcessToken);

    boolean stopServiceTokenIfCurrent(in ComponentName className, in IBinder token, int startId, int userId);
    Intent prepareBroadcast(in Intent intent, String resolvedType, in String[] permissions, in String[] excludedPermissions, in String[] excludedPackages, int userId);
    void registerBroadcastReceiver(in IBinder receiver, in IntentFilter filter, String permission, int flags);
    void unregisterBroadcastReceiver(in IBinder receiver);
    Bundle pendingIntentOperation(String operation, in Bundle request);
    Bundle documentOperation(String operation, in Bundle request);
    // Host launches carry the selected profile identity through splash/deferred dispatch.
    void startActivityIfMatches(in Intent intent, int userId, long expectedUserCreateTime);
}
