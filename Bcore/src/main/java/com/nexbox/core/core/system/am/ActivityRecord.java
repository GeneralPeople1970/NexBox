package com.nexbox.core.core.system.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;

import java.util.UUID;

import com.nexbox.core.core.system.ProcessRecord;



public class ActivityRecord extends Binder {
    public TaskRecord task;
    public IBinder token;
    public IBinder resultTo;
    public ActivityInfo info;
    public ComponentName component;
    public ComponentName proxyComponent;
    public Intent intent;
    public int userId;
    public long userGeneration;
    public final String activityIdentity = "nexbox-activity:" + UUID.randomUUID();
    public boolean finished;
    public ProcessRecord processRecord;

    public static ActivityRecord create(Intent intent, ActivityInfo info, IBinder resultTo, int userId) {
        ActivityRecord record = new ActivityRecord();
        record.intent = intent;
        record.info = info;
        record.component = new ComponentName(info.packageName, info.name);
        record.resultTo = resultTo;
        record.userId = userId;
        return record;
    }


}
