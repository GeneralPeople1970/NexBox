package com.nexbox.core.proxy.record;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Parcelable;

import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.compat.BundleCompat;


public class ProxyActivityRecord {
    public static final String EXTRA_USER_ID = "nexbox.activity.userId";
    public static final String EXTRA_USER_GENERATION = "nexbox.activity.userGeneration";
    public static final String EXTRA_ACTIVITY_INFO = "nexbox.activity.info";
    public static final String EXTRA_TARGET = "nexbox.activity.target";
    public static final String EXTRA_ACTIVITY_RECORD = "nexbox.activity.record";

    public int mUserId;
    public long mUserGeneration;
    public String mActivityIdentity;
    public ActivityInfo mActivityInfo;
    public Intent mTarget;
    public IBinder mActivityRecord;

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord) {
        this(userId, activityInfo, target, activityRecord, 0L, null);
    }

    public ProxyActivityRecord(int userId, ActivityInfo activityInfo, Intent target, IBinder activityRecord,
                               long userGeneration, String activityIdentity) {
        mUserId = userId;
        mUserGeneration = userGeneration;
        mActivityIdentity = activityIdentity;
        mActivityInfo = activityInfo;
        mTarget = target;
        mActivityRecord = activityRecord;
    }

    public static void saveStub(Intent shadow, Intent target, ActivityInfo activityInfo, IBinder activityRecord,
                                 int userId, long userGeneration, String activityIdentity) {
        NexBoxIpcKeys.removeExtras(shadow, NexBoxIpcKeys.LEGACY_USER_ID, NexBoxIpcKeys.LEGACY_ACTIVITY_INFO,
                NexBoxIpcKeys.LEGACY_TARGET, NexBoxIpcKeys.LEGACY_ACTIVITY_RECORD);
        shadow.putExtra(EXTRA_USER_ID, userId);
        shadow.putExtra(EXTRA_USER_GENERATION, userGeneration);
        shadow.putExtra(EXTRA_ACTIVITY_INFO, activityInfo);
        shadow.putExtra(EXTRA_TARGET, target);
        shadow.setIdentifier(activityIdentity);
        BundleCompat.putBinder(shadow, EXTRA_ACTIVITY_RECORD, activityRecord);
    }

    private static boolean isCurrent(Intent intent) {
        return NexBoxIpcKeys.hasAnyExtra(intent, EXTRA_USER_ID, EXTRA_USER_GENERATION,
                EXTRA_ACTIVITY_INFO, EXTRA_TARGET, EXTRA_ACTIVITY_RECORD);
    }

    public static boolean hasTarget(Intent intent) {
        return intent != null && BundleCompat.getParcelable(intent,
                NexBoxIpcKeys.readKey(isCurrent(intent), EXTRA_TARGET, NexBoxIpcKeys.LEGACY_TARGET),
                Parcelable.class) != null;
    }

    /** Routing hints alone never establish ownership of a system task. */
    public static int readUserId(Intent intent) {
        return intent.getIntExtra(NexBoxIpcKeys.readKey(isCurrent(intent),
                EXTRA_USER_ID, NexBoxIpcKeys.LEGACY_USER_ID), -1);
    }

    public static long readUserGeneration(Intent intent) {
        return isCurrent(intent) ? intent.getLongExtra(EXTRA_USER_GENERATION, 0L) : 0L;
    }

    public static ProxyActivityRecord create(Intent intent) {
        boolean current = isCurrent(intent);
        int userId = readUserId(intent);
        ActivityInfo activityInfo = BundleCompat.getParcelable(intent, NexBoxIpcKeys.readKey(current,
                EXTRA_ACTIVITY_INFO, NexBoxIpcKeys.LEGACY_ACTIVITY_INFO), ActivityInfo.class);
        Intent target = BundleCompat.getParcelable(intent, NexBoxIpcKeys.readKey(current,
                EXTRA_TARGET, NexBoxIpcKeys.LEGACY_TARGET), Intent.class);
        IBinder activityRecord = BundleCompat.getBinder(intent, NexBoxIpcKeys.readKey(current,
                EXTRA_ACTIVITY_RECORD, NexBoxIpcKeys.LEGACY_ACTIVITY_RECORD));
        return new ProxyActivityRecord(userId, activityInfo, target, activityRecord,
                readUserGeneration(intent), intent.getIdentifier());
    }
}
