package com.nexbox.core.utils.compat;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.Serializable;
import java.util.HashMap;

public final class ParcelCompat {
    private ParcelCompat() {
    }

    /**
     * Keep the stored class-name format, caller's loader and decode-before-cast failure behavior.
     * Typed readers can reject a class before reading its payload and impose stricter CREATOR
     * layout requirements, including for inherited creators in stored PackageParser intent filters.
     */
    @SuppressWarnings("deprecation")
    public static <T extends Parcelable> T readParcelable(Parcel source, ClassLoader loader,
                                                        Class<T> type) {
        return type.cast(source.readParcelable(loader));
    }

    /**
     * Decode the entire stored writeMap format before the caller validates entries and trailing
     * bytes. Typed readers reject mismatched entries before the caller's UID validation can run.
     */
    @SuppressWarnings("deprecation")
    public static HashMap<?, ?> readHashMap(Parcel source, ClassLoader loader) {
        return source.readHashMap(loader);
    }

    /**
     * Core-owned callers share this loader; legacy deserialization chooses the nearest user
     * loader on the stack. Leave the caller's original casts after decoding the complete value.
     */
    @SuppressWarnings("deprecation")
    public static Serializable readSerializable(Parcel source) {
        return source.readSerializable();
    }
}
