package com.nexbox.core.core.system.accounts;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Typed package-to-visibility IPC payload, separate from BAccount persistence. */
public final class PackageVisibilityMap implements Parcelable {
    private static final int PARCEL_FORMAT = 0x4e425001;

    // Both null denotes an invalid-entry marker; a null map uses a null AIDL payload.
    private final Map<String, Integer> values;
    private final Map<?, ?> suppliedValues;

    /** Takes ownership of a result snapshot, never the live account registry map. */
    PackageVisibilityMap(Map<String, Integer> values) {
        this(Objects.requireNonNull(values), null);
    }

    private PackageVisibilityMap(Map<String, Integer> values, Map<?, ?> suppliedValues) {
        this.values = values;
        this.suppliedValues = suppliedValues;
    }

    private PackageVisibilityMap(Parcel source) {
        suppliedValues = null;
        int count = AccountVisibilityParcel.readCount(source, PARCEL_FORMAT, true);
        if (count == AccountVisibilityParcel.INVALID_ENTRY_COUNT) {
            values = null;
            return;
        }
        // The former AIDL readHashMap used this capacity and insertion sequence.
        values = new HashMap<>(count);
        for (int index = 0; index < count; index++) {
            String packageName = AccountVisibilityParcel.readPackageName(source);
            values.put(packageName, AccountVisibilityParcel.readVisibility(source));
        }
    }

    public static PackageVisibilityMap fromMap(Map<?, ?> supplied) {
        // Defer traversal to the former Map marshalling boundary (or, for a local
        // service call, until toMap after its caller/owner/generation checks).
        return supplied == null ? null : new PackageVisibilityMap(null, supplied);
    }

    private static Map<String, Integer> copySuppliedValues(Map<?, ?> supplied) {
        Map<String, Integer> snapshot = new LinkedHashMap<>(supplied.size());
        for (Map.Entry<?, ?> entry : supplied.entrySet()) {
            Object packageName = entry.getKey();
            Object visibility = entry.getValue();
            if ((packageName != null && !(packageName instanceof String))
                    || (visibility != null && !(visibility instanceof Integer))) {
                // The service must still enforce caller/owner/generation before failing
                // the old checked-map boundary. Do not marshal an unknown guest value.
                return null;
            }
            snapshot.put((String) packageName, (Integer) visibility);
        }
        return snapshot;
    }

    public Map<String, Integer> toMap() {
        Map<String, Integer> result = suppliedValues == null ? values : copySuppliedValues(suppliedValues);
        if (result == null) {
            // The service calls this after caller/owner and user-state checks. Keep this
            // failure Binder-encodable; ClassCastException cannot cross that boundary.
            throw new IllegalArgumentException("Invalid account visibility entry type");
        }
        return result;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        Map<String, Integer> snapshot = suppliedValues == null ? values : copySuppliedValues(suppliedValues);
        dest.writeInt(PARCEL_FORMAT);
        if (snapshot == null) {
            dest.writeInt(AccountVisibilityParcel.INVALID_ENTRY_COUNT);
            return;
        }
        dest.writeInt(snapshot.size());
        for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
            AccountVisibilityParcel.writePackageName(dest, entry.getKey());
            AccountVisibilityParcel.writeVisibility(dest, entry.getValue());
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<PackageVisibilityMap> CREATOR = new Creator<PackageVisibilityMap>() {
        @Override
        public PackageVisibilityMap createFromParcel(Parcel source) {
            return new PackageVisibilityMap(source);
        }

        @Override
        public PackageVisibilityMap[] newArray(int size) {
            return new PackageVisibilityMap[size];
        }
    };
}
