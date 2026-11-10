package com.nexbox.core.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.Build;
import android.os.IBinder;
import android.os.Message;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.fake.hook.IInjectHook;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.proxy.record.ProxyActivityRecord;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.ActivityIntentLog;
import com.nexbox.core.utils.compat.BuildCompat;



public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = NexBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                    if (handleLaunchActivity(msg.obj)) {
                        getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                        return true;
                    }
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();

        if (mActivityCallbacks == null) {
            // Lifecycle-only transactions legitimately have no activity callbacks.
            return null;
        }

        for (Object obj : mActivityCallbacks) {
            if (obj != null && BRLaunchActivityItem.getRealClass().isInstance(obj)) {
                return obj;
            }
        }
        return null;
    }

    private boolean handleLaunchActivity(Object client) {
        if (Build.VERSION.SDK_INT >= 35) {
            List<?> items = GoogleClientTransactionCompat.items(client);
            if (items != null && !items.isEmpty()) {
                boolean google = GmsCompat.isCorePackage(BActivityThread.getAppPackageName());
                for (Object item : items) {
                    if (item == null || !BRLaunchActivityItem.getRealClass().isInstance(item)) continue;
                    Intent intent = BRLaunchActivityItem.get(item).mIntent();
                    if (intent == null) continue;
                    ActivityInfo info = ProxyActivityRecord.create(intent).mActivityInfo;
                    if (info == null || !GmsCompat.isCorePackage(info.packageName)) continue;
                    google = true;
                    // A modern transaction may carry launches for more than one activity.
                    if (handleLaunchActivity(client, item, true)) return true;
                }
                if (google) return false;
            }
        }
        Object r = getLaunchActivityItem(client);
        if (r == null)
            return false;
        return handleLaunchActivity(client, r, false);
    }

    private boolean handleLaunchActivity(Object client, Object r, boolean modernGoogle) {
        Intent intent;
        IBinder token;
        intent = BRLaunchActivityItem.get(r).mIntent();
        token = modernGoogle ? GoogleClientTransactionCompat.activityToken(r) : null;
        if (token == null) token = BRClientTransaction.get(client).mActivityToken();

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                NexBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);
                if (GmsCompat.isCorePackage(activityInfo.packageName) && BActivityThread.getAppConfig() == null) {
                    // Leave an inert host proxy activity to finish. Otherwise ProxyActivity
                    // would immediately relaunch this target while its crash circuit is open.
                    LaunchActivityItemContext rejected = BRLaunchActivityItem.get(r);
                    rejected._set_mIntent(new Intent().setComponent(intent.getComponent()));
                    return false;
                }

                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                // A login/deep-link request is not interchangeable with the package launcher.
                ProxyActivityRecord.saveStub(intent, stubRecord.mTarget, stubRecord.mActivityInfo,
                        stubRecord.mActivityRecord, stubRecord.mUserId,
                        stubRecord.mUserGeneration, stubRecord.mActivityIdentity);
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(intent);
                launchActivityItemContext._set_mInfo(activityInfo);
                return true;
            }
            
            if (stubRecord.mUserId != BActivityThread.getUserId()
                    || (stubRecord.mUserGeneration > 0 && stubRecord.mUserGeneration
                    != BActivityThread.getAppConfig().userGeneration)
                    || !activityInfo.packageName.equals(BActivityThread.getAppPackageName())) {
                throw new SecurityException("Activity launch belongs to another virtual process");
            }
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }
            if (stubRecord.mTarget == null) throw new IllegalArgumentException("Missing target activity intent");
            stubRecord.mTarget.setExtrasClassLoader(BActivityThread.getApplication().getClassLoader());
            com.nexbox.core.utils.IntentSanitizer.restoreSanitizedClassExtras(stubRecord.mTarget,
                    BActivityThread.getApplication().getClassLoader());

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            NexBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            // Android 12/12L create the record in preExecute(), before this Handler callback.
            // Android 13+ create it in execute(), so update the launch item instead.
            if (BuildCompat.isTiramisu()) {
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else {
                Object record = BRActivityThread.get(NexBoxCore.mainThread()).getLaunchingActivity(token);
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
                clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());

                checkActivityClient();
            }
            ActivityIntentLog.log("launchRestored", stubRecord.mTarget, stubRecord.mUserId,
                    stubRecord.mUserGeneration, true, "package=" + activityInfo.packageName);
        }
        return false;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                NexBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            Slog.e("HCallbackProxy", "Container operation failed", t);
        }
    }
}
