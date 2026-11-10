package com.nexbox.core.utils.compat;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;

import java.util.ArrayList;

public class BundleCompat {
    /**
     * Heterogeneous IPC payloads need the original value before the caller can validate it.
     * Android has no typed replacement that preserves unknown keys and wrong-type failures.
     */
    @SuppressWarnings("deprecation")
    public static Object getValue(Bundle bundle, String key) {
        return bundle.get(key);
    }

    /**
     * Retain the caller's class loader and fail on a wrong Parcelable subtype, as an assignment
     * from the legacy getter did. Platform typed getters instead return null on a type mismatch
     * and impose additional CREATOR constraints, including on releases after Android 13.
     */
    @SuppressWarnings("deprecation")
    public static <T extends Parcelable> T getParcelable(Bundle bundle, String key, Class<T> type) {
        return type.cast(bundle.getParcelable(key));
    }

    /** Uses the same decoding and mismatch behavior as the Bundle overload. */
    @SuppressWarnings("deprecation")
    public static <T extends Parcelable> T getParcelable(Intent intent, String key, Class<T> type) {
        return type.cast(intent.getParcelableExtra(key));
    }

    /**
     * Leave per-element casting or filtering to the caller. Typed collection getters can reject
     * an entire mixed collection and only check element types while unparcelling it.
     */
    @SuppressWarnings("deprecation")
    public static Parcelable[] getParcelableArray(Bundle bundle, String key) {
        return bundle.getParcelableArray(key);
    }

    /** Preserves mixed elements, nulls and order for the caller's existing array policy. */
    @SuppressWarnings("deprecation")
    public static Parcelable[] getParcelableArray(Intent intent, String key) {
        return intent.getParcelableArrayExtra(key);
    }

    /** The caller checks each item when accessing it; no unchecked generic list cast is exposed. */
    @SuppressWarnings("deprecation")
    public static ArrayList<?> getParcelableArrayList(Bundle bundle, String key) {
        return bundle.getParcelableArrayList(key);
    }

    public static IBinder getBinder(Bundle bundle, String key) {
        return bundle.getBinder(key);
    }

    public static void putBinder(Bundle bundle, String key, IBinder value) {
        bundle.putBinder(key, value);
    }

    public static void putBinder(Intent intent, String key, IBinder value) {
        Bundle bundle = new Bundle();
        putBinder(bundle, "binder", value);
        intent.putExtra(key, bundle);
    }

    public static IBinder getBinder(Intent intent, String key) {
        Bundle bundle = intent.getBundleExtra(key);
        if (bundle != null) {
            return getBinder(bundle, "binder");
        }
        return null;
    }
}
