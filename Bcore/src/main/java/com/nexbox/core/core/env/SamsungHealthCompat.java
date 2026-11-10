package com.nexbox.core.core.env;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.RemoteException;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.IBUserManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.utils.Slog;

import java.util.Map;

public final class SamsungHealthCompat {
    private static final String PREFS_NAME = "CompatRules";
    private static final String KEY_HOST_SAMSUNG_ACCOUNT_FALLBACK_PREFIX = "shealth_host_sa_fallback_";
    private static final String KEY_LAST_SASDK_REDIRECT_USER_ID = "shealth_last_sasdk_redirect_user_id";
    private static final String KEY_LAST_SASDK_REDIRECT_GENERATION = "shealth_last_sasdk_redirect_generation";
    private static final String TAG = "SamsungHealthCompat";

    private SamsungHealthCompat() {
    }

    private static SharedPreferences prefs() {
        Context context = NexBoxCore.getContext();
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean isHostSamsungAccountFallbackEnabled(int userId, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        try {
            return prefs().getBoolean(keyFor(userId, packageName), false);
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void setHostSamsungAccountFallbackEnabled(int userId, String packageName, boolean enabled) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return;
        }
        try {
            prefs().edit().putBoolean(keyFor(userId, packageName), enabled).commit();
        } catch (Throwable ignored) {
        }
    }

    public static void setLastSaSdkRedirectUserId(int userId) {
        try {
            AppConfig caller = BActivityThread.getAppConfig();
            if (caller == null || caller.userId != userId || caller.userGeneration <= 0L
                    || !"com.sec.android.app.shealth".equals(caller.packageName)) {
                throw new IllegalStateException("Samsung Health redirect has no valid originating process");
            }
            requireCurrentUser(userId, caller.userGeneration);
            if (!redirectPrefs().edit().putInt(KEY_LAST_SASDK_REDIRECT_USER_ID, userId)
                    .putLong(KEY_LAST_SASDK_REDIRECT_GENERATION, caller.userGeneration).commit()) {
                throw new IllegalStateException("Samsung Health redirect owner could not be saved");
            }
            Slog.dLazy(TAG, () -> "action=saveRedirectOwner stage=saved userId=" + userId
                    + " generation=" + caller.userGeneration);
        } catch (RuntimeException failure) {
            Slog.w(TAG, "action=saveRedirectOwner stage=failed userId=" + userId
                    + " error=" + failure.getClass().getSimpleName());
            throw failure;
        }
    }

    public static int getLastSaSdkRedirectUserId() {
        return getLastSaSdkRedirectTarget().id;
    }

    /** One saved snapshot prevents mixing the ID and generation of two different flows. */
    public static BUserInfo getLastSaSdkRedirectTarget() {
        Map<String, ?> saved = redirectPrefs().getAll();
        Object id = saved.get(KEY_LAST_SASDK_REDIRECT_USER_ID);
        Object generation = saved.get(KEY_LAST_SASDK_REDIRECT_GENERATION);
        if (!(id instanceof Integer) || !(generation instanceof Long)) {
            Slog.w(TAG, "action=resolveRedirectOwner stage=rejected reason=generation_missing");
            throw new IllegalStateException("Samsung Health redirect has no captured user generation");
        }
        return requireCurrentUser((Integer) id, (Long) generation);
    }

    private static BUserInfo requireCurrentUser(int userId, long generation) {
        if (userId < 0 || generation <= 0L) {
            throw new IllegalStateException("Samsung Health redirect owner is invalid");
        }
        IBUserManagerService service = BUserManager.get().getService();
        if (service == null) throw new IllegalStateException("User service unavailable for Samsung Health redirect");
        try {
            BUserInfo user = service.getUserInfo(userId);
            if (user == null || user.createTime != generation) {
                Slog.w(TAG, "action=validateRedirectOwner stage=rejected userId=" + userId
                        + " generation=" + generation + " reason=user_generation_changed");
                throw new IllegalStateException("Samsung Health redirect user generation changed");
            }
            return user;
        } catch (RemoteException failure) {
            throw new IllegalStateException("Samsung Health redirect owner could not be verified", failure);
        }
    }

    // This existing compatibility file is written by the guest and read by the host. Reload
    // committed values before taking the owner snapshot; no old ID-only record is upgraded.
    @SuppressWarnings("deprecation")
    private static SharedPreferences redirectPrefs() {
        return NexBoxCore.getContext().getSharedPreferences(PREFS_NAME,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    private static String keyFor(int userId, String packageName) {
        return KEY_HOST_SAMSUNG_ACCOUNT_FALLBACK_PREFIX + userId + "_" + packageName;
    }
}
