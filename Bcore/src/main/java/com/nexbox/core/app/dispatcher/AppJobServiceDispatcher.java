package com.nexbox.core.app.dispatcher;

import android.app.job.JobParameters;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.entity.JobRecord;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.utils.JobCompat;
import com.nexbox.core.utils.Slog;
import java.util.HashMap;
import java.util.Map;

/** Confined to the guest main thread. Instances are keyed by host identity, never guest job ID. */
public class AppJobServiceDispatcher {
    private static final AppJobServiceDispatcher INSTANCE = new AppJobServiceDispatcher();
    private final Map<String, JobRecord> jobs = new HashMap<>();
    private final Map<String, android.app.job.JobService> services = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    public static AppJobServiceDispatcher get() { return INSTANCE; }
    private String key(JobRecord r) { return r.hostId + ":" + r.nonce; }
    private String component(JobRecord r) { return r.packageName + "/" + r.mServiceInfo.name; }
    public void dispatch(JobRecord record, JobParameters host, boolean start) {
        try {
            BUserInfo user = BUserManager.get().getService().getUserInfo(record.userId);
            if (user == null || user.createTime != record.generation || record.userId != BActivityThread.getUserId()
                    || !record.packageName.equals(BActivityThread.getAppPackageName())
                    || !record.mServiceInfo.processName.equals(BActivityThread.getAppProcessName())) {
                JobCompat.acknowledge(host, start, false);
                return;
            }
            String key = key(record);
            JobRecord live = jobs.get(key);
            if (start && live == null) {
                record.mJobService = services.get(component(record));
                if (record.mJobService == null) record.mJobService = NexBoxCore.currentActivityThread().createJobService(record.mServiceInfo);
                if (record.mJobService == null) { fail(record, host, true); return; }
                services.put(component(record), record.mJobService);
                jobs.put(key, record);
                live = record;
            }
            if (live == null) { JobCompat.acknowledge(host, start, false); return; }
            JobParameters guest = JobCompat.restore(record, host, () -> main.post(() -> destroy(key)));
            IBinder binder = live.mJobService.onBind(null);
            Object service = Class.forName("android.app.job.IJobService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class.forName("android.app.job.IJobService").getMethod(start ? "startJob" : "stopJob", JobParameters.class).invoke(service, guest);
        } catch (Exception failure) {
            Slog.e("AppJobServiceDispatcher", "Guest job dispatch failed", failure);
            fail(record, host, start);
        }
    }
    private void fail(JobRecord record, JobParameters host, boolean start) {
        JobCompat.acknowledge(host, start, start);
        if (start) JobCompat.finish(host, true);
        destroy(key(record), false);
        try { NexBoxCore.getBJobManager().getService().finishJob(record.hostId, record.nonce, false); }
        catch (Exception failure) { Slog.w("AppJobServiceDispatcher", "Cannot release failed job", failure); }
    }
    private void destroy(String key) { destroy(key, true); }
    private void destroy(String key, boolean completed) {
        JobRecord record = jobs.remove(key);
        if (record != null) {
            String component = component(record);
            boolean referenced = false;
            for (JobRecord other : jobs.values()) if (component(other).equals(component)) { referenced = true; break; }
            try {
                if (!referenced) {
                    android.app.job.JobService service = services.remove(component);
                    if (service != null) service.onDestroy();
                }
            } finally {
                try { NexBoxCore.getBJobManager().getService().finishJob(record.hostId, record.nonce, completed); }
                catch (Exception failure) { Slog.w("AppJobServiceDispatcher", "Job completion registry unavailable", failure); }
            }
        }
    }
    public void dispatchEvent(JobRecord record, JobParameters host, android.app.job.JobWorkItem work, String event) {
        JobRecord live = jobs.get(key(record));
        if (live == null) return;
        try {
            JobParameters guest = JobCompat.restore(record, host, () -> main.post(() -> destroy(key(record))));
            Object service = Class.forName("android.app.job.IJobService$Stub").getMethod("asInterface", IBinder.class)
                    .invoke(null, live.mJobService.onBind(null));
            Class<?> contract = Class.forName("android.app.job.IJobService");
            if ("onNetworkChanged".equals(event)) contract.getMethod(event, JobParameters.class).invoke(service, guest);
            else if ("getTransferredDownloadBytes".equals(event) || "getTransferredUploadBytes".equals(event)) {
                contract.getMethod(event, JobParameters.class, android.app.job.JobWorkItem.class).invoke(service, guest, work);
            } else throw new UnsupportedOperationException("Unknown job event " + event);
        } catch (Exception failure) { Slog.w("AppJobServiceDispatcher", "Cannot deliver job event " + event, failure); }
    }
    public void onConfigurationChanged(Configuration config) {
        for (android.app.job.JobService service : services.values()) service.onConfigurationChanged(config);
    }
    public void onLowMemory() { for (android.app.job.JobService service : services.values()) service.onLowMemory(); }
    public void onTrimMemory(int level) { for (android.app.job.JobService service : services.values()) service.onTrimMemory(level); }
    public void onDestroy() { for (String key : new java.util.ArrayList<>(jobs.keySet())) destroy(key); }
}
