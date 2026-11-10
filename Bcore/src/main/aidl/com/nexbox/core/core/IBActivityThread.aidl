package com.nexbox.core.core;

import android.os.IBinder;
import android.content.ComponentName;
import android.content.Intent;
import java.util.List;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import com.nexbox.core.entity.am.ReceiverData;
import com.nexbox.core.entity.JobRecord;
import android.app.job.JobParameters;
import android.app.job.JobWorkItem;

interface IBActivityThread {
    IBinder getActivityThread();
    void bindApplication();
    void restartJobService(String selfId);
    void dispatchJobStart(in JobRecord record, in JobParameters params);
    void dispatchJobStop(in JobRecord record, in JobParameters params);
    void dispatchJobEvent(in JobRecord record, in JobParameters params, in JobWorkItem work, String event);
    IBinder acquireContentProviderClient(in ProviderInfo providerInfo);

    IBinder peekService(in Intent intent);
    void stopService(in Intent componentName);

    void finishActivity(IBinder token);
    void handleNewIntent(IBinder token, in Intent intent);

    void scheduleReceiver(in ReceiverData data);
    void refreshApplicationLocales(in IBinder processToken);
}
