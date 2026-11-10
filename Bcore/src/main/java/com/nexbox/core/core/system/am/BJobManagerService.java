package com.nexbox.core.core.system.am;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Parcel;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.AtomicFile;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.entity.JobRecord;
import com.nexbox.core.proxy.ProxyJobService;
import com.nexbox.core.utils.JobCompat;
import com.nexbox.core.utils.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** All OS operations occur in the server's default namespace, never in a guest process. */
public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService INSTANCE = new BJobManagerService();
    private static final int VERSION = 2;
    public static final String EXTRA_JOB_OWNER = "nexbox.virtual.job.owner";
    public static final String EXTRA_JOB_USER_ID = "nexbox.virtual.job.userId";
    public static final String EXTRA_JOB_USER_GENERATION = "nexbox.virtual.job.userGeneration";
    public static final String EXTRA_NONCE = "nexbox.virtual.job.nonce";
    private static final String EXTRA_VERSION = "nexbox.virtual.job.version";
    private final Map<Integer, JobRecord> records = new HashMap<>();
    private final Map<String, ProcessRecord> running = new HashMap<>();
    private final Map<String, JobRecord> previousRecords = new HashMap<>();
    private final Map<String, android.os.IBinder.DeathRecipient> deaths = new HashMap<>();
    private final Map<String, Object> dispatching = new HashMap<>();
    private final Map<Integer, Integer> failures = new HashMap<>();
    private final java.util.Set<String> acknowledged = new java.util.HashSet<>();
    private boolean loaded;
    private boolean storageHealthy = true;
    private int nextId = 0x40000000;

    public static BJobManagerService get() { return INSTANCE; }
    private JobScheduler scheduler() {
        return Objects.requireNonNull(NexBoxCore.getContext().getSystemService(JobScheduler.class));
    }
    private AtomicFile storage() {
        return new AtomicFile(new File(BEnvironment.getSystemDir(), "jobs-v2.bin"));
    }
    private ProcessRecord caller() {
        ProcessRecord process = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (Binder.getCallingUid() != android.os.Process.myUid() || process == null
                || process.bActivityThread == null || !process.bActivityThread.asBinder().isBinderAlive()
                || !valid(process.userId, process.userGeneration)) {
            throw new SecurityException("Unknown or expired virtual job caller");
        }
        return process;
    }
    private boolean valid(int userId, long generation) {
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        return user != null && generation > 0 && user.createTime == generation;
    }
    private boolean owns(JobRecord r, ProcessRecord p, String namespace, boolean all) {
        return r.userId == p.userId && r.generation == p.userGeneration
                && r.packageName.equals(p.info.packageName) && (all || Objects.equals(r.namespace, namespace));
    }
    private JobRecord find(ProcessRecord p, String namespace, int id) {
        JobRecord inactive = null;
        for (JobRecord r : records.values()) {
            if (owns(r, p, namespace, false) && r.mJobInfo.getId() == id) {
                if (pending(r)) return r;
                inactive = r;
            }
        }
        return inactive;
    }

    @Override
    public int scheduleAsPackage(JobInfo info, String namespace, String packageName, int androidUserId) {
        ProcessRecord owner = caller();
        if (!owner.info.packageName.equals(packageName) || androidUserId != Binder.getCallingUid() / 100000) {
            throw new SecurityException("Cross-package/profile job delegation is forbidden");
        }
        return schedule(info, null, namespace);
    }
    @Override
    public int schedule(JobInfo info, JobWorkItem work, String namespace) {
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            ProcessRecord owner = caller();
            synchronized (this) {
                load();
                if (!storageHealthy || info == null || info.getService() == null
                        || !owner.info.packageName.equals(info.getService().getPackageName())) return JobScheduler.RESULT_FAILURE;
                ResolveInfo resolved = BPackageManagerService.get().resolveService(
                        new Intent().setComponent(info.getService()), PackageManager.GET_META_DATA, null, owner.userId);
                if (resolved == null || resolved.serviceInfo == null
                        || !"android.permission.BIND_JOB_SERVICE".equals(resolved.serviceInfo.permission)) return JobScheduler.RESULT_FAILURE;
                long identity = Binder.clearCallingIdentity();
                JobRecord previous = find(owner, namespace, info.getId());
                JobRecord record = new JobRecord();
                try {
                    JobInfo occupied = previous == null ? null : scheduler().getPendingJob(previous.hostId);
                    boolean conflict = occupied != null && (!matches(previous, occupied.getExtras())
                            || !new ComponentName(NexBoxCore.getHostPkg(), ProxyJobService.class.getName()).equals(occupied.getService()));
                    record.hostId = previous == null || conflict ? allocateId() : previous.hostId;
                    record.userId = owner.userId;
                    record.generation = owner.userGeneration;
                    record.packageName = owner.info.packageName;
                    record.namespace = namespace;
                    record.nonce = UUID.randomUUID().toString();
                    record.mJobInfo = JobCompat.copy(info);
                    record.mServiceInfo = resolved.serviceInfo;
                    JobInfo proxy = proxy(record);
                    if (previous != null) previousRecords.put(key(previous), previous);
                    records.put(record.hostId, record);
                    // Save ownership before OS submission; a crash cannot leave an adoptable unowned job.
                    if (!save()) { restore(previous, record); return JobScheduler.RESULT_FAILURE; }
                    int result = work == null ? scheduler().schedule(proxy) : scheduler().enqueue(proxy, work);
                    if (result != JobScheduler.RESULT_SUCCESS) restore(previous, record);
                    else {
                        if (previous != null && previous.hostId != record.hostId) records.remove(previous.hostId);
                        failures.remove(record.hostId);
                        previousRecords.entrySet().removeIf(entry -> entry.getValue().hostId == record.hostId
                                && !running.containsKey(entry.getKey()));
                    }
                    save();
                    return result;
                } catch (RuntimeException failure) {
                    restore(previous, record);
                    save();
                    Slog.w("BJobManager", "Job submission failed", failure);
                    return JobScheduler.RESULT_FAILURE;
                } finally { Binder.restoreCallingIdentity(identity); }
            }
        }
    }
    private void restore(JobRecord previous, JobRecord record) {
        records.remove(record.hostId);
        if (previous != null) records.put(previous.hostId, previous);
        if (previous != null) previousRecords.remove(key(previous));
    }
    private String key(JobRecord record) { return record.hostId + ":" + record.nonce; }
    private int allocateId() {
        List<JobInfo> pending = scheduler().getAllPendingJobs();
        for (long attempt = 0; attempt <= Integer.MAX_VALUE; attempt++) {
            int id = nextId++;
            if (nextId < 0) nextId = 1;
            if (records.containsKey(id)) continue;
            boolean used = false;
            for (JobInfo job : pending) if (job.getId() == id) { used = true; break; }
            if (!used) return id;
        }
        throw new IllegalStateException("No available host job IDs");
    }
    private JobInfo proxy(JobRecord record) {
        JobInfo result = JobCompat.copy(record.mJobInfo);
        JobCompat.set(result, "jobId", record.hostId);
        JobCompat.set(result, "service", new ComponentName(NexBoxCore.getHostPkg(), ProxyJobService.class.getName()));
        PersistableBundle extras = new PersistableBundle(result.getExtras());
        JobCompat.set(result, "extras", extras);
        // Guest extras are restored from the original JobInfo, including colliding key names.
        extras.putInt(EXTRA_VERSION, VERSION);
        extras.putString(EXTRA_JOB_OWNER, NexBoxCore.getHostPkg());
        extras.putInt(EXTRA_JOB_USER_ID, record.userId);
        extras.putLong(EXTRA_JOB_USER_GENERATION, record.generation);
        extras.putString(EXTRA_NONCE, record.nonce);
        return result;
    }
    private boolean matches(JobRecord r, PersistableBundle extras) {
        return extras != null && extras.getInt(EXTRA_VERSION, 0) == VERSION
                && NexBoxCore.getHostPkg().equals(extras.getString(EXTRA_JOB_OWNER))
                && extras.getInt(EXTRA_JOB_USER_ID, -1) == r.userId
                && extras.getLong(EXTRA_JOB_USER_GENERATION, 0) == r.generation
                && r.nonce.equals(extras.getString(EXTRA_NONCE));
    }
    private boolean pending(JobRecord r) {
        JobInfo job = scheduler().getPendingJob(r.hostId);
        return job != null && new ComponentName(NexBoxCore.getHostPkg(), ProxyJobService.class.getName())
                .equals(job.getService()) && matches(r, job.getExtras());
    }
    @Override
    public List<JobInfo> getPendingJobs(String namespace, boolean allNamespaces) {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                List<JobInfo> result = new ArrayList<>();
                for (JobRecord r : records.values()) if (owns(r, owner, namespace, allNamespaces) && pending(r)) result.add(JobCompat.copy(r.mJobInfo));
                return result;
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public List<JobRecord> getPendingJobRecords() {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                List<JobRecord> result = new ArrayList<>();
                for (JobRecord r : records.values()) if (owns(r, owner, null, true) && pending(r)) result.add(r);
                return result;
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public int[] getPendingJobReasons(String namespace, int jobId, boolean multiple) {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                JobRecord r = find(owner, namespace, jobId);
                if (r == null || !pending(r)) return new int[] { -2 };
                try {
                    Object value = JobScheduler.class.getMethod(multiple ? "getPendingJobReasons" : "getPendingJobReason", int.class)
                            .invoke(scheduler(), r.hostId);
                    return multiple ? (int[]) value : new int[] { (Integer) value };
                } catch (ReflectiveOperationException failure) {
                    throw new UnsupportedOperationException("Job pending reasons are unavailable", failure);
                }
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public JobInfo getPendingJob(String namespace, int jobId) {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                JobRecord r = find(owner, namespace, jobId);
                return r != null && pending(r) ? JobCompat.copy(r.mJobInfo) : null;
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public void cancel(String namespace, int jobId) {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                JobRecord r = find(owner, namespace, jobId);
                if (r != null) { remove(r); save(); }
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public void cancelAll(String namespace, boolean allNamespaces) {
        ProcessRecord owner = caller();
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                for (JobRecord r : new ArrayList<>(records.values())) if (owns(r, owner, namespace, allNamespaces)) remove(r);
                save();
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    private void remove(JobRecord r) {
        if (pending(r)) scheduler().cancel(r.hostId);
        records.remove(r.hostId);
        if (running.containsKey(key(r))) previousRecords.put(key(r), r);
        // Keep the live dispatch until OS delivers onStopJob.
    }
    public void removeUser(int userId) {
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                load();
                for (JobRecord r : new ArrayList<>(records.values())) if (r.userId == userId) remove(r);
                save();
            }
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override
    public int dispatchJob(JobParameters params, boolean start) throws RemoteException {
        if (Binder.getCallingUid() != android.os.Process.myUid()
                || !isHostMainProcess(Binder.getCallingPid())) {
            throw new SecurityException("Job bridge caller is not a host process");
        }
        JobRecord record = null;
        ProcessRecord process;
        Object dispatchToken = new Object();
        try {
            String processName;
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                synchronized (this) {
                    load();
                    if (!storageHealthy) return 0;
                    record = params == null ? null : records.get(params.getJobId());
                    if (params != null && (record == null || !matches(record, params.getExtras()))) {
                        record = previousRecords.get(params.getJobId() + ":" + params.getExtras().getString(EXTRA_NONCE));
                    }
                    if (record == null || !matches(record, params.getExtras()) || !valid(record.userId, record.generation)) return 0;
                    if (start && !pending(record)) return 0;
                    if (start) {
                        int attempts = failures.getOrDefault(record.hostId, 0) + 1;
                        failures.put(record.hostId, attempts);
                        save();
                        if (attempts > 3) return 0;
                        dispatching.put(key(record), dispatchToken);
                    } else {
                        // A stop received while Provider attachment is pending must prevent
                        // that older start from being published after this stop returns.
                        dispatching.remove(key(record));
                    }
                    process = start ? null : running.get(key(record));
                }
                ResolveInfo resolved = resolveJobService(record);
                if (resolved == null) return 0;
                processName = resolved.serviceInfo.processName;
            }

            // A cold Provider connection may call back into lifecycle services. Only the
            // captured nonce/generation, not either global lock, crosses this boundary.
            if (start) process = BProcessManagerService.get().startProcessLocked(record.packageName,
                    processName, record.userId, -1, Binder.getCallingPid(), record.generation);
            else if (process == null) process = BProcessManagerService.get().findProcessRecord(
                    record.packageName, processName, record.userId);
            if (process == null || process.bActivityThread == null) return start ? 2 : 0;

            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                if (!valid(record.userId, record.generation) || process.userGeneration != record.generation) return 0;
                ResolveInfo resolved = resolveJobService(record);
                if (resolved == null || !Objects.equals(process.processName, resolved.serviceInfo.processName)
                        || BProcessManagerService.get().findProcessRecord(record.packageName,
                        process.processName, record.userId) != process) return 0;
                synchronized (this) {
                    if (!storageHealthy || !matches(record, params.getExtras())
                            || (records.get(record.hostId) != record
                            && previousRecords.get(key(record)) != record)) return 0;
                    if (start && (dispatching.get(key(record)) != dispatchToken || !pending(record))) return 0;
                    record.mServiceInfo = resolved.serviceInfo;
                    if (start) {
                        final JobRecord active = record;
                        final ProcessRecord target = process;
                        android.os.IBinder.DeathRecipient death = new android.os.IBinder.DeathRecipient() {
                            @Override public void binderDied() {
                                boolean retry;
                                boolean wasStarted;
                                synchronized (BJobManagerService.this) {
                                    if (running.get(key(active)) != target || deaths.get(key(active)) != this) return;
                                    running.remove(key(active));
                                    deaths.remove(key(active));
                                    wasStarted = acknowledged.remove(key(active));
                                    retry = failures.getOrDefault(active.hostId, 0) < 3 && valid(active.userId, active.generation);
                                }
                                if (!wasStarted) JobCompat.acknowledge(params, true, true);
                                JobCompat.finish(params, retry);
                            }
                        };
                        process.bActivityThread.asBinder().linkToDeath(death, 0);
                        ProcessRecord previous = running.put(key(record), process);
                        android.os.IBinder.DeathRecipient previousDeath = deaths.put(key(record), death);
                        if (previous != null && previousDeath != null) {
                            previous.bActivityThread.asBinder().unlinkToDeath(previousDeath, 0);
                        }
                    } else {
                        ProcessRecord current = running.get(key(record));
                        if (current != null && current != process) return 0;
                    }
                }
            }
            if (start) process.bActivityThread.dispatchJobStart(record, params);
            else process.bActivityThread.dispatchJobStop(record, params);
            return 1;
        } finally {
            if (start && record != null) {
                synchronized (this) {
                    if (dispatching.get(key(record)) == dispatchToken) dispatching.remove(key(record));
                }
            }
        }
    }

    private ResolveInfo resolveJobService(JobRecord record) {
        ResolveInfo resolved = BPackageManagerService.get().resolveService(
                new Intent().setComponent(record.mJobInfo.getService()),
                PackageManager.GET_META_DATA, null, record.userId);
        return resolved != null && resolved.serviceInfo != null
                && record.packageName.equals(resolved.serviceInfo.packageName)
                && "android.permission.BIND_JOB_SERVICE".equals(resolved.serviceInfo.permission)
                ? resolved : null;
    }
    @Override public boolean canRunUserInitiatedJobs() {
        ProcessRecord owner = caller();
        android.content.pm.PackageInfo info = BPackageManagerService.get().getPackageInfo(owner.info.packageName,
                PackageManager.GET_PERMISSIONS, owner.userId);
        boolean declared = false;
        if (info != null && info.requestedPermissions != null) {
            for (String permission : info.requestedPermissions) if ("android.permission.RUN_USER_INITIATED_JOBS".equals(permission)) declared = true;
        }
        if (!declared) return false;
        long identity = Binder.clearCallingIdentity();
        try {
            return (Boolean) JobScheduler.class.getMethod("canRunUserInitiatedJobs").invoke(scheduler());
        } catch (ReflectiveOperationException unavailable) { return false; }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public boolean dispatchJobEvent(JobParameters params, JobWorkItem work, String event) throws RemoteException {
        if (Binder.getCallingUid() != android.os.Process.myUid() || !isHostMainProcess(Binder.getCallingPid())) {
            throw new SecurityException("Job event caller is not host");
        }
        if (!"onNetworkChanged".equals(event) && !"getTransferredDownloadBytes".equals(event)
                && !"getTransferredUploadBytes".equals(event)) throw new UnsupportedOperationException("Unknown job event");
        JobRecord record;
        ProcessRecord process;
        synchronized (this) {
            String key = params.getJobId() + ":" + params.getExtras().getString(EXTRA_NONCE);
            record = records.get(params.getJobId());
            if (record == null || !matches(record, params.getExtras())) record = previousRecords.get(key);
            process = running.get(key);
            if (record == null || process == null || !matches(record, params.getExtras()) || !valid(record.userId, record.generation)) return false;
        }
        process.bActivityThread.dispatchJobEvent(record, params, work, event);
        return true;
    }
    private boolean isHostMainProcess(int pid) {
        android.app.ActivityManager am = NexBoxCore.getContext().getSystemService(android.app.ActivityManager.class);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
        if (processes == null) return false;
        String hostProcess = NexBoxCore.getContext().getApplicationInfo().processName;
        for (android.app.ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == pid && process.uid == android.os.Process.myUid() && hostProcess.equals(process.processName)) return true;
        }
        return false;
    }
    @Override public synchronized void finishJob(int hostId, String nonce, boolean completed) {
        ProcessRecord owner = caller();
        String key = hostId + ":" + nonce;
        ProcessRecord process = running.get(key);
        if (process == null || process.pid != owner.pid || process.userGeneration != owner.userGeneration) return;
        running.remove(key);
        acknowledged.remove(key);
        android.os.IBinder.DeathRecipient death = deaths.remove(key);
        if (death != null) process.bActivityThread.asBinder().unlinkToDeath(death, 0);
        previousRecords.remove(key);
        JobRecord current = records.get(hostId);
        if (completed && current != null && nonce.equals(current.nonce)) failures.remove(hostId);
        save();
    }
    @Override public synchronized void markJobStarted(int hostId, String nonce) {
        ProcessRecord owner = caller();
        String key = hostId + ":" + nonce;
        ProcessRecord process = running.get(key);
        if (process != null && process.pid == owner.pid && process.userGeneration == owner.userGeneration) acknowledged.add(key);
    }
    private void load() {
        if (loaded) return;
        loaded = true;
        if (!storage().getBaseFile().exists()) return;
        Parcel p = Parcel.obtain();
        try {
            byte[] bytes = storage().readFully();
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            if (p.readInt() != VERSION || p.readInt() != Build.VERSION.SDK_INT) throw new IllegalStateException("Unsupported job registry");
            nextId = p.readInt();
            int count = p.readInt();
            if (count < 0 || count > 100000) throw new IllegalStateException("Invalid job count");
            for (int i = 0; i < count; i++) {
                JobRecord r = JobRecord.CREATOR.createFromParcel(p);
                if (r.nonce == null || r.packageName == null || r.mJobInfo == null) throw new IllegalStateException("Invalid job owner");
                records.put(r.hostId, r);
            }
            int previousCount = p.readInt();
            if (previousCount < 0 || previousCount > 100000) throw new IllegalStateException("Invalid job snapshot count");
            for (int i = 0; i < previousCount; i++) {
                JobRecord r = JobRecord.CREATOR.createFromParcel(p);
                previousRecords.put(key(r), r);
            }
            int failureCount = p.readInt();
            if (failureCount < 0 || failureCount > 100000) throw new IllegalStateException("Invalid job retry count");
            for (int i = 0; i < failureCount; i++) failures.put(p.readInt(), p.readInt());
            // OS metadata decides which side of an interrupted submission actually committed.
            for (JobRecord r : new ArrayList<>(records.values())) {
                JobInfo actual = scheduler().getPendingJob(r.hostId);
                if (actual != null && !matches(r, actual.getExtras())) {
                    JobRecord prior = previousRecords.get(r.hostId + ":" + actual.getExtras().getString(EXTRA_NONCE));
                    if (prior != null && matches(prior, actual.getExtras())) records.put(r.hostId, prior);
                }
            }
        } catch (Exception failure) {
            records.clear();
            previousRecords.clear();
            failures.clear();
            storageHealthy = false;
            Slog.e("BJobManager", "Job registry unavailable; refusing adoption or overwrite", failure);
        } finally { p.recycle(); }
    }
    private boolean save() {
        if (!storageHealthy) return false;
        Parcel p = Parcel.obtain();
        FileOutputStream stream = null;
        try {
            p.writeInt(VERSION);
            p.writeInt(Build.VERSION.SDK_INT);
            p.writeInt(nextId);
            p.writeInt(records.size());
            for (JobRecord r : records.values()) r.writeToParcel(p, 0);
            p.writeInt(previousRecords.size());
            for (JobRecord r : previousRecords.values()) r.writeToParcel(p, 0);
            p.writeInt(failures.size());
            for (Map.Entry<Integer, Integer> entry : failures.entrySet()) { p.writeInt(entry.getKey()); p.writeInt(entry.getValue()); }
            byte[] bytes = p.marshall();
            stream = storage().startWrite();
            stream.write(bytes);
            storage().finishWrite(stream);
            return true;
        } catch (Exception failure) {
            if (stream != null) storage().failWrite(stream);
            Slog.e("BJobManager", "Cannot persist job ownership", failure);
            return false;
        } finally { p.recycle(); }
    }
    @Override public synchronized void systemReady() { load(); }
}
