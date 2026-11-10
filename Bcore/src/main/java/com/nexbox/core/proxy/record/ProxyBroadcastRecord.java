package com.nexbox.core.proxy.record;

import android.content.Intent;

import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.compat.BundleCompat;

import static com.nexbox.core.utils.NexBoxIpcKeys.readKey;


public class ProxyBroadcastRecord {
    private static final String EXTRA_TARGET = "nexbox.broadcast.target";
    private static final String EXTRA_USER_ID = "nexbox.broadcast.userId";

    public Intent mIntent;
    public int mUserId;

    public ProxyBroadcastRecord(Intent intent, int userId) {
        mIntent = intent;
        mUserId = userId;
    }

    public static void saveStub(Intent shadow, Intent target, int userId) {
        NexBoxIpcKeys.removeExtras(shadow, NexBoxIpcKeys.LEGACY_TARGET, NexBoxIpcKeys.LEGACY_USER_ID);
        shadow.putExtra(EXTRA_TARGET, target);
        shadow.putExtra(EXTRA_USER_ID, userId);
    }

    public static ProxyBroadcastRecord create(Intent intent) {
        boolean current = NexBoxIpcKeys.hasAnyExtra(intent, EXTRA_TARGET, EXTRA_USER_ID);
        Intent target = BundleCompat.getParcelable(intent,
                readKey(current, EXTRA_TARGET, NexBoxIpcKeys.LEGACY_TARGET), Intent.class);
        int userId = intent.getIntExtra(readKey(current, EXTRA_USER_ID, NexBoxIpcKeys.LEGACY_USER_ID), 0);
        return new ProxyBroadcastRecord(target, userId);
    }

    @Override
    public String toString() {
        return "ProxyBroadcastRecord{" +
                "mIntent=" + mIntent +
                ", mUserId=" + mUserId +
                '}';
    }
}
