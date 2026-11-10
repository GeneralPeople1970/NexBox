package com.nexbox.core.fake.service;

import android.os.IBinder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Optional Android 15 accessors for the Google launch path; no generated field dependency. */
final class GoogleClientTransactionCompat {
    private GoogleClientTransactionCompat() { }

    static List<?> items(Object transaction) {
        Object value = read(transaction, "getTransactionItems", "mTransactionItems");
        return value instanceof List<?> ? (List<?>) value : null;
    }

    static IBinder activityToken(Object item) {
        Object value = read(item, "getActivityToken", "mActivityToken");
        return value instanceof IBinder ? (IBinder) value : null;
    }

    private static Object read(Object target, String accessor, String fieldName) {
        if (target == null) return null;
        try {
            Method method = target.getClass().getMethod(accessor);
            method.setAccessible(true);
            Object value = method.invoke(target);
            if (value != null) return value;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // ActivityTransactionItem owns the per-item token, not LaunchActivityItem.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
