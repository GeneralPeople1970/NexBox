package com.nexbox.core.core.system.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import black.android.app.BRActivityManagerNative;
import black.android.app.BRIActivityManager;
import black.com.android.internal.BRRstyleable;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageManagerCompat;
import com.nexbox.core.proxy.ProxyActivity;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.proxy.record.ProxyActivityRecord;
import com.nexbox.core.utils.ComponentUtils;
import com.nexbox.core.utils.ActivityIntentLog;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.ActivityManagerCompat;

import static android.content.pm.PackageManager.GET_ACTIVITIES;


@SuppressWarnings({"deprecation", "unchecked"})
public class ActivityStack {
    public static final String TAG = "ActivityStack";

    private final ActivityManager mAms;
    private final Map<Integer, TaskRecord> mTasks = new LinkedHashMap<>();
    private final Set<ActivityRecord> mLaunchingActivities = new HashSet<>();

    public static final int LAUNCH_TIME_OUT = 0;
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LAUNCH_TIME_OUT:
                    ActivityRecord record = (ActivityRecord) msg.obj;
                    if (record != null) {
                        mLaunchingActivities.remove(record);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    public ActivityStack() {
        mAms = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
    }

    public boolean containsFlag(Intent intent, int flag) {
        return (intent.getFlags() & flag) != 0;
    }

    public int startActivitiesLocked(int userId, Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle options) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }
        for (int i = 0; i < intents.length; i++) {
            ResolveInfo target = BPackageManagerService.get().resolveActivity(intents[i], GET_ACTIVITIES, resolvedTypes[i], userId);
            if (target == null || target.activityInfo == null) return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
        }
        for (int i = 0; i < intents.length; i++) {
            int result = startActivityLocked(userId, intents[i], resolvedTypes[i], resultTo, null, -1, 0, options);
            if (result < 0) return result;
        }
        return 0;
    }

    public int startActivityLocked(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) {
        return startActivityLocked(userId, intent, resolvedType, resultTo, resultWho, requestCode, flags,
                options, null, 0L);
    }

    int startActivityLocked(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho,
                            int requestCode, int flags, Bundle options, ProcessRecord preparedProcess,
                            long expectedGeneration) {
        synchronized (mTasks) {
            synchronizeTasks();
        }

        ResolveInfo resolveInfo = BPackageManagerService.get().resolveActivity(intent, GET_ACTIVITIES, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
        }
        ActivityInfo activityInfo = resolveInfo.activityInfo;
        ComponentName targetComponent = ComponentUtils.toComponentName(activityInfo);
        ActivityIntentLog.log("resolved", intent, userId, expectedGeneration, false,
                "package=" + activityInfo.packageName + " launchMode=" + activityInfo.launchMode
                        + " requestCode=" + requestCode);
        if (preparedProcess != null || expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(preparedProcess, activityInfo, userId, expectedGeneration);
        }

        ActivityRecord sourceRecord = findActivityRecordByToken(userId, resultTo);
        if (sourceRecord == null) {
            if (resultTo != null && requestCode >= 0) return ActivityManagerCompat.START_CANCELED;
            resultTo = null;
        }
        TaskRecord sourceTask = null;
        if (sourceRecord != null) {
            sourceTask = sourceRecord.task;
        }

        String taskAffinity = ComponentUtils.getTaskAffinity(activityInfo);

        int launchModeFlags = 0;
        boolean singleTop = containsFlag(intent, Intent.FLAG_ACTIVITY_SINGLE_TOP) || activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP;
        boolean newTask = containsFlag(intent, Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean clearTop = containsFlag(intent, Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean clearTask = containsFlag(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK);

        TaskRecord taskRecord = null;
        switch (activityInfo.launchMode) {
            case ActivityInfo.LAUNCH_SINGLE_TOP:
            case ActivityInfo.LAUNCH_MULTIPLE:
            case ActivityInfo.LAUNCH_SINGLE_TASK:
                taskRecord = findTaskRecordByTaskAffinityLocked(userId, taskAffinity);
                if (taskRecord == null && !newTask) {
                    taskRecord = sourceTask;
                }
                break;
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE:
                taskRecord = findTaskRecordByTaskAffinityLocked(userId, taskAffinity);
                break;
        }

        
        if (taskRecord == null || taskRecord.needNewTask()) {
            if (sourceRecord != null && requestCode >= 0) {
                return startActivityInSourceTask(intent, resolvedType, resultTo, resultWho, requestCode,
                        flags, options, userId, sourceRecord, activityInfo, launchModeFlags, preparedProcess, expectedGeneration);
            }
            return startActivityInNewTaskLocked(userId, intent, activityInfo, resultTo, launchModeFlags,
                    preparedProcess, expectedGeneration);
        }
        
        mAms.moveTaskToFront(taskRecord.id, 0);

        boolean notStartToFront = false;
        if (clearTop || singleTop || clearTask) {
            notStartToFront = true;
        }

        boolean launcherRequest = requestCode < 0 && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && intent.getData() == null && intent.getType() == null && intent.getClipData() == null
                && intent.getSelector() == null && intent.getExtras() == null;
        boolean startTaskToFront = launcherRequest && !notStartToFront
                && ComponentUtils.intentFilterEquals(taskRecord.rootIntent, intent)
                && taskRecord.rootIntent.getFlags() == intent.getFlags();

        if (startTaskToFront) {
            ActivityIntentLog.log("taskToFront", intent, userId, taskRecord.userGeneration, false,
                    "package=" + activityInfo.packageName + " taskId=" + taskRecord.id);
            return 0;
        }

        ActivityRecord topActivityRecord = taskRecord.getTopActivityRecord();
        ActivityRecord targetActivityRecord = findActivityRecordByComponentName(userId, targetComponent);
        ActivityRecord newIntentRecord = null;
        boolean ignore = false;

        if (clearTop) {
            if (targetActivityRecord != null) {
                
                synchronized (targetActivityRecord.task.activities) {
                    for (int i = targetActivityRecord.task.activities.size() - 1; i >= 0; i--) {
                        ActivityRecord next = targetActivityRecord.task.activities.get(i);
                        if (next != targetActivityRecord) {
                            next.finished = true;
                            Slog.d(TAG, "makerFinish: " + next.component.toString());
                        } else {
                            if (singleTop || activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                                    || activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                                newIntentRecord = targetActivityRecord;
                            } else {
                                
                                targetActivityRecord.finished = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (singleTop && !clearTop) {
            // A changed URI/action is the payload to onNewIntent, not a different activity.
            if (topActivityRecord != null && targetComponent.equals(topActivityRecord.component)) {
                newIntentRecord = topActivityRecord;
            } else {
                synchronized (mLaunchingActivities) {
                    for (ActivityRecord launchingActivity : mLaunchingActivities) {
                        if (launcherRequest && !launchingActivity.finished && launchingActivity.component.equals(intent.getComponent())) {
                            
                            ignore = true;
                        }
                    }
                }
            }
        }

        if (activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK && !clearTop) {
            if (topActivityRecord != null && targetComponent.equals(topActivityRecord.component)) {
                newIntentRecord = topActivityRecord;
            } else {
                ActivityRecord record = findActivityRecordByComponentName(userId, ComponentUtils.toComponentName(activityInfo));
                if (record != null) {
                    
                    newIntentRecord = record;
                    
                    synchronized (taskRecord.activities) {
                        for (int i = taskRecord.activities.size() - 1; i >= 0; i--) {
                            ActivityRecord next = taskRecord.activities.get(i);
                            if (next != record) {
                                next.finished = true;
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            newIntentRecord = topActivityRecord;
        }

        
        if (clearTask && newTask) {
            for (ActivityRecord activity : taskRecord.activities) {
                activity.finished = true;
            }
            finishAllActivity(userId);
            // CLEAR_TASK must create a fresh instance, never deliver to a record just finished.
            return startActivityInNewTaskLocked(userId, intent, activityInfo, resultTo, launchModeFlags,
                    preparedProcess, expectedGeneration);
        }
        

        if (newIntentRecord != null && requestCode < 0) {
            
            deliverNewIntentLocked(newIntentRecord, intent, expectedGeneration);
            return 0;
        } else if (ignore) {
            ActivityIntentLog.log("launcherAlreadyPending", intent, userId, taskRecord.userGeneration, false,
                    "package=" + activityInfo.packageName);
            return 0;
        }

        ActivityRecord launchSource = sourceRecord != null ? sourceRecord : topActivityRecord;
        if (launchSource == null) return startActivityInNewTaskLocked(userId, intent, activityInfo, resultTo,
                launchModeFlags, preparedProcess, expectedGeneration);
        if (resultTo == null && requestCode < 0) resultTo = launchSource.token;
        return startActivityInSourceTask(intent,
                resolvedType, resultTo, resultWho, requestCode, flags, options, userId, launchSource, activityInfo,
                launchModeFlags, preparedProcess, expectedGeneration);
    }

    private void deliverNewIntentLocked(ActivityRecord activityRecord, Intent intent, long expectedGeneration) {
        if (expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(activityRecord.processRecord, activityRecord.info,
                    activityRecord.userId, expectedGeneration);
        }
        try {
            ActivityIntentLog.log("newIntentRequested", intent, activityRecord.userId,
                    activityRecord.userGeneration, false, "package=" + activityRecord.info.packageName
                            + " taskId=" + (activityRecord.task == null ? -1 : activityRecord.task.id));
            activityRecord.processRecord.bActivityThread.handleNewIntent(activityRecord.token, intent);
            ActivityIntentLog.log("newIntentQueued", intent, activityRecord.userId,
                    activityRecord.userGeneration, false, "package=" + activityRecord.info.packageName);
        } catch (RemoteException e) {
            throw new IllegalStateException("Activity process died before intent delivery", e);
        }
    }

    private Intent startActivityProcess(int userId, Intent intent, ActivityInfo
            info, ActivityRecord record) {
        return startActivityProcess(userId, intent, info, record, null, 0L);
    }

    private Intent startActivityProcess(int userId, Intent intent, ActivityInfo info, ActivityRecord record,
                                        ProcessRecord preparedProcess, long expectedGeneration) {
        ProcessRecord targetApp;
        if (preparedProcess != null || expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(preparedProcess, info, userId, expectedGeneration);
            targetApp = preparedProcess;
        } else {
            targetApp = BProcessManagerService.get().startProcessLocked(info.packageName, info.processName,
                    userId, -1, Binder.getCallingPid());
        }
        if (targetApp == null) {
            throw new RuntimeException("Unable to create process, name:" + info.name);
        }
        record.userGeneration = targetApp.userGeneration;
        ProxyActivityRecord stubRecord = new ProxyActivityRecord(userId, info, intent, record,
                targetApp.userGeneration, record.activityIdentity);
        Intent shadow = getStartStubActivityIntentInner(intent, targetApp.bpid, userId, stubRecord, info);
        record.proxyComponent = shadow.getComponent();
        return shadow;
    }

    private int startActivityInNewTaskLocked(int userId, Intent intent, ActivityInfo
            activityInfo, IBinder resultTo, int launchMode, ProcessRecord preparedProcess, long expectedGeneration) {
        ActivityRecord record = newActivityRecord(intent, activityInfo, resultTo, userId);
        Intent shadow = startActivityProcess(userId, intent, activityInfo, record, preparedProcess, expectedGeneration);

        shadow.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shadow.addFlags(launchMode);

        ActivityIntentLog.log("newInstanceRequested", intent, userId, record.userGeneration, false,
                "package=" + activityInfo.packageName + " task=new");
        NexBoxCore.getContext().startActivity(shadow);
        return 0;
    }

    Intent prepareHostDocumentActivity(Intent intent, int userId) {
        ResolveInfo resolved = BPackageManagerService.get().resolveActivity(intent, GET_ACTIVITIES, intent.getType(), userId);
        if (resolved == null || resolved.activityInfo == null) throw new IllegalArgumentException("Picker candidate unavailable");
        intent.setFlags(intent.getFlags() & ~(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_FORWARD_RESULT));
        ActivityRecord record = newActivityRecord(intent, resolved.activityInfo, null, userId);
        return startActivityProcess(userId, intent, resolved.activityInfo, record);
    }

    private int startActivityInSourceTask(Intent intent, String resolvedType,
                                          IBinder resultTo, String resultWho, int requestCode, int flags,
                                          Bundle options,
                                          int userId, ActivityRecord sourceRecord, ActivityInfo activityInfo, int launchMode,
                                          ProcessRecord preparedProcess, long expectedGeneration) {
        if (expectedGeneration > 0L) {
            BActivityManagerService.requirePreparedProcess(sourceRecord.processRecord, sourceRecord.info,
                    userId, expectedGeneration);
        }
        ActivityRecord selfRecord = newActivityRecord(intent, activityInfo, resultTo, userId);
        Intent shadow = startActivityProcess(userId, intent, activityInfo, selfRecord, preparedProcess, expectedGeneration);
        shadow.setAction(UUID.randomUUID().toString());
        shadow.addFlags(launchMode);
        if (resultTo == null) {
            shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        ActivityIntentLog.log("newInstanceRequested", intent, userId, selfRecord.userGeneration, false,
                "package=" + activityInfo.packageName + " taskId=" + (sourceRecord.task == null ? -1 : sourceRecord.task.id));
        return realStartActivityLocked(sourceRecord.processRecord.appThread, shadow, resolvedType, resultTo, resultWho, requestCode, flags, options);
    }

    private int realStartActivityLocked(IInterface appThread, Intent intent, String resolvedType,
                                        IBinder resultTo, String resultWho, int requestCode, int flags,
                                        Bundle options) {
        try {
            flags &= ~ActivityManagerCompat.START_FLAG_DEBUG;
            flags &= ~ActivityManagerCompat.START_FLAG_NATIVE_DEBUGGING;
            flags &= ~ActivityManagerCompat.START_FLAG_TRACK_ALLOCATION;

            Integer result = BRIActivityManager.getWithException(BRActivityManagerNative.get().getDefault()).startActivity(appThread, NexBoxCore.getHostPkg(), intent,
                    resolvedType, resultTo, resultWho, requestCode, flags, null, options);
            if (result == null) throw new IllegalStateException("Activity manager returned no launch result");
            return result;
        } catch (Throwable e) {
            throw new IllegalStateException("Activity launch failed", e);
        }
    }

    private ActivityRecord getTopActivityRecord() {
        synchronized (mTasks) {
            synchronizeTasks();
        }
        List<TaskRecord> tasks = new LinkedList<>(mTasks.values());
        if (tasks.isEmpty())
            return null;
        return tasks.get(tasks.size() - 1).getTopActivityRecord();
    }

    private Intent getStartStubActivityIntentInner(Intent intent, int vpid,
                                                   int userId, ProxyActivityRecord target,
                                                   ActivityInfo activityInfo) {
        Intent shadow = new Intent();
        shadow.setFlags(intent.getFlags());
        TypedArray typedArray = null;
        try {
            Resources resources = PackageManagerCompat.getResources(NexBoxCore.getContext(), activityInfo.applicationInfo);
            int id;
            if (activityInfo.theme != 0) {
                id = activityInfo.theme;
            } else {
                id = activityInfo.applicationInfo.theme;
            }
            assert resources != null;
            typedArray = resources.newTheme().obtainStyledAttributes(id, BRRstyleable.get().Window());
            boolean windowIsTranslucent = typedArray.getBoolean(BRRstyleable.get().Window_windowIsTranslucent(), false);
            boolean forceOpaqueProxy = shouldForceOpaqueProxy(activityInfo);
            if (windowIsTranslucent && !forceOpaqueProxy) {
                shadow.setComponent(new ComponentName(NexBoxCore.getHostPkg(), ProxyManifest.TransparentProxyActivity(vpid)));
            } else {
                shadow.setComponent(new ComponentName(NexBoxCore.getHostPkg(), ProxyManifest.getProxyActivity(vpid)));
            }
            Slog.d(TAG, activityInfo + ", windowIsTranslucent: " + windowIsTranslucent
                    + ", forceOpaqueProxy: " + forceOpaqueProxy);
        } catch (Throwable e) {
            Slog.e("ActivityStack", "Container operation failed", e);
            shadow.setComponent(new ComponentName(NexBoxCore.getHostPkg(), ProxyManifest.getProxyActivity(vpid)));
        } finally {
            if (typedArray != null) {
                typedArray.recycle();
            }
        }
        // Guest extras may contain its own Parcelable classes. Keep the opaque Bundle here;
        // class-extra sanitization happens in the originating guest before the core IPC.
        ProxyActivityRecord.saveStub(shadow, intent, target.mActivityInfo, target.mActivityRecord,
                target.mUserId, target.mUserGeneration, target.mActivityIdentity);
        return shadow;
    }

    private boolean shouldForceOpaqueProxy(ActivityInfo activityInfo) {
        if (activityInfo == null) {
            return false;
        }

        // Samsung Health's sign-in authenticator declares a translucent window, but
        // running it through TransparentProxyActivity leaves the proxy task top-resumed
        // while the real auth windows are focused. That stalls the sign-in flow.
        return "com.sec.android.app.shealth".equals(activityInfo.packageName)
                && "com.samsung.android.app.shealth.accounts.AuthenticatorActivity".equals(activityInfo.name);
    }

    private void finishAllActivity(int userId) {
        for (TaskRecord task : mTasks.values()) {
            for (ActivityRecord activity : task.activities) {
                if (activity.userId == userId) {
                    if (activity.finished) {
                        try {
                            activity.processRecord.bActivityThread.finishActivity(activity.token);
                        } catch (RemoteException ignored) {
                        }
                    }
                }
            }
        }
    }

    ActivityRecord newActivityRecord(Intent intent, ActivityInfo info, IBinder resultTo,
                                     int userId) {
        ActivityRecord targetRecord = ActivityRecord.create(intent, info, resultTo, userId);
        synchronized (mLaunchingActivities) {
            mLaunchingActivities.add(targetRecord);
            Message obtain = Message.obtain(mHandler, LAUNCH_TIME_OUT, targetRecord);
            mHandler.sendMessageDelayed(obtain, 2000);
        }
        return targetRecord;
    }

    private ActivityRecord findActivityRecordByComponentName(int userId, ComponentName
            componentName) {
        ActivityRecord record = null;
        for (TaskRecord next : mTasks.values()) {
            if (userId == next.userId) {
                for (ActivityRecord activity : next.activities) {
                    if (!activity.finished && activity.component.equals(componentName)) {
                        record = activity;
                        break;
                    }
                }
            }
        }
        return record;
    }

    private ActivityRecord findActivityRecordByToken(int userId, IBinder token) {
        ActivityRecord record = null;
        if (token != null) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId) {
                    for (ActivityRecord activity : next.activities) {
                        if (activity.token == token) {
                            record = activity;
                            break;
                        }
                    }
                }
            }
        }
        return record;
    }

    private TaskRecord findTaskRecordByTaskAffinityLocked(int userId, String taskAffinity) {
        synchronized (mTasks) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId && next.taskAffinity.equals(taskAffinity))
                    return next;
            }
            return null;
        }
    }

    private TaskRecord findTaskRecordByTokenLocked(int userId, IBinder token) {
        synchronized (mTasks) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId) {
                    for (ActivityRecord activity : next.activities) {
                        if (activity.token == token) {
                            return next;
                        }
                    }
                }
            }
            return null;
        }
    }

    public void onActivityCreated(ProcessRecord processRecord, int taskId, IBinder
            token, ActivityRecord record) {
        synchronized (mLaunchingActivities) {
            mLaunchingActivities.remove(record);
            mHandler.removeMessages(LAUNCH_TIME_OUT, record);
        }
        synchronized (mTasks) {
            synchronizeTasks();
            TaskRecord taskRecord = mTasks.get(taskId);
            if (taskRecord != null && (taskRecord.userId != record.userId
                    || taskRecord.userGeneration != processRecord.userGeneration)) {
                throw new SecurityException("Task belongs to another user generation");
            }
            if (taskRecord == null) {
                taskRecord = new TaskRecord(taskId, record.userId, ComponentUtils.getTaskAffinity(record.info));
                taskRecord.userGeneration = processRecord.userGeneration;
                taskRecord.activityIdentity = record.activityIdentity;
                taskRecord.packageName = record.info.packageName;
                taskRecord.proxyComponent = record.proxyComponent;
                taskRecord.rootIntent = record.intent;
                mTasks.put(taskId, taskRecord);
            }
            record.token = token;
            record.processRecord = processRecord;
            record.task = taskRecord;
            taskRecord.addTopActivity(record);
            Slog.d(TAG, "onActivityCreated : " + record.component.toString());
        }
    }

    public void onActivityResumed(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            Slog.d(TAG, "onActivityResumed : " + activityRecord.component.toString());
            activityRecord.task.removeActivity(activityRecord);
            activityRecord.task.addTopActivity(activityRecord);
        }
    }

    public void onActivityDestroyed(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            activityRecord.finished = true;
            Slog.d(TAG, "onActivityDestroyed : " + activityRecord.component.toString());
            activityRecord.task.removeActivity(activityRecord);
        }
    }

    public void onFinishActivity(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            activityRecord.finished = true;
            Slog.d(TAG, "onFinishActivity : " + activityRecord.component.toString());
        }
    }

    public String getCallingPackage(IBinder token, int userId) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecordByToken = findActivityRecordByToken(userId, token);
            if (activityRecordByToken != null) {
                ActivityRecord resultTo = findActivityRecordByToken(userId, activityRecordByToken.resultTo);
                if (resultTo != null) {
                    return resultTo.info.packageName;
                }
                // Privacy: if we can't resolve the "resultTo" link, fall back to the
                // current virtual activity package rather than leaking the host package.
                if (activityRecordByToken.info != null && activityRecordByToken.info.packageName != null) {
                    return activityRecordByToken.info.packageName;
                }
            }
            // Android returns null when there is no calling activity. Returning the
            // host package here only leaks host identity without helping the guest.
            return NexBoxCore.getAppPackageName();
        }
    }

    public ComponentName getCallingActivity(IBinder token, int userId) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecordByToken = findActivityRecordByToken(userId, token);
            if (activityRecordByToken != null) {
                ActivityRecord resultTo = findActivityRecordByToken(userId, activityRecordByToken.resultTo);
                if (resultTo != null) {
                    return resultTo.component;
                }
                // Privacy: fall back to the current virtual activity (best-effort).
                if (activityRecordByToken.component != null) {
                    return activityRecordByToken.component;
                }
            }
            String appPkg = NexBoxCore.getAppPackageName();
            if (appPkg != null) {
                return new ComponentName(appPkg, ProxyActivity.P0.class.getName());
            }
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void synchronizeTasks() {
        List<ActivityManager.RecentTaskInfo> recentTasks = mAms.getRecentTasks(100, 0);
        if (recentTasks == null) throw new IllegalStateException("System task list unavailable");
        Map<Integer, TaskRecord> newTacks = new LinkedHashMap<>();
        for (int i = recentTasks.size() - 1; i >= 0; i--) {
            ActivityManager.RecentTaskInfo next = recentTasks.get(i);
            TaskRecord taskRecord = mTasks.get(next.taskId);
            if (taskRecord == null) continue;
            newTacks.put(next.taskId, taskRecord);
        }
        mTasks.clear();
        mTasks.putAll(newTacks);
    }

    List<RecentTaskLabels.TaskOwner> snapshotTasksForRefresh(int userId, long generation) {
        synchronized (mTasks) {
            List<RecentTaskLabels.TaskOwner> result = new ArrayList<>();
            for (TaskRecord record : mTasks.values()) {
                if (record.userId == userId && record.userGeneration == generation) {
                    result.add(new RecentTaskLabels.TaskOwner(record));
                }
            }
            return result;
        }
    }

    void forgetRemovedTask(int taskId, long generation, String identity) {
        synchronized (mTasks) {
            TaskRecord current = mTasks.get(taskId);
            if (current != null && current.userGeneration == generation
                    && java.util.Objects.equals(current.activityIdentity, identity)) {
                mTasks.remove(taskId);
            }
        }
    }
}
