package com.nexbox.core.utils;

import android.content.Intent;

/** Internal wire names; legacy keys are read only for previously issued transports. */
public final class NexBoxIpcKeys {
    public static final String SYSTEM_SERVICE_NAME = "nexbox.system.serviceName";
    public static final String SYSTEM_SERVICE_BINDER = "nexbox.system.service";
    public static final String ACCOUNT_USER_ID = "nexbox.account.userId";
    public static final String CLASS_EXTRA_PREFIX = "nexbox.class.extra.";

    public static final String LEGACY_USER_ID = "_B_|_user_id_";
    public static final String LEGACY_TARGET = "_B_|_target_";
    public static final String LEGACY_ACTIVITY_INFO = "_B_|_activity_info_";
    public static final String LEGACY_ACTIVITY_RECORD = "_B_|_activity_record_v_";
    public static final String LEGACY_SERVICE_INFO = "_B_|_service_info_";
    public static final String LEGACY_START_ID = "_B_|_start_id_";
    public static final String LEGACY_TOKEN = "_B_|_token_";
    public static final String LEGACY_PROCESS_TOKEN = "_B_|_process_token_";
    public static final String LEGACY_PENDING_USER_ID = "_B_|_P_user_id_";
    public static final String LEGACY_PENDING_TARGET = "_B_|_P_target_";
    public static final String LEGACY_ACCOUNT_USER_ID = "_B_|_UserId";
    public static final String LEGACY_CLASS_EXTRA_PREFIX = "_B_|_class_extra_|";

    private NexBoxIpcKeys() { }

    /** Select one format for the whole record, never mix current and legacy identity fields. */
    public static boolean hasAnyExtra(Intent intent, String... keys) {
        if (intent == null) return false;
        for (String key : keys) {
            if (intent.hasExtra(key)) return true;
        }
        return false;
    }

    public static String readKey(boolean current, String currentKey, String legacyKey) {
        return current ? currentKey : legacyKey;
    }

    public static void removeExtras(Intent intent, String... keys) {
        for (String key : keys) intent.removeExtra(key);
    }
}
