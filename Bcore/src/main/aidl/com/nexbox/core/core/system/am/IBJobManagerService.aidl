package com.nexbox.core.core.system.am;

import android.app.job.JobInfo;
import android.app.job.JobWorkItem;
import android.app.job.JobParameters;
import com.nexbox.core.entity.JobRecord;

interface IBJobManagerService {
    int schedule(in JobInfo info, in JobWorkItem work, String namespace);
    int scheduleAsPackage(in JobInfo info, String namespace, String packageName, int androidUserId);
    List<JobInfo> getPendingJobs(String namespace, boolean allNamespaces);
    List<JobRecord> getPendingJobRecords();
    int[] getPendingJobReasons(String namespace, int jobId, boolean multiple);
    JobInfo getPendingJob(String namespace, int jobId);
    void cancel(String namespace, int jobId);
    void cancelAll(String namespace, boolean allNamespaces);
    int dispatchJob(in JobParameters params, boolean start);
    boolean canRunUserInitiatedJobs();
    boolean dispatchJobEvent(in JobParameters params, in JobWorkItem work, String event);
    void finishJob(int hostId, String nonce, boolean completed);
    void markJobStarted(int hostId, String nonce);
}
