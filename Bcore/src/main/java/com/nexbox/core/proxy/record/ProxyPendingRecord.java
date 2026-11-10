package com.nexbox.core.proxy.record;

import android.content.Intent;

import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.compat.BundleCompat;

import static com.nexbox.core.utils.NexBoxIpcKeys.readKey;


public class ProxyPendingRecord {
    private static final String EXTRA_USER_ID = "nexbox.pending.userId";
    private static final String EXTRA_TARGET = "nexbox.pending.target";

    public int mUserId;
    public Intent mTarget;

    public ProxyPendingRecord(Intent target, int userId) {
        mUserId = userId;
        mTarget = target;
    }

    public static void saveStub(Intent shadow, Intent target, int userId) {
        NexBoxIpcKeys.removeExtras(shadow, NexBoxIpcKeys.LEGACY_PENDING_USER_ID, NexBoxIpcKeys.LEGACY_PENDING_TARGET);
        shadow.putExtra(EXTRA_USER_ID, userId);
        shadow.putExtra(EXTRA_TARGET, target);
    }

    public static ProxyPendingRecord create(Intent intent) {
        boolean current = NexBoxIpcKeys.hasAnyExtra(intent, EXTRA_USER_ID, EXTRA_TARGET);
        int userId = intent.getIntExtra(readKey(current, EXTRA_USER_ID, NexBoxIpcKeys.LEGACY_PENDING_USER_ID), 0);
        Intent target = BundleCompat.getParcelable(intent,
                readKey(current, EXTRA_TARGET, NexBoxIpcKeys.LEGACY_PENDING_TARGET), Intent.class);
        return new ProxyPendingRecord(target, userId);
    }

    @Override
    public String toString() {
        return "ProxyPendingActivityRecord{" +
                "mUserId=" + mUserId +
                ", mTarget=" + mTarget +
                '}';
    }
}
