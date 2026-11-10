package com.nexbox.core.core.system.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import black.android.app.BRActivityClient;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.proxy.record.ProxyActivityRecord;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.TaskDescriptionCompat;

/** System recents are a separate cache; a saved group name does not prove that it was refreshed. */
final class RecentTaskLabels {
    private static final String TAG = "RecentTaskLabels";

    private RecentTaskLabels() {}

    static final class TaskOwner {
        final int taskId;
        final int userId;
        final long generation;
        final String identity;
        final String packageName;
        final ComponentName proxyComponent;
        final List<ActivityOwner> activities = new ArrayList<>();

        TaskOwner(TaskRecord record) {
            taskId = record.id;
            userId = record.userId;
            generation = record.userGeneration;
            identity = record.activityIdentity;
            packageName = record.packageName;
            proxyComponent = record.proxyComponent;
            for (ActivityRecord activity : record.activities) activities.add(new ActivityOwner(activity));
        }

        boolean matches(ActivityManager.RecentTaskInfo info) {
            Intent base = info.baseIntent;
            if (base != null) base.setExtrasClassLoader(RecentTaskLabels.class.getClassLoader());
            int hintedUser = base == null ? -1 : ProxyActivityRecord.readUserId(base);
            long hintedGeneration = base == null ? 0 : ProxyActivityRecord.readUserGeneration(base);
            return info.taskId == taskId && identity != null && proxyComponent != null
                    && base != null && proxyComponent.equals(base.getComponent())
                    && identity.equals(base.getIdentifier())
                    && (hintedUser < 0 || userId == hintedUser)
                    && (hintedGeneration <= 0 || generation == hintedGeneration);
        }

        List<ActivityOwner> liveActivities() {
            List<ActivityOwner> result = new ArrayList<>();
            for (int i = activities.size() - 1; i >= 0; i--) {
                ActivityOwner activity = activities.get(i);
                if (!activity.finished && activity.token != null && activity.isLive(userId, generation)) {
                    result.add(activity);
                }
            }
            return result;
        }
    }

    private static final class ActivityOwner {
        final IBinder token;
        final ProcessRecord process;
        final boolean finished;

        ActivityOwner(ActivityRecord record) {
            token = record.token;
            process = record.processRecord;
            finished = record.finished;
        }

        boolean isLive(int userId, long generation) {
            return process != null && process.userId == userId && process.userGeneration == generation
                    && process.bActivityThread != null && process.bActivityThread.asBinder().isBinderAlive()
                    && BProcessManagerService.get().findProcessByPid(process.pid) == process;
        }
    }

    private static final class TaskEntry {
        final ActivityManager.AppTask task;
        final ActivityManager.RecentTaskInfo info;

        TaskEntry(ActivityManager.AppTask task, ActivityManager.RecentTaskInfo info) {
            this.task = task;
            this.info = info;
            if (info.baseIntent != null) info.baseIntent.setExtrasClassLoader(RecentTaskLabels.class.getClassLoader());
        }
    }

    static void refresh(BUserInfo user, ActivityStack stack, Bundle result) {
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            requireCurrentUser(user);
            ActivityManager manager = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new IllegalStateException("Activity manager unavailable");
            List<TaskOwner> owners = stack == null ? Collections.emptyList()
                    : stack.snapshotTasksForRefresh(user.id, user.createTime);
            Map<Integer, TaskOwner> byId = new HashMap<>();
            for (TaskOwner owner : owners) byId.put(owner.taskId, owner);

            Map<Integer, String> labels = new HashMap<>();
            Set<Integer> updateRequested = new HashSet<>();
            Set<Integer> removeRequested = new HashSet<>();
            List<TaskEntry> stale = new ArrayList<>();
            for (TaskEntry entry : taskEntries(manager)) {
                TaskOwner owner = byId.get(entry.info.taskId);
                if (owner == null || !owner.matches(entry.info)) continue;
                ApplicationInfo application = BPackageManagerService.get().getApplicationInfo(owner.packageName, 0, user.id);
                if (application == null) continue;
                String label = TaskDescriptionCompat.label(application, user);
                labels.put(owner.taskId, label);
                if (label.equals(taskLabel(entry.info))) continue;
                List<ActivityOwner> activities = owner.liveActivities();
                if (!activities.isEmpty()) {
                    // Android combines non-overlay activities top-to-root; one overlay token may
                    // not supply the effective label. Try other owned tokens only until readback agrees.
                    for (ActivityOwner activity : activities) {
                        try {
                            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                                requireCurrentUser(user);
                                if (!activity.isLive(user.id, user.createTime)) continue;
                                ActivityManager.RecentTaskInfo current = entry.task.getTaskInfo();
                                if (current == null || !owner.matches(current)) break;
                                if (label.equals(taskLabel(current))) break;
                                Object controller = BRActivityClient.get().getActivityClientController();
                                Reflector.with(controller).method("setTaskDescription", IBinder.class,
                                        ActivityManager.TaskDescription.class).call(activity.token,
                                        TaskDescriptionCompat.withLabel(current.taskDescription, label));
                                updateRequested.add(owner.taskId);
                                ActivityManager.RecentTaskInfo readback = entry.task.getTaskInfo();
                                if (readback != null && owner.matches(readback) && label.equals(taskLabel(readback))) break;
                            }
                        } catch (Exception failure) {
                            Slog.w(TAG, "stage=liveRefreshFailed userId=" + user.id + " generation="
                                    + user.createTime + " taskId=" + owner.taskId, failure);
                        }
                    }
                }
                // An unsupported setter alone never requests a stop. A fresh, readable system
                // snapshot must still identify this exact task and show the uncorrected label.
                try {
                    ActivityManager.RecentTaskInfo readback = entry.task.getTaskInfo();
                    if (isStale(owner, label, readback)) stale.add(new TaskEntry(entry.task, readback));
                } catch (IllegalArgumentException disappeared) {
                    // The final task-list read confirms that the old task disappeared.
                } catch (RuntimeException failure) {
                    Slog.w(TAG, "stage=staleReadbackUnavailable userId=" + user.id + " generation="
                            + user.createTime + " taskId=" + owner.taskId, failure);
                }
            }

            // No stack lock is taken in this phase. Keep process restart and removal atomic.
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                requireCurrentUser(user);
                List<TaskEntry> confirmedStale = new ArrayList<>();
                boolean needsStop = false;
                for (TaskEntry entry : stale) {
                    TaskOwner owner = byId.get(entry.info.taskId);
                    try {
                        ActivityManager.RecentTaskInfo current = entry.task.getTaskInfo();
                        if (!isStale(owner, labels.get(owner.taskId), current)) continue;
                        confirmedStale.add(new TaskEntry(entry.task, current));
                        needsStop |= current.isRunning || !owner.liveActivities().isEmpty();
                    } catch (IllegalArgumentException disappeared) {
                        // A task removed or replaced before the stop is not a restart reason.
                    } catch (RuntimeException failure) {
                        Slog.w(TAG, "stage=staleRecheckUnavailable userId=" + user.id + " generation="
                                + user.createTime + " taskId=" + owner.taskId, failure);
                    }
                }
                boolean canRemove = true;
                if (!confirmedStale.isEmpty()) {
                    Slog.i(TAG, "stage=refreshFallback userId=" + user.id + " generation=" + user.createTime
                            + " staleTasks=" + confirmedStale.size() + " stopRequired=" + needsStop);
                }
                if (needsStop) {
                    result.putBoolean("stopRequested", true);
                    canRemove = BProcessManagerService.get().stopUserIfMatches(user.id, user.createTime);
                    result.putBoolean("stopConfirmed", canRemove);
                    if (!canRemove) result.putString("error", "process-stop-unconfirmed");
                }
                if (canRemove) {
                    for (TaskEntry entry : confirmedStale) {
                        TaskOwner owner = byId.get(entry.info.taskId);
                        try {
                            ActivityManager.RecentTaskInfo current = entry.task.getTaskInfo();
                            // An in-flight guest update may have repaired the label while it stopped.
                            if (isStale(owner, labels.get(owner.taskId), current)
                                    && (!current.isRunning || result.getBoolean("stopConfirmed"))) {
                                entry.task.finishAndRemoveTask();
                                removeRequested.add(owner.taskId);
                            }
                        } catch (RuntimeException failure) {
                            Slog.w(TAG, "stage=removeStaleTaskFailed userId=" + user.id + " generation="
                                    + user.createTime + " taskId=" + owner.taskId, failure);
                        }
                    }
                }
            }

            Map<Integer, TaskEntry> after = new HashMap<>();
            int updated = 0;
            int unchanged = 0;
            int remaining = 0;
            int unverified = 0;
            for (TaskEntry entry : taskEntries(manager)) {
                after.put(entry.info.taskId, entry);
                TaskOwner owner = byId.get(entry.info.taskId);
                if (owner == null || !owner.matches(entry.info)) {
                    if (mayBelongToUser(entry.info, user)) unverified++;
                    continue;
                }
                String expected = labels.get(owner.taskId);
                if (expected != null && expected.equals(taskLabel(entry.info))) {
                    if (updateRequested.contains(owner.taskId)) updated++;
                    else unchanged++;
                } else remaining++;
            }
            int removed = 0;
            for (Integer taskId : removeRequested) {
                TaskOwner owner = byId.get(taskId);
                TaskEntry current = after.get(taskId);
                if (current == null || !owner.matches(current.info)) {
                    removed++;
                    if (stack != null) stack.forgetRemovedTask(taskId, owner.generation, owner.identity);
                }
            }
            requireCurrentUser(user);
            boolean confirmed = remaining == 0 && unverified == 0
                    && (!result.getBoolean("stopRequested") || result.getBoolean("stopConfirmed"));
            result.putInt("updatedTasks", updated);
            result.putInt("unchangedTasks", unchanged);
            result.putInt("removedTasks", removed);
            result.putInt("remainingTasks", remaining);
            result.putInt("unverifiedTasks", unverified);
            result.putBoolean("refreshConfirmed", confirmed);
            result.putBoolean("requiresRelaunch", !confirmed || removed > 0 || result.getBoolean("stopRequested"));
            result.putString("refreshStatus", !confirmed ? "partial" : removed > 0 ? "removed"
                    : updated + unchanged > 0 ? "updated" : "no-tasks");
            if (confirmed) result.remove("error");
            else if (!result.containsKey("error")) result.putString("error",
                    unverified > 0 ? "task-owner-unverified" : "task-refresh-unconfirmed");
        } catch (Exception failure) {
            result.putBoolean("refreshConfirmed", false);
            result.putBoolean("requiresRelaunch", true);
            result.putString("refreshStatus", "failed");
            result.putInt("remainingTasks", -1);
            result.putInt("unverifiedTasks", -1);
            result.putString("error", "task-refresh-unavailable");
            Slog.w(TAG, "stage=refreshUnavailable userId=" + user.id + " generation=" + user.createTime, failure);
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    private static List<TaskEntry> taskEntries(ActivityManager manager) {
        List<ActivityManager.AppTask> tasks = manager.getAppTasks();
        if (tasks == null) throw new IllegalStateException("System task list unavailable");
        List<TaskEntry> entries = new ArrayList<>();
        for (ActivityManager.AppTask task : tasks) {
            try {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null) entries.add(new TaskEntry(task, info));
            } catch (IllegalArgumentException disappeared) {
                // AppTaskImpl reports a task removed after getAppTasks() with this exception.
            }
        }
        return entries;
    }

    private static String taskLabel(ActivityManager.RecentTaskInfo info) {
        return info.taskDescription == null ? null : info.taskDescription.getLabel();
    }

    private static boolean isStale(TaskOwner owner, String expected, ActivityManager.RecentTaskInfo info) {
        return owner != null && expected != null && info != null && info.taskDescription != null
                && owner.matches(info) && !expected.equals(info.taskDescription.getLabel());
    }

    private static void requireCurrentUser(BUserInfo expected) {
        BUserInfo current = BUserManagerService.get().getUserInfo(expected.id);
        if (current == null || current.createTime != expected.createTime
                || !Objects.equals(current.name, expected.name)) {
            throw new IllegalStateException("Task refresh user changed");
        }
    }

    private static boolean mayBelongToUser(ActivityManager.RecentTaskInfo info, BUserInfo user) {
        Intent base = info.baseIntent;
        if (base == null || !isHostProxy(base.getComponent())) return false;
        int hintedUser = ProxyActivityRecord.readUserId(base);
        long hintedGeneration = ProxyActivityRecord.readUserGeneration(base);
        // Incomplete historic records are explicitly unverified, not assigned today's generation.
        return (hintedUser < 0 || hintedUser == user.id)
                && (hintedGeneration <= 0 || hintedGeneration == user.createTime);
    }

    private static boolean isHostProxy(ComponentName component) {
        if (component == null || !NexBoxCore.getHostPkg().equals(component.getPackageName())) return false;
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
            if (Objects.equals(component.getClassName(), ProxyManifest.getProxyActivity(i))
                    || Objects.equals(component.getClassName(), ProxyManifest.TransparentProxyActivity(i))) return true;
        }
        return false;
    }
}
