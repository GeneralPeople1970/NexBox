package com.nexbox.core.core.system;

import android.app.ActivityManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.IBActivityThread;
import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.notification.BNotificationManagerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageCodeAvailability;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.core.system.xposed.BXposedManagerService;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.media.NexBoxMediaContract;
import com.nexbox.core.proxy.ProxyContentProvider;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.ApplicationThreadCompat;
import com.nexbox.core.utils.compat.BundleCompat;
import com.nexbox.core.utils.compat.FrameworkMemberAccess;
import com.nexbox.core.utils.provider.ProviderCall;


public class BProcessManagerService implements ISystemService {
    public static final String TAG = "BProcessManager";

    public static BProcessManagerService sBProcessManagerService = new BProcessManagerService();
    private final Map<Integer, Map<String, ProcessRecord>> mProcessMap = new HashMap<>();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final Object mProcessLock = new Object();
    private final GmsProcessRestartPolicy mGoogleRestarts = new GmsProcessRestartPolicy();
    private final Map<Integer, Map<String, PendingStart>> mStartingProcesses = new HashMap<>();
    private final Map<Integer, SlotLease> mSlotOwners = new HashMap<>();
    private static final long PROCESS_JOIN_TIMEOUT_MS = 10_000L;
    // Pending clients never enter this map. Identity readers see only generation-checked,
    // fully attached processes and need not wait for unrelated Provider connections.
    private final Map<Integer, ProcessRecord> mLiveIdentityProcesses = new ConcurrentHashMap<>();

    private static final class SlotLease {
        final ProcessRecord record;
        boolean connecting = true;
        boolean retired;
        boolean failureRecorded;
        long completedAtNanos;

        SlotLease(ProcessRecord record) { this.record = record; }
    }

    private static final class PendingStart {
        final ProcessRecord record;
        final SlotLease lease;
        boolean cancelled;
        ProcessRecord result;
        RuntimeException failure;

        PendingStart(ProcessRecord record) {
            this.record = record;
            lease = new SlotLease(record);
        }
    }

    private static final class RunningSnapshot {
        final long observedAtNanos = SystemClock.elapsedRealtimeNanos();
        final List<ActivityManager.RunningAppProcessInfo> processes = runningHostProcesses();
    }

    private static final class ClientConnection {
        final IBActivityThread activityThread;
        final IInterface appThread;
        final int pid;

        ClientConnection(IBActivityThread activityThread, IInterface appThread, int pid) {
            this.activityThread = activityThread;
            this.appThread = appThread;
            this.pid = pid;
        }

        boolean alive() {
            return activityThread != null && appThread != null && pid > 0
                    && activityThread.asBinder().isBinderAlive() && appThread.asBinder() != null
                    && appThread.asBinder().isBinderAlive();
        }
    }

    public static BProcessManagerService get() {
        return sBProcessManagerService;
    }

    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid, int callingPid) {
        return startProcessForGeneration(packageName, processName, userId, bpid, callingPid, 0L);
    }

    /** Internal deferred launches must not resolve a recycled numeric user id as a new owner. */
    public ProcessRecord startProcessLocked(String packageName, String processName, int userId, int bpid,
                                            int callingPid, long expectedGeneration) {
        if (expectedGeneration <= 0L) throw new IllegalArgumentException("A captured user generation is required");
        return startProcessForGeneration(packageName, processName, userId, bpid, callingPid, expectedGeneration);
    }

    private ProcessRecord startProcessForGeneration(String packageName, String processName, int userId, int bpid,
                                                    int callingPid, long expectedGeneration) {
        // AMS inspection and guest IPC run without the package/user or process registry locks.
        RunningSnapshot running = null;
        PendingStart pending = null;
        boolean owner = false;
        while (pending == null) {
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BUserInfo user = BUserManagerService.get().getUserInfo(userId);
                if (user == null || !matchesStartGeneration(user.createTime, expectedGeneration)) {
                    Slog.w(TAG, "stage=startProcessRejected reason=user_generation_unavailable userId=" + userId);
                    return null;
                }
                ApplicationInfo info = BPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
                if (info == null) {
                    Slog.w(TAG, "stage=startProcessRejected reason=package_unavailable package=" + packageName + " userId=" + userId);
                    return null;
                }
                String sourceFailure = PackageCodeAvailability.unavailableReason(info);
                if (sourceFailure != null) {
                    Slog.w(TAG, "stage=startProcessRejected reason=" + sourceFailure + " package=" + packageName
                            + " userId=" + userId + " generation=" + user.createTime);
                    return null;
                }
                int appId = BPackageManagerService.get().getAppId(packageName);
                int buid = BUserHandle.getUid(userId, appId);
                synchronized (mProcessLock) {
                    if (running != null) pruneRetiredSlotsLocked(running);
                    Map<String, ProcessRecord> processes = mProcessMap.get(buid);
                    ProcessRecord current = processes == null ? null : processes.get(processName);
                    if (current != null) {
                        if (current.userGeneration != user.createTime || (bpid >= 0 && bpid != current.bpid)) {
                            Slog.w(TAG, "stage=startProcessRejected reason=stale_process_identity userId=" + userId);
                            return null;
                        }
                        if (current.bActivityThread != null && current.appThread != null
                                && current.bActivityThread.asBinder().isBinderAlive()) {
                            current.hostedPackages.add(packageName);
                            return current;
                        }
                        retireProcessLocked(current, true, false);
                    }
                    Map<String, PendingStart> starts = mStartingProcesses.get(buid);
                    pending = starts == null ? null : starts.get(processName);
                    if (pending != null) {
                        if (pending.cancelled || pending.record.userGeneration != user.createTime
                                || (bpid >= 0 && pending.record.bpid != bpid)) return null;
                    } else if (running != null) {
                        GmsProcessRestartPolicy.Key restartKey = new GmsProcessRestartPolicy.Key(
                                packageName, processName, userId, user.createTime);
                        boolean google = GmsCompat.isCorePackage(packageName);
                        if (google && rejectGoogleRestart(restartKey)) return null;
                        if (bpid >= 0) {
                            if (callingPid <= 0 || bpid >= ProxyManifest.FREE_COUNT || mSlotOwners.containsKey(bpid)
                                    || !snapshotHasProcess(running, bpid, callingPid)) {
                                Slog.w(TAG, "stage=startProcessRejected reason=unverified_recovery_slot userId=" + userId);
                                return null;
                            }
                        } else {
                            bpid = getUsingBPidL(running);
                        }
                        if (bpid < 0) throw new IllegalStateException("No processes available");
                        ProcessRecord app = new ProcessRecord(info, processName, user.createTime);
                        app.uid = Process.myUid();
                        app.bpid = bpid;
                        app.buid = appId;
                        app.callingBUid = getBUidByPidOrPackageName(callingPid, packageName);
                        app.userId = userId;
                        pending = new PendingStart(app);
                        if (starts == null) {
                            starts = new HashMap<>();
                            mStartingProcesses.put(buid, starts);
                        }
                        starts.put(processName, pending);
                        mSlotOwners.put(bpid, pending.lease);
                        if (google) mGoogleRestarts.onStart(restartKey, SystemClock.elapsedRealtime());
                        owner = true;
                    }
                }
            }
            if (pending == null) running = new RunningSnapshot();
        }
        if (!owner) return awaitStart(pending, packageName);
        return initializeReservedProcess(pending);
    }

    static boolean matchesStartGeneration(long actual, long expected) {
        return actual > 0L && (expected == 0L || actual == expected);
    }

    private ProcessRecord awaitStart(PendingStart pending, String packageName) {
        if (!pending.record.initLock.block(PROCESS_JOIN_TIMEOUT_MS)) {
            Slog.w(TAG, "stage=startProcessDeferred reason=connection_pending process=" + pending.record.processName);
            return null; // This waiter does not cancel a connection shared with other callers.
        }
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            BUserInfo user = BUserManagerService.get().getUserInfo(pending.record.userId);
            ApplicationInfo installed = user == null || user.createTime != pending.record.userGeneration
                    ? null : BPackageManagerService.get().getApplicationInfo(packageName, 0, user.id);
            synchronized (mProcessLock) {
                if (pending.failure != null) throw pending.failure;
                ProcessRecord result = pending.result;
                if (pending.cancelled || result == null || installed == null
                        || BUserHandle.getAppId(installed.uid) != BUserHandle.getAppId(result.buid)
                        || !isCurrentProcessLocked(result) || result.bActivityThread == null
                        || !result.bActivityThread.asBinder().isBinderAlive()) return null;
                result.hostedPackages.add(packageName);
                return result;
            }
        }
    }

    private ProcessRecord initializeReservedProcess(PendingStart pending) {
        ProcessRecord record = pending.record;
        ClientConnection client = null;
        RuntimeException failure = null;
        try {
            client = connectReservedProcess(record);
        } catch (RuntimeException problem) {
            failure = problem;
        } catch (Error problem) {
            RuntimeException attachmentFailure = new IllegalStateException("Guest attachment failed", problem);
            try {
                finishReservedProcess(pending, null, attachmentFailure);
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != attachmentFailure) problem.addSuppressed(cleanupFailure);
            }
            throw problem;
        }
        return finishReservedProcess(pending, client, failure);
    }

    private ProcessRecord finishReservedProcess(PendingStart pending, ClientConnection client, RuntimeException failure) {
        ProcessRecord record = pending.record;
        boolean google = GmsCompat.isCorePackage(record.getPackageName());
        try {
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                BUserInfo user = BUserManagerService.get().getUserInfo(record.userId);
                ApplicationInfo installed = user == null || user.createTime != record.userGeneration
                        ? null : BPackageManagerService.get().getApplicationInfo(record.getPackageName(), 0, record.userId);
                synchronized (mProcessLock) {
                    boolean ownsSlot = mSlotOwners.get(record.bpid) == pending.lease;
                    boolean publish = failure == null && client != null && client.alive()
                            && !pending.cancelled && ownsSlot && isCurrentStartLocked(pending)
                            && samePackageCode(record.info, installed);
                    if (client != null && ownsSlot) {
                        record.pid = client.pid;
                        record.bActivityThread = client.activityThread;
                        record.appThread = client.appThread;
                    }
                    if (publish) {
                        createProc(record);
                        synchronized (mProcessMap) {
                            mProcessMap.computeIfAbsent(processKey(record), key -> new HashMap<>())
                                    .put(record.processName, record);
                        }
                        mPidsSelfLocked.add(record);
                        mLiveIdentityProcesses.put(record.pid, record);
                        pending.result = record;
                        Slog.iLazy(TAG, () -> "stage=processReady package=" + record.getPackageName()
                                + " process=" + record.processName + " userId=" + record.userId
                                + " pid=" + record.pid + " slot=" + record.bpid);
                    } else {
                        pending.cancelled = true;
                        pending.lease.retired = true;
                        if (!google) pending.failure = failure;
                        if (ownsSlot && record.bActivityThread != null
                                && record.bActivityThread.asBinder().isBinderAlive()) record.kill();
                        if (!pending.lease.failureRecorded && google && isCurrentStartLocked(pending)) {
                            pending.lease.failureRecorded = true;
                            mGoogleRestarts.onFailure(new GmsProcessRestartPolicy.Key(record.getPackageName(),
                                    record.processName, record.userId, record.userGeneration), SystemClock.elapsedRealtime());
                        }
                        if (failure != null) Slog.w(TAG, "stage=processInitFailed process=" + record.processName, failure);
                    }
                }
            }
        } catch (RuntimeException | Error problem) {
            synchronized (mProcessLock) {
                pending.failure = problem instanceof RuntimeException ? (RuntimeException) problem
                        : new IllegalStateException("Guest publication failed", problem);
                pending.cancelled = true;
                pending.result = null;
                // An allocation or local publication failure must not leave an apparently
                // reusable process or an unretired slot after the completion signal opens.
                if (isCurrentProcessLocked(record)) retireProcessLocked(record, false, true);
                pending.lease.retired = true;
                if (mSlotOwners.get(record.bpid) == pending.lease && record.bActivityThread != null
                        && record.bActivityThread.asBinder().isBinderAlive()) {
                    record.kill();
                }
            }
            if (problem instanceof Error) throw (Error) problem;
        } finally {
            synchronized (mProcessLock) {
                removeIfCurrent(mStartingProcesses, processKey(record), record.processName, pending);
                pending.lease.connecting = false;
                pending.lease.completedAtNanos = SystemClock.elapsedRealtimeNanos();
                record.initLock.open();
                mProcessLock.notifyAll();
            }
        }
        if (pending.failure != null) throw pending.failure;
        return pending.result;
    }

    private boolean isCurrentStartLocked(PendingStart start) {
        Map<String, PendingStart> starts = mStartingProcesses.get(processKey(start.record));
        return starts != null && starts.get(start.record.processName) == start;
    }

    private boolean isCurrentProcessLocked(ProcessRecord record) {
        Map<String, ProcessRecord> processes = mProcessMap.get(processKey(record));
        return processes != null && processes.get(record.processName) == record;
    }

    private static boolean samePackageCode(ApplicationInfo expected, ApplicationInfo current) {
        return current != null && expected.uid == current.uid
                && Objects.equals(expected.packageName, current.packageName)
                && Objects.equals(expected.sourceDir, current.sourceDir)
                && Objects.equals(expected.nativeLibraryDir, current.nativeLibraryDir)
                && Arrays.equals(expected.splitSourceDirs, current.splitSourceDirs);
    }

    private boolean rejectGoogleRestart(GmsProcessRestartPolicy.Key key) {
        long delay = mGoogleRestarts.remainingDelay(key, SystemClock.elapsedRealtime());
        if (delay == 0) return false;
        if (mGoogleRestarts.shouldLogRejection(key)) Slog.w(TAG, "stage=googleRestartDeferred package="
                + key.packageName + " process=" + key.processName + " userId=" + key.userId
                + " retryAfterMs=" + delay + (delay < 0 ? " circuit=open reset=explicit_package_stop" : ""));
        return true;
    }

    /** A stale/dead client must not clear a replacement or initiate a retry from Binder IPC. */
    public void onGoogleBinderDied(ProcessRecord record) {
        if (record == null || !GmsCompat.isCorePackage(record.getPackageName())) return;
        synchronized (mProcessLock) {
            if (findProcessRecord(record.getPackageName(), record.processName, record.userId) == record) {
                onProcessDie(record);
            }
        }
    }

    private int getUsingBPidL(RunningSnapshot snapshot) {
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo process : snapshot.processes) {
            if (process == null || process.uid != Process.myUid()) continue;
            int slot = parseBPid(process.processName);
            if (slot >= 0) usingPs.add(slot);
        }
        for (int slot = 0; slot < ProxyManifest.FREE_COUNT; slot++) {
            if (!usingPs.contains(slot) && !mSlotOwners.containsKey(slot)) return slot;
        }
        return -1;
    }

    private boolean snapshotHasProcess(RunningSnapshot snapshot, int slot, int pid) {
        String name = ProxyManifest.getProcessName(slot);
        for (ActivityManager.RunningAppProcessInfo process : snapshot.processes) {
            if (process != null && process.uid == Process.myUid() && name.equals(process.processName)
                    && (pid <= 0 || process.pid == pid)) return true;
        }
        return false;
    }

    private void pruneRetiredSlotsLocked(RunningSnapshot snapshot) {
        mSlotOwners.entrySet().removeIf(entry -> {
            SlotLease lease = entry.getValue();
            return canReleaseSlot(lease.connecting, lease.retired, lease.completedAtNanos,
                    snapshot.observedAtNanos, snapshotHasProcess(snapshot, entry.getKey(), -1));
        });
    }

    static boolean canReleaseSlot(boolean connecting, boolean retired, long completedAt,
                                  long observedAt, boolean occupied) {
        return !connecting && retired && completedAt > 0 && observedAt >= completedAt && !occupied;
    }

    public void restartAppProcess(String packageName, String processName, int userId) {
        int callingPid = Binder.getCallingPid();
        if (findProcessByPid(callingPid) != null) return;
        String stubProcessName = getProcessName(NexBoxCore.getContext(), callingPid);
        int bpid = parseBPid(stubProcessName);
        if (bpid < 0) throw new SecurityException("Process recovery requires an exact guest slot");
        startProcessLocked(packageName, processName, userId, bpid, callingPid);
    }

    private int parseBPid(String stubProcessName) {
        return parseGuestSlot(NexBoxCore.getHostPkg(), stubProcessName);
    }

    static int parseGuestSlot(String hostPackage, String processName) {
        if (hostPackage == null || hostPackage.isEmpty() || processName == null) return -1;
        String prefix = hostPackage + ":p";
        if (processName.startsWith(prefix)) {
            try {
                int slot = Integer.parseInt(processName.substring(prefix.length()));
                if (slot >= 0 && slot < ProxyManifest.FREE_COUNT && processName.equals(prefix + slot)) return slot;
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    static boolean isGuestRecoveryCandidate(String hostPackage, int hostUid, int serverPid,
                                             int pid, int uid, String processName) {
        return hostUid >= 0 && pid > 0 && pid != serverPid && uid == hostUid
                && parseGuestSlot(hostPackage, processName) >= 0;
    }

    private ClientConnection connectReservedProcess(ProcessRecord record) {
        AppConfig config = record.getClientConfig();
        Bundle bundle = new Bundle();
        bundle.putParcelable(AppConfig.KEY, config);
        Bundle initialized = ProviderCall.callSafely(record.getProviderAuthority(), ProxyContentProvider.METHOD_INIT_PROCESS, null, bundle);
        IBinder binder = initialized == null ? null : BundleCompat.getBinder(initialized, ProxyContentProvider.KEY_CLIENT);
        int reportedPid = initialized == null ? -1 : initialized.getInt(ProxyContentProvider.KEY_PID, -1);
        if (binder == null || !binder.isBinderAlive()) return null;
        IBActivityThread activityThread = IBActivityThread.Stub.asInterface(binder);
        if (activityThread == null) return null;
        RunningSnapshot running = new RunningSnapshot();
        if (reportedPid <= 0 || !snapshotHasProcess(running, record.bpid, reportedPid)) {
            throw new IllegalStateException("Guest PID does not match the reserved host process");
        }
        synchronized (mProcessLock) {
            SlotLease lease = mSlotOwners.get(record.bpid);
            if (lease == null || lease.record != record) return null;
            // This is cleanup metadata only: pending clients are absent from both the
            // public process map and the live-identity map until final publication.
            record.pid = reportedPid;
            record.bActivityThread = activityThread;
            if (lease.retired) {
                if (binder.isBinderAlive()) record.kill();
                return null;
            }
        }
        try {
            binder.linkToDeath(new IBinder.DeathRecipient() {
                @Override public void binderDied() {
                    binder.unlinkToDeath(this, 0);
                    onProcessDie(record);
                }
            }, 0);
            IInterface appThread = ApplicationThreadCompat.asInterface(activityThread.getActivityThread());
            return new ClientConnection(activityThread, appThread, reportedPid);
        } catch (RemoteException failure) {
            throw new IllegalStateException("Guest client died during attachment", failure);
        }
    }

    public void onProcessDie(ProcessRecord record) {
        if (record == null) return;
        synchronized (mProcessLock) {
            retireProcessLocked(record, true, false);
        }
    }

    /** Registry retirement is by exact record/slot lease, never just processName or pid. */
    private void retireProcessLocked(ProcessRecord record, boolean reportFailure, boolean kill) {
        SlotLease lease = mSlotOwners.get(record.bpid);
        boolean ownsSlot = lease != null && lease.record == record;
        boolean current = isCurrentProcessLocked(record);
        Map<String, PendingStart> starts = mStartingProcesses.get(processKey(record));
        PendingStart pending = starts == null ? null : starts.get(record.processName);
        boolean starting = pending != null && pending.record == record;
        if (!current && !starting && (!ownsSlot || lease.retired)) return;
        mLiveIdentityProcesses.remove(record.pid, record);
        if (starting) {
            pending.cancelled = true;
            pending.result = null;
            removeIfCurrent(mStartingProcesses, processKey(record), record.processName, pending);
        }
        if (current) {
            synchronized (mProcessMap) {
                removeIfCurrent(mProcessMap, processKey(record), record.processName, record);
            }
            mPidsSelfLocked.remove(record);
        }
        if (ownsSlot) {
            lease.retired = true;
            if (!lease.connecting) lease.completedAtNanos = SystemClock.elapsedRealtimeNanos();
        }
        if (reportFailure && (current || starting) && GmsCompat.isCorePackage(record.getPackageName())
                && (lease == null || !lease.failureRecorded)) {
            if (lease != null) lease.failureRecorded = true;
            mGoogleRestarts.onFailure(new GmsProcessRestartPolicy.Key(record.getPackageName(),
                    record.processName, record.userId, record.userGeneration), SystemClock.elapsedRealtime());
        }
        if (kill && record.bActivityThread != null && record.bActivityThread.asBinder().isBinderAlive()) record.kill();
        if (current && ownsSlot) {
            removeProc(record);
            BXposedManagerService.get().onProcessDied(record.pid);
            if (reportFailure) BNotificationManagerService.get().deletePackageNotification(
                    record.getPackageName(), record.userId, record.userGeneration);
        }
        record.initLock.open();
        mProcessLock.notifyAll();
    }

    private static int processKey(ProcessRecord record) {
        return BUserHandle.getUid(record.userId, BUserHandle.getAppId(record.buid));
    }

    static <T> boolean removeIfCurrent(Map<Integer, Map<String, T>> records, int uid, String processName, T expected) {
        Map<String, T> processes = records.get(uid);
        if (processes == null || processes.get(processName) != expected) return false;
        processes.remove(processName);
        if (processes.isEmpty()) records.remove(uid);
        return true;
    }

    public ProcessRecord findProcessRecord(String packageName, String processName, int userId) {
        int appId = BPackageManagerService.get().getAppId(packageName);
        synchronized (mProcessLock) {
            int buid = BUserHandle.getUid(userId, appId);
            Map<String, ProcessRecord> processRecordMap = mProcessMap.get(buid);
            if (processRecordMap == null)
                return null;
            return processRecordMap.get(processName);
        }
    }

    public void killAllByPackageName(String packageName) {
        synchronized (mProcessLock) {
            mGoogleRestarts.clearPackage(packageName, -1);
            int appId = BPackageManagerService.get().getAppId(packageName);
            for (SlotLease lease : new ArrayList<>(mSlotOwners.values())) {
                if (BUserHandle.getAppId(lease.record.buid) == appId) {
                    retireProcessLocked(lease.record, false, true);
                }
            }
        }
    }

    public void killUser(int userId) {
        synchronized (mProcessLock) {
            mGoogleRestarts.clearUser(userId);
            for (SlotLease lease : new ArrayList<>(mSlotOwners.values())) {
                if (lease.record.userId == userId) retireProcessLocked(lease.record, false, true);
            }
        }
    }

    public void killPackageAsUser(String packageName, int userId) {
        synchronized (mProcessLock) {
            mGoogleRestarts.clearPackage(packageName, userId);
            int appId = BPackageManagerService.get().getAppId(packageName);
            for (SlotLease lease : new ArrayList<>(mSlotOwners.values())) {
                ProcessRecord record = lease.record;
                if (record.userId == userId && BUserHandle.getAppId(record.buid) == appId) {
                    retireProcessLocked(record, false, true);
                }
            }
        }
    }

    /** A destructive GMS reset needs confirmed exits, not only successful kill requests. */
    public boolean stopPackagesForGmsReset(List<String> packageNames, int userId, long userGeneration) {
        return stopPackagesIfMatches(packageNames, userId, userGeneration);
    }

    /** Conditional media operations never stop guests; callers hold lifecycle until their file work ends. */
    public void requireMediaQuiescence() {
        if (!Thread.holdsLock(BPackageManagerService.get().getLifecycleLock())) {
            throw new IllegalStateException("Media quiescence requires lifecycle lock");
        }
        RunningSnapshot running;
        try { running = new RunningSnapshot(); }
        catch (RuntimeException unavailable) { throw new com.nexbox.core.utils.MutationReceipt.Rejected("atomicity_unavailable"); }
        synchronized (mProcessLock) {
            pruneRetiredSlotsLocked(running);
            if (!mSlotOwners.isEmpty() || !mStartingProcesses.isEmpty() || !mPidsSelfLocked.isEmpty()) {
                throw new com.nexbox.core.utils.MutationReceipt.Rejected("resource_busy");
            }
            for (ActivityManager.RunningAppProcessInfo process : running.processes) {
                if (process != null && isGuestRecoveryCandidate(NexBoxCore.getHostPkg(), Process.myUid(),
                        Process.myPid(), process.pid, process.uid, process.processName)) {
                    throw new com.nexbox.core.utils.MutationReceipt.Rejected("resource_busy");
                }
            }
            // Native children need not have an AMS process record. Refuse unknown same-UID processes.
            Set<Integer> known = new HashSet<>();
            for (ActivityManager.RunningAppProcessInfo process : running.processes) {
                if (process != null && process.uid == Process.myUid()) known.add(process.pid);
            }
            File[] proc = new File("/proc").listFiles();
            if (proc == null) throw new com.nexbox.core.utils.MutationReceipt.Rejected("atomicity_unavailable");
            for (File directory : proc) {
                if (!directory.getName().matches("[0-9]+")) continue;
                try {
                    if (android.system.Os.stat(directory.getPath()).st_uid == Process.myUid()
                            && !known.contains(Integer.parseInt(directory.getName()))) {
                        throw new com.nexbox.core.utils.MutationReceipt.Rejected("resource_busy");
                    }
                } catch (android.system.ErrnoException failure) {
                    if (failure.errno != android.system.OsConstants.ENOENT) {
                        throw new com.nexbox.core.utils.MutationReceipt.Rejected("atomicity_unavailable");
                    }
                }
            }
        }
    }

    /** Used only when an owned stale task cannot be refreshed without restarting its space. */
    public boolean stopUserIfMatches(int userId, long userGeneration) {
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null || userGeneration <= 0 || user.createTime != userGeneration) return false;
            Set<String> packages = new HashSet<>();
            synchronized (mProcessLock) {
                for (SlotLease lease : mSlotOwners.values()) {
                    ProcessRecord process = lease.record;
                    if (process.userId == userId && process.userGeneration == userGeneration) {
                        packages.addAll(process.hostedPackages);
                    }
                }
            }
            return stopPackagesIfMatches(new ArrayList<>(packages), userId, userGeneration);
        }
    }

    /** Preserve process records until death cleanup confirms that the captured app processes exited. */
    public boolean stopPackagesIfMatches(List<String> packageNames, int userId, long userGeneration) {
        RunningSnapshot running = new RunningSnapshot();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null || userGeneration <= 0 || user.createTime != userGeneration) return false;
            synchronized (mProcessLock) {
                pruneRetiredSlotsLocked(running);
                Set<String> requestedPackages = new HashSet<>(packageNames);
                for (SlotLease lease : mSlotOwners.values()) {
                    ProcessRecord record = lease.record;
                    if (record.userId == userId && record.userGeneration == userGeneration
                            && requestedPackages.contains(record.getPackageName())
                            && (lease.connecting || lease.retired)) {
                        Slog.w(TAG, "stage=packageStopRejected reason=connection_or_exit_unconfirmed userId=" + userId);
                        return false;
                    }
                }
                List<ProcessRecord> stopping = new ArrayList<>();
                for (String packageName : packageNames) {
                    mGoogleRestarts.clearPackage(packageName, userId);
                    for (ProcessRecord record : getPackageProcessAsUser(packageName, userId)) {
                        if (!record.hostedPackages.contains(packageName)) continue;
                        // Never terminate an out-of-scope app merely because it shares a UID/process.
                        if (!requestedPackages.containsAll(record.hostedPackages)) {
                            Slog.w(TAG, "stage=packageStopRejected reason=shared_process_outside_scope userId=" + userId);
                            return false;
                        }
                        if (record.userId != userId || record.userGeneration != userGeneration
                                || record.pid <= 0 || record.pid == Process.myPid()
                                || record.bActivityThread == null) {
                            Slog.w(TAG, "stage=packageStopRejected reason=unverified_process userId=" + userId);
                            return false;
                        }
                        if (!stopping.contains(record)) stopping.add(record);
                    }
                }
                try {
                    for (ProcessRecord record : stopping) {
                        if (record.bActivityThread.asBinder().isBinderAlive()) Process.killProcess(record.pid);
                    }
                } catch (RuntimeException failure) {
                    Slog.w(TAG, "stage=packageStopRejected reason=kill_failed userId=" + userId, failure);
                    return false;
                }
                // Keep process identities registered until linkToDeath retires them. A
                // failed/timed-out stop must remain visible to a subsequent reset attempt.
                long deadline = SystemClock.elapsedRealtime() + 3000L;
                while (true) {
                    boolean pending = false;
                    for (ProcessRecord record : stopping) {
                        if (record.bActivityThread.asBinder().isBinderAlive()
                                || mPidsSelfLocked.contains(record)) {
                            pending = true;
                            break;
                        }
                    }
                    if (!pending) {
                        for (String packageName : packageNames) mGoogleRestarts.clearPackage(packageName, userId);
                        return true;
                    }
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) {
                        Slog.w(TAG, "stage=packageStopRejected reason=exit_unconfirmed userId=" + userId
                                + " generation=" + userGeneration);
                        return false;
                    }
                    try {
                        // Releases mProcessLock so the existing death cleanup can finish.
                        mProcessLock.wait(Math.min(remaining, 50L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
    }

    public List<ProcessRecord> getPackageProcessAsUser(String packageName, int userId) {
        int buid = BUserHandle.getUid(userId, BPackageManagerService.get().getAppId(packageName));
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> process = mProcessMap.get(buid);
            if (process == null)
                return new ArrayList<>();
            return new ArrayList<>(process.values());
        }
    }

    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcessesAsUser(
            String packageName, int userId, long userGeneration) {
        ProcessQuery query = captureProcessQuery(packageName, userId, userGeneration);
        BPackageManagerService packages = BPackageManagerService.get();
        // System IPC must stay outside the package/user and process registry locks.
        Map<Integer, ActivityManager.RunningAppProcessInfo> runningByPid = new HashMap<>();
        for (ActivityManager.RunningAppProcessInfo process : readRunningHostProcesses()) {
            if (process != null) runningByPid.put(process.pid, process);
        }
        List<ActivityManager.RunningAppProcessInfo> result = new ArrayList<>();
        synchronized (packages.getLifecycleLock()) {
            int hostUid = NexBoxCore.getHostUid();
            synchronized (mProcessLock) {
                requireProcessQueryCaller(packageName, userId, userGeneration, query.caller);
                for (ProcessRecord record : query.processes) {
                    if (record.userId != userId || record.userGeneration != userGeneration
                            || !isCurrentProcessLocked(record) || !isLiveIdentityProcess(record, hostUid)) continue;
                    ActivityManager.RunningAppProcessInfo running = runningByPid.get(record.pid);
                    if (running == null || running.uid != record.uid
                            || !ProxyManifest.getProcessName(record.bpid).equals(running.processName)) continue;
                    ActivityManager.RunningAppProcessInfo info = copyRunningAppProcessInfo(running);
                    info.processName = record.processName;
                    // Match AppConfig/guest getuid; record.uid is the host transport UID.
                    info.uid = record.buid;
                    info.pkgList = record.hostedPackages.toArray(new String[0]);
                    Arrays.sort(info.pkgList);
                    // Host proxy bindings and dependencies do not describe guest components.
                    info.importanceReasonCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
                    info.importanceReasonPid = 0;
                    info.importanceReasonComponent = null;
                    result.add(info);
                }
            }
        }
        return result;
    }

    public List<ActivityManager.RunningServiceInfo> getRunningServicesAsUser(
            String packageName, int userId, long userGeneration, List<ServiceInfo> services) {
        ProcessQuery query = captureProcessQuery(packageName, userId, userGeneration);
        Map<ComponentName, ActivityManager.RunningServiceInfo> runningByComponent = new HashMap<>();
        for (ActivityManager.RunningServiceInfo service : readRunningHostServices()) {
            if (service != null) runningByComponent.put(service.service, service);
        }
        List<ActivityManager.RunningServiceInfo> result = new ArrayList<>();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            int hostUid = NexBoxCore.getHostUid();
            synchronized (mProcessLock) {
                requireProcessQueryCaller(packageName, userId, userGeneration, query.caller);
                for (ProcessRecord record : query.processes) {
                    if (record.userId != userId || record.userGeneration != userGeneration
                            || !isCurrentProcessLocked(record) || !isLiveIdentityProcess(record, hostUid)) continue;
                    ComponentName proxy = new ComponentName(NexBoxCore.getHostPkg(), ProxyManifest.getProxyService(record.bpid));
                    ActivityManager.RunningServiceInfo running = runningByComponent.get(proxy);
                    if (running == null || running.pid != record.pid || running.uid != record.uid
                            || !ProxyManifest.getProcessName(record.bpid).equals(running.process)) continue;
                    for (ServiceInfo service : services) {
                        if (!record.processName.equals(service.processName)
                                || !record.hostedPackages.contains(service.packageName)) continue;
                        Parcel parcel = Parcel.obtain();
                        try {
                            running.writeToParcel(parcel, 0);
                            parcel.setDataPosition(0);
                            ActivityManager.RunningServiceInfo info = ActivityManager.RunningServiceInfo.CREATOR.createFromParcel(parcel);
                            info.uid = record.buid;
                            info.process = record.processName;
                            info.service = new ComponentName(service.packageName, service.name);
                            info.clientPackage = null;
                            info.clientLabel = 0;
                            result.add(info);
                        } finally {
                            parcel.recycle();
                        }
                    }
                }
            }
        }
        return result;
    }

    private static final class ProcessQuery {
        final ProcessRecord caller;
        final List<ProcessRecord> processes;

        ProcessQuery(ProcessRecord caller, List<ProcessRecord> processes) {
            this.caller = caller;
            this.processes = processes;
        }
    }

    private ProcessQuery captureProcessQuery(String packageName, int userId, long userGeneration) {
        ProcessRecord caller = mLiveIdentityProcesses.get(Binder.getCallingPid());
        if (caller == null) XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (mProcessLock) {
                int appId = requireProcessQueryCaller(packageName, userId, userGeneration, caller);
                Map<String, ProcessRecord> processes = appId < 0 ? null
                        : mProcessMap.get(BUserHandle.getUid(userId, appId));
                return new ProcessQuery(caller, processes == null ? Collections.emptyList() : new ArrayList<>(processes.values()));
            }
        }
    }

    // Caller holds the lifecycle lock followed by mProcessLock, before and after system IPC.
    private int requireProcessQueryCaller(String packageName, int userId, long userGeneration, ProcessRecord caller) {
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (user == null || userGeneration <= 0L || user.createTime != userGeneration) {
            throw new SecurityException("Process query user lifetime expired");
        }
        int appId = BPackageManagerService.get().getAppId(packageName);
        if (caller != null && (Binder.getCallingUid() != NexBoxCore.getHostUid()
                || mLiveIdentityProcesses.get(Binder.getCallingPid()) != caller
                || !isLiveIdentityProcess(caller, NexBoxCore.getHostUid())
                || caller.userId != userId || caller.userGeneration != userGeneration
                || BUserHandle.getAppId(caller.buid) != appId)) {
            throw new SecurityException("Process query package does not belong to the live caller");
        }
        return appId;
    }

    private static ActivityManager.RunningAppProcessInfo copyRunningAppProcessInfo(
            ActivityManager.RunningAppProcessInfo source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            ActivityManager.RunningAppProcessInfo result = ActivityManager.RunningAppProcessInfo.CREATOR.createFromParcel(parcel);
            try {
                FrameworkMemberAccess.Member.PROCESS_PACKAGE_DEPENDENCIES.writeIfPresent(result, null);
                FrameworkMemberAccess.Member.PROCESS_REASON_IMPORTANCE.writeIfPresent(result, 0);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot normalize host process metadata", failure);
            }
            return result;
        } finally {
            parcel.recycle();
        }
    }

    public int getBUidByPidOrPackageName(int pid, String packageName) {
        ProcessRecord callingProcess = findProcessByPid(pid);
        if (callingProcess == null) {
            return BPackageManagerService.get().getAppId(packageName);
        }
        return BUserHandle.getAppId(callingProcess.buid);
    }

    public int getUserIdByCallingPid(int callingPid) {
        ProcessRecord callingProcess = findProcessByPid(callingPid);
        if (callingProcess == null) {
            return 0;
        }
        return callingProcess.userId;
    }

    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mProcessLock) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    public void notifyApplicationLocales(String packageName, int userId, long generation) {
        List<ProcessRecord> processes;
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) { processes = new ArrayList<>(mPidsSelfLocked); }
        }
        for (ProcessRecord record : processes) {
            if (record.userId != userId || record.userGeneration != generation
                    || !packageName.equals(record.getPackageName()) || record.bActivityThread == null) continue;
            try {
                // The guest re-reads authoritative state instead of trusting locale values in a callback.
                record.bActivityThread.refreshApplicationLocales(record);
            } catch (RemoteException failure) {
                Slog.w(TAG, "Locale notification unavailable: user=" + userId + " package=" + packageName, failure);
            }
        }
    }

    /**
     * Only the real host main process may forward a captured Binder pid. Guests share our UID,
     * so UID equality alone must never authorize an arbitrary "creator pid" assertion.
     */
    public Bundle resolveMediaCreationOwner(int callerPid) {
        final int hostUid = NexBoxCore.getHostUid();
        final int requesterPid = Binder.getCallingPid();
        if (Binder.getCallingUid() != hostUid || requesterPid <= 0 || callerPid <= 0) {
            throw new SecurityException("Media owner lookup requires the host provider");
        }
        ActivityManager manager = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> running = manager == null ? null : manager.getRunningAppProcesses();
        if (running == null) throw new IllegalStateException("Media creator process list unavailable");
        boolean hostMainRequester = false;
        ActivityManager.RunningAppProcessInfo writer = null;
        for (ActivityManager.RunningAppProcessInfo process : running) {
            if (process == null) continue;
            if (process.pid == requesterPid && process.uid == hostUid
                    && NexBoxCore.getHostPkg().equals(process.processName)) hostMainRequester = true;
            if (process.pid == callerPid && process.uid == hostUid) writer = process;
        }
        if (!hostMainRequester) throw new SecurityException("Only the host main media gateway may resolve a creator");
        if (writer == null) return null;

        // Match the same lock order as process creation and profile deletion. No application
        // Binder call or media I/O happens while these registry locks are held.
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (mProcessLock) {
                for (ProcessRecord record : mPidsSelfLocked) {
                    if (record.pid != callerPid || record.uid != hostUid || record.userId < 0
                            || record.userGeneration <= 0L || record.info == null
                            || record.info.packageName == null || record.info.packageName.isEmpty()
                            || record.bpid < 0 || record.bpid >= ProxyManifest.FREE_COUNT
                            || !ProxyManifest.getProcessName(record.bpid).equals(writer.processName)
                            || record.bActivityThread == null || !record.bActivityThread.asBinder().isBinderAlive()) continue;
                    synchronized (mProcessMap) {
                        Map<String, ProcessRecord> processes = mProcessMap.get(processKey(record));
                        if (processes == null || processes.get(record.processName) != record) return null;
                    }
                    BUserInfo currentUser = BUserManagerService.get().getUserInfo(record.userId);
                    if (currentUser == null || currentUser.createTime != record.userGeneration) return null;
                    ApplicationInfo installed = BPackageManagerService.get()
                            .getApplicationInfo(record.info.packageName, 0, record.userId);
                    if (installed == null || !record.info.packageName.equals(installed.packageName)
                            || installed.uid != record.info.uid
                            || BUserHandle.getAppId(record.buid) != BUserHandle.getAppId(installed.uid)) return null;
                    Bundle result = new Bundle();
                    result.putString(NexBoxMediaContract.COL_OWNER_PACKAGE_NAME, installed.packageName);
                    result.putInt(NexBoxMediaContract.COL_OWNER_USER_ID, record.userId);
                    result.putLong(NexBoxMediaContract.COL_OWNER_USER_GENERATION, record.userGeneration);
                    return result;
                }
                return null;
            }
        }
    }

    /** No guest-supplied package/UID is trusted, and host/system callers are not virtualized. */
    public int resolveCallingUid(int callerPid) {
        ProcessRecord caller = resolveLiveCaller(callerPid, null);
        return caller == null ? -1 : caller.buid;
    }

    public AppConfig resolveProviderCaller(int callerPid, IBinder expectedProcessToken) {
        if (expectedProcessToken == null) return null;
        ProcessRecord caller = resolveLiveCaller(callerPid, expectedProcessToken);
        return caller == null ? null : caller.getClientConfig();
    }

    private ProcessRecord resolveLiveCaller(int callerPid, IBinder expectedProcessToken) {
        int hostUid = NexBoxCore.getHostUid();
        int requesterPid = Binder.getCallingPid();
        if (Binder.getCallingUid() != hostUid || requesterPid <= 0 || callerPid <= 0) {
            throw new SecurityException("Guest caller lookup requires the container transport");
        }
        ProcessRecord requester = mLiveIdentityProcesses.get(requesterPid);
        ProcessRecord caller = mLiveIdentityProcesses.get(callerPid);
        if (!isLiveIdentityProcess(requester, hostUid) || !isLiveIdentityProcess(caller, hostUid)
                || requester.userId != caller.userId || requester.userGeneration != caller.userGeneration
                || (expectedProcessToken != null && caller != expectedProcessToken)) {
            return null;
        }
        BUserInfo user = BUserManagerService.get().getUserInfo(requester.userId);
        if (user == null || user.createTime != requester.userGeneration
                || mLiveIdentityProcesses.get(requesterPid) != requester
                || mLiveIdentityProcesses.get(callerPid) != caller) return null;
        return caller;
    }

    private static boolean isLiveIdentityProcess(ProcessRecord record, int hostUid) {
        return record != null && record.uid == hostUid && record.buid >= Process.FIRST_APPLICATION_UID
                && record.userId >= 0 && record.userGeneration > 0L
                && record.bActivityThread != null && record.bActivityThread.asBinder().isBinderAlive();
    }

    private static String getProcessName(Context context, int pid) {
        String processName = null;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.pid == pid) {
                processName = info.processName;
                break;
            }
        }
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static int getPid(Context context, String processName) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                if (runningAppProcess.processName.equals(processName)) {
                    return runningAppProcess.pid;
                }
            }
        } catch (Throwable e) {
            Slog.e("BProcessManagerService", "Container operation failed", e);
        }
        return -1;
    }

    private static void createProc(ProcessRecord record) {
        File cmdline = new File(BEnvironment.getProcDir(record.bpid), "cmdline");
        try {
            BzFileUtils.writeToFile(record.processName.getBytes(), cmdline);
        } catch (IOException ignored) {
        }
    }

    private static void removeProc(ProcessRecord record) {
        BzFileUtils.deleteDir(BEnvironment.getProcDir(record.bpid));
    }


    @Override
    public void systemReady() {
        // This runs before package/user state is loaded or service Binders are published.
        // Old guests may have survived an earlier core process and still own file handles.
        synchronized (mProcessLock) {
            Map<Integer, String> orphans = untrackedGuestProcesses();
            for (Map.Entry<Integer, String> orphan : orphans.entrySet()) {
                // Re-read AMS identity immediately before signaling. Do not use the guest's
                // renamed /proc command line, a package-name guess, or same UID alone.
                if (orphan.getValue().equals(untrackedGuestProcesses().get(orphan.getKey()))) {
                    Process.killProcess(orphan.getKey());
                }
            }
            if (!orphans.isEmpty()) {
                long deadline = SystemClock.elapsedRealtime() + 3000L;
                while (true) {
                    Map<Integer, String> current = untrackedGuestProcesses();
                    boolean pending = false;
                    for (Map.Entry<Integer, String> orphan : orphans.entrySet()) {
                        if (orphan.getValue().equals(current.get(orphan.getKey()))) {
                            pending = true;
                            break;
                        }
                    }
                    if (!pending) break;
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining <= 0) {
                        throw new IllegalStateException("Previous guest processes have not exited; user data was preserved");
                    }
                    try {
                        Thread.sleep(Math.min(remaining, 50L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Guest process recovery was interrupted", interrupted);
                    }
                }
                Slog.i(TAG, "action=recoverOrphanProcesses exited=" + orphans.size());
            }
        }
        BzFileUtils.deleteDir(BEnvironment.getProcDir());
    }

    private Map<Integer, String> untrackedGuestProcesses() {
        Map<Integer, String> result = new HashMap<>();
        for (ActivityManager.RunningAppProcessInfo process : runningHostProcesses()) {
            if (process == null || !isGuestRecoveryCandidate(NexBoxCore.getHostPkg(), Process.myUid(),
                    Process.myPid(), process.pid, process.uid, process.processName)) continue;
            boolean tracked = false;
            for (ProcessRecord record : mPidsSelfLocked) {
                if (record.pid == process.pid) {
                    tracked = true;
                    break;
                }
            }
            if (!tracked) result.put(process.pid, process.processName);
        }
        return result;
    }

    private static List<ActivityManager.RunningAppProcessInfo> runningHostProcesses() {
        List<ActivityManager.RunningAppProcessInfo> processes = readRunningHostProcesses();
        if (processes.isEmpty()) throw new IllegalStateException("Cannot inspect container process lifetimes");
        return processes;
    }

    private static List<ActivityManager.RunningAppProcessInfo> readRunningHostProcesses() {
        long identity = Binder.clearCallingIdentity();
        try {
            ActivityManager manager = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new IllegalStateException("System activity service unavailable");
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            return processes == null ? Collections.emptyList() : processes;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @SuppressWarnings("deprecation")
    private static List<ActivityManager.RunningServiceInfo> readRunningHostServices() {
        long identity = Binder.clearCallingIdentity();
        try {
            ActivityManager manager = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) throw new IllegalStateException("System activity service unavailable");
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(Integer.MAX_VALUE);
            return services == null ? Collections.emptyList() : services;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
