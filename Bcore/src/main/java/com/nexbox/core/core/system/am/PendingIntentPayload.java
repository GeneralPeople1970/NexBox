package com.nexbox.core.core.system.am;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;

import java.util.UUID;

import black.android.os.BRParcel;

/** Keeps Binder objects in a Parcel-backed snapshot instead of losing them in disk encoding. */
final class PendingIntentPayload {
    private static final int MAX_BYTES = 512 * 1024;

    final String encoded;
    final String runtimeId;
    final int byteCount;
    private final Bundle runtimeSnapshot;

    private PendingIntentPayload(String encoded, Bundle runtimeSnapshot, int byteCount) {
        this.encoded = encoded;
        this.runtimeSnapshot = runtimeSnapshot;
        this.runtimeId = runtimeSnapshot == null ? null : UUID.randomUUID().toString();
        this.byteCount = byteCount;
    }

    static PendingIntentPayload capture(Bundle request) {
        if (request == null) throw new IllegalArgumentException("Missing PendingIntent payload");
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(request);
            if (parcel.hasFileDescriptors()) {
                throw new IllegalArgumentException("PendingIntent payload contains file descriptors");
            }
            if (parcel.dataSize() > MAX_BYTES) {
                throw new IllegalArgumentException("PendingIntent payload is too large");
            }
            int byteCount = parcel.dataSize();
            if (BRParcel.getWithException(parcel).hasBinders()) {
                // This also detects binders inside opaque/nested guest Parcelables. Reading a
                // Bundle copies the Parcel object table; marshall() cannot preserve that table.
                parcel.setDataPosition(0);
                return new PendingIntentPayload(null, readBundle(parcel), byteCount);
            }
            return new PendingIntentPayload(Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP),
                    null, byteCount);
        } finally {
            parcel.recycle();
        }
    }

    Bundle copy() {
        if (encoded != null) return decode(encoded);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(runtimeSnapshot);
            parcel.setDataPosition(0);
            return readBundle(parcel);
        } finally {
            parcel.recycle();
        }
    }

    static Bundle decode(String encoded) {
        byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("PendingIntent payload is too large");
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            return readBundle(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static Bundle readBundle(Parcel parcel) {
        Bundle bundle = parcel.readBundle(Intent.class.getClassLoader());
        if (bundle == null) throw new IllegalArgumentException("Missing PendingIntent payload");
        return bundle;
    }
}
