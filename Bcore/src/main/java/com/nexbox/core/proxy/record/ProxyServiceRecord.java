package com.nexbox.core.proxy.record;

import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.compat.BundleCompat;

import static com.nexbox.core.utils.NexBoxIpcKeys.readKey;


public class ProxyServiceRecord {
    private static final String EXTRA_TARGET = "nexbox.service.target";
    private static final String EXTRA_SERVICE_INFO = "nexbox.service.info";
    private static final String EXTRA_USER_ID = "nexbox.service.userId";
    private static final String EXTRA_START_ID = "nexbox.service.startId";
    private static final String EXTRA_TOKEN = "nexbox.service.token";
    private static final String EXTRA_PROCESS_TOKEN = "nexbox.service.processToken";

    public Intent mServiceIntent;
    public ServiceInfo mServiceInfo;
    public IBinder mToken;
    public IBinder mProcessToken;
    public int mUserId;
    public int mStartId;

    public ProxyServiceRecord(Intent serviceIntent, ServiceInfo serviceInfo, IBinder token, int userId, int startId) {
        mServiceIntent = serviceIntent;
        mServiceInfo = serviceInfo;
        mUserId = userId;
        mStartId = startId;
        mToken = token;
    }

    public static void saveStub(Intent shadow, Intent target, ServiceInfo serviceInfo, IBinder token, int userId, int startId,
                                IBinder processToken) {
        NexBoxIpcKeys.removeExtras(shadow, NexBoxIpcKeys.LEGACY_TARGET, NexBoxIpcKeys.LEGACY_SERVICE_INFO,
                NexBoxIpcKeys.LEGACY_USER_ID, NexBoxIpcKeys.LEGACY_START_ID,
                NexBoxIpcKeys.LEGACY_TOKEN, NexBoxIpcKeys.LEGACY_PROCESS_TOKEN);
        shadow.putExtra(EXTRA_TARGET, target);
        shadow.putExtra(EXTRA_SERVICE_INFO, serviceInfo);
        shadow.putExtra(EXTRA_USER_ID, userId);
        shadow.putExtra(EXTRA_START_ID, startId);
        BundleCompat.putBinder(shadow, EXTRA_TOKEN, token);
        BundleCompat.putBinder(shadow, EXTRA_PROCESS_TOKEN, processToken);
    }

    public static ProxyServiceRecord create(Intent intent) {
        if (intent == null) return new ProxyServiceRecord(null, null, null, -1, 0);
        boolean current = NexBoxIpcKeys.hasAnyExtra(intent, EXTRA_TARGET, EXTRA_SERVICE_INFO, EXTRA_USER_ID,
                EXTRA_START_ID, EXTRA_TOKEN, EXTRA_PROCESS_TOKEN);
        Intent target = BundleCompat.getParcelable(intent,
                readKey(current, EXTRA_TARGET, NexBoxIpcKeys.LEGACY_TARGET), Intent.class);
        ServiceInfo serviceInfo = BundleCompat.getParcelable(intent,
                readKey(current, EXTRA_SERVICE_INFO, NexBoxIpcKeys.LEGACY_SERVICE_INFO), ServiceInfo.class);
        int userId = intent.getIntExtra(readKey(current, EXTRA_USER_ID, NexBoxIpcKeys.LEGACY_USER_ID), 0);
        int startId = intent.getIntExtra(readKey(current, EXTRA_START_ID, NexBoxIpcKeys.LEGACY_START_ID), 0);
        IBinder token = BundleCompat.getBinder(intent, readKey(current, EXTRA_TOKEN, NexBoxIpcKeys.LEGACY_TOKEN));
        ProxyServiceRecord record = new ProxyServiceRecord(target, serviceInfo, token, userId, startId);
        record.mProcessToken = BundleCompat.getBinder(intent, readKey(current, EXTRA_PROCESS_TOKEN, NexBoxIpcKeys.LEGACY_PROCESS_TOKEN));
        return record;
    }
}
