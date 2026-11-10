package com.nexbox.core.fake.service;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobWorkItem;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.am.IBJobManagerService;
import com.nexbox.core.entity.JobRecord;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared by every guest, including Google packages. No operation falls through to the host. */
final class GoogleJobCompat {
    private GoogleJobCompat() { }
    static Object invoke(Method method, Object[] arguments) throws Throwable {
        Object[] args = arguments == null ? new Object[0] : arguments;
        String name = method.getName();
        IBJobManagerService jobs = NexBoxCore.getBJobManager().getService();
        if (jobs == null) throw new android.os.DeadObjectException();
        String namespace = args.length > 0 && method.getParameterTypes()[0] == String.class ? (String) args[0] : null;
        int offset = args.length > 0 && method.getParameterTypes()[0] == String.class ? 1 : 0;
        switch (name) {
            case "schedule":
            case "enqueue": {
                int expected = offset + (name.equals("enqueue") ? 2 : 1);
                if (args.length != expected || !(args[offset] instanceof JobInfo)) return JobScheduler.RESULT_FAILURE;
                JobWorkItem work = name.equals("enqueue") ? (JobWorkItem) args[offset + 1] : null;
                if (name.equals("enqueue") && work == null) throw new IllegalArgumentException("Missing job work item");
                return jobs.schedule((JobInfo) args[offset], work, namespace);
            }
            case "scheduleAsPackage":
                if (args.length == offset + 4 && args[offset] instanceof JobInfo
                        && args[offset + 1] instanceof String && args[offset + 2] instanceof Integer) {
                    return jobs.scheduleAsPackage((JobInfo) args[offset], namespace,
                            (String) args[offset + 1], (Integer) args[offset + 2]);
                }
                throw new SecurityException("Virtual jobs cannot use another package/user identity");
            case "canRunUserInitiatedJobs":
                return jobs.canRunUserInitiatedJobs();
            case "hasRunUserInitiatedJobsPermission":
                if (args.length > 0 && !com.nexbox.core.app.BActivityThread.getAppPackageName().equals(args[0])) return false;
                return jobs.canRunUserInitiatedJobs();
            case "cancel":
                if (args.length != offset + 1) throw new IllegalArgumentException("Unknown cancel signature");
                jobs.cancel(namespace, (Integer) args[offset]);
                return null;
            case "cancelAll":
                jobs.cancelAll(null, true);
                return null;
            case "cancelAllInNamespace":
                jobs.cancelAll(namespace, false);
                return null;
            case "getPendingJob":
                return jobs.getPendingJob(namespace, (Integer) args[offset]);
            case "getAllPendingJobsInNamespace":
                return ParceledListSliceCompat.create(jobs.getPendingJobs(namespace, false));
            case "getAllPendingJobs":
                if (Map.class.isAssignableFrom(method.getReturnType())) {
                    Map<String, List<JobInfo>> grouped = new HashMap<>();
                    for (JobRecord record : jobs.getPendingJobRecords()) grouped.computeIfAbsent(record.namespace, key -> new ArrayList<>()).add(record.mJobInfo);
                    Map<String, Object> slices = new HashMap<>();
                    for (Map.Entry<String, List<JobInfo>> entry : grouped.entrySet()) slices.put(entry.getKey(), ParceledListSliceCompat.create(entry.getValue()));
                    return slices;
                }
                List<JobInfo> result = jobs.getPendingJobs(null, false);
                return List.class.isAssignableFrom(method.getReturnType()) ? result : ParceledListSliceCompat.create(result);
            case "getPendingJobReason":
                return jobs.getPendingJobReasons(namespace, (Integer) args[offset], false)[0];
            case "getPendingJobReasons":
                return jobs.getPendingJobReasons(namespace, (Integer) args[offset], true);
            default:
                throw new UnsupportedOperationException("Unsupported virtual JobScheduler operation: " + name);
        }
    }
    static int cancelJobIdIndex(Class<?>[] types) {
        if (types.length == 1 && types[0] == int.class) return 0;
        if (types.length == 2 && types[0] == String.class && types[1] == int.class) return 1;
        return -1;
    }
}
