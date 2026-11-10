package com.nexbox.core.fake.frameworks;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.RemoteException;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.UnbindRecord;
import com.nexbox.core.entity.am.PendingResultData;
import com.nexbox.core.entity.am.RunningAppProcessInfo;
import com.nexbox.core.entity.am.RunningServiceInfo;
import com.nexbox.core.utils.Slog;


public class BActivityManager extends CoreServiceManager<IBActivityManagerService> {
    private static final String TAG = "BActivityManager";
    private static final BActivityManager sActivityManager = new BActivityManager();

    public static BActivityManager get() {
        return sActivityManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.ACTIVITY_MANAGER;
    }

    public AppConfig initProcess(String packageName, String processName, int userId) {
        int retryCount = 0;
        final int maxRetries = 3;
        
        while (retryCount < maxRetries) {
            try {
                IBActivityManagerService service = getService();
                if (service != null) {
                    AppConfig result = service.initProcess(packageName, processName, userId);
                    if (result != null) {
                        return result;
                    } else {
                        Slog.w(TAG, "initProcess returned null for package: " + packageName + ", process: " + processName + ", retry " + (retryCount + 1) + "/" + maxRetries);
                    }
                } else {
                    Slog.w(TAG, "ActivityManager service is null for initProcess, retry " + (retryCount + 1) + "/" + maxRetries);
                }
            } catch (DeadObjectException e) {
                Slog.w(TAG, "ActivityManager service died during initProcess, clearing cache and retrying " + (retryCount + 1) + "/" + maxRetries, e);
                clearServiceCache();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException in initProcess", e);
                break;
            } catch (Exception e) {
                Slog.e(TAG, "Unexpected error in initProcess", e);
                break;
            }
            retryCount++;
        }
        
        Slog.e(TAG, "Failed to initProcess after " + maxRetries + " retries for package: " + packageName + ", process: " + processName);
        return null;
    }

    public void restartProcess(String packageName, String processName, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.restartProcess(packageName, processName, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void startActivity(Intent intent, int userId) {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        try { service.startActivity(intent, userId); }
        catch (RemoteException failure) { throw new IllegalStateException("Activity launch could not be confirmed", failure); }
    }

    public void startActivityIfMatches(Intent intent, int userId, long expectedUserCreateTime) {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        try { service.startActivityIfMatches(intent, userId, expectedUserCreateTime); }
        catch (RemoteException failure) { throw new IllegalStateException("Activity launch could not be confirmed", failure); }
    }

    public int startActivityAms(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        try { return service.startActivityAms(userId, intent, resolvedType, resultTo, resultWho, requestCode, flags, options); }
        catch (RemoteException failure) { throw new IllegalStateException("Activity launch could not be confirmed", failure); }
    }

    public int startActivities(int userId, Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle options) {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        try { return service.startActivities(userId, intents, resolvedTypes, resultTo, options); }
        catch (RemoteException failure) { throw new IllegalStateException("Activity launch could not be confirmed", failure); }
    }

    public ComponentName startService(Intent intent, String resolvedType, boolean requireForeground, int userId) {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        try {
            // A lost reply does not prove the start was rejected. Never replay this mutation.
            return service.startService(intent, resolvedType, requireForeground, userId);
        } catch (DeadObjectException e) {
            clearServiceCache();
            throw new IllegalStateException("Virtual activity service died during service start", e);
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not deliver the virtual service start", e);
        }
    }

    public int stopService(Intent intent, String resolvedType, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                return service.stopService(intent, resolvedType, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return -1;
    }

    public Intent bindService(Intent service, IBinder binder, String resolvedType, int userId) {
        try {
            IBActivityManagerService serviceManager = getService();
            if (serviceManager != null) {
                return serviceManager.bindService(service, binder, resolvedType, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public void unbindService(IBinder binder, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.unbindService(binder, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void stopServiceToken(ComponentName componentName, IBinder token, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.stopServiceToken(componentName, token, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onStartCommand(proxyIntent, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                return service.onServiceUnbind(proxyIntent, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onServiceDestroy(proxyIntent, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                return service.acquireContentProviderClient(providerInfo);
            } else {
                Slog.w(TAG, "ActivityManager service is null for acquireContentProviderClient");
            }
        } catch (DeadObjectException e) {
            Slog.w(TAG, "ActivityManager service died during acquireContentProviderClient, clearing cache", e);
            clearServiceCache();
        } catch (RemoteException e) {
            Slog.e(TAG, "RemoteException in acquireContentProviderClient", e);
        } catch (Exception e) {
            Slog.e(TAG, "Unexpected error in acquireContentProviderClient", e);
        }
        return null;
    }

    public Intent sendBroadcast(Intent intent, String resolvedType, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                return service.sendBroadcast(intent, resolvedType, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public IBinder peekService(Intent intent, String resolvedType, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                return service.peekService(intent, resolvedType, userId);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public void onActivityCreated(int taskId, IBinder token, IBinder activityRecord) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onActivityCreated(taskId, token, activityRecord);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void onActivityResumed(IBinder token) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onActivityResumed(token);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void onActivityDestroyed(IBinder token) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onActivityDestroyed(token);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public void onFinishActivity(IBinder token) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.onFinishActivity(token);
            }
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public RunningAppProcessInfo getRunningAppProcesses(String callerPackage, int userId) throws RemoteException {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        RunningAppProcessInfo result = service.getRunningAppProcesses(callerPackage, userId);
        if (result == null || result.mAppProcessInfoList == null) {
            throw new IllegalStateException("Virtual process query returned no result");
        }
        return result;
    }

    public RunningServiceInfo getRunningServices(String callerPackage, int userId) throws RemoteException {
        IBActivityManagerService service = getService();
        if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
        RunningServiceInfo result = service.getRunningServices(callerPackage, userId);
        if (result == null || result.mRunningServiceInfoList == null) {
            throw new IllegalStateException("Virtual service query returned no result");
        }
        return result;
    }

    public void scheduleBroadcastReceiver(Intent intent, PendingResultData pendingResultData, int userId) throws RemoteException {
        getService().scheduleBroadcastReceiver(intent, pendingResultData, userId);
    }

    public void finishBroadcast(PendingResultData data) {
        try {
            getService().finishBroadcast(data);
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public String getCallingPackage(IBinder token, int userId) {
        try {
            return getService().getCallingPackage(token, userId);
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public ComponentName getCallingActivity(IBinder token, int userId) {
        try {
            return getService().getCallingActivity(token, userId);
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public void getIntentSender(IBinder target, String packageName, int uid) {
        try {
            getService().getIntentSender(target, packageName, uid, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
    }

    public String getPackageForIntentSender(IBinder target) {
        try {
            return getService().getPackageForIntentSender(target, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return null;
    }

    public int getUidForIntentSender(IBinder target) {
        try {
            return getService().getUidForIntentSender(target, BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BActivityManager", "Container operation failed", e);
        }
        return -1;
    }
}
