package com.nexbox.core.core.system.accounts;

import android.os.BadParcelableException;
import android.os.Parcel;

/** Codec primitives for private, same-APK IPC; never used by accounts.conf. */
final class AccountVisibilityParcel {
    static final int INVALID_ENTRY_COUNT = -1;

    private AccountVisibilityParcel() {
    }

    static int readCount(Parcel source, int format, boolean allowInvalidEntry) {
        if (source.dataAvail() < 2 * Integer.BYTES || source.readInt() != format) {
            throw new BadParcelableException("Unsupported account visibility IPC format");
        }
        int count = source.readInt();
        if (allowInvalidEntry && count == INVALID_ENTRY_COUNT) return count;
        // Even null keys and values need two presence words. Bound allocation
        // by the received data, without imposing a new limit on valid visibility maps.
        if (count < 0 || count > source.dataAvail() / (2 * Integer.BYTES)) {
            throw new BadParcelableException("Invalid account visibility IPC entry count");
        }
        return count;
    }

    static Integer readVisibility(Parcel source) {
        if (source.dataAvail() < Integer.BYTES) {
            throw new BadParcelableException("Missing account visibility IPC value");
        }
        int present = source.readInt();
        if (present == 0) return null;
        if (present != 1 || source.dataAvail() < Integer.BYTES) {
            throw new BadParcelableException("Invalid account visibility IPC value");
        }
        return source.readInt();
    }

    static String readPackageName(Parcel source) {
        if (source.dataAvail() < Integer.BYTES) {
            throw new BadParcelableException("Missing account visibility IPC package");
        }
        int present = source.readInt();
        if (present == 0) return null;
        if (present != 1) {
            throw new BadParcelableException("Invalid account visibility IPC package marker");
        }
        // A separate presence bit prevents a failed platform string decode from
        // being accepted as a legitimate null key, without copying its wire layout.
        String packageName = source.readString();
        if (packageName == null) {
            throw new BadParcelableException("Invalid account visibility IPC package");
        }
        return packageName;
    }

    static void writePackageName(Parcel dest, String packageName) {
        dest.writeInt(packageName == null ? 0 : 1);
        if (packageName != null) dest.writeString(packageName);
    }

    static void writeVisibility(Parcel dest, Integer visibility) {
        dest.writeInt(visibility == null ? 0 : 1);
        if (visibility != null) dest.writeInt(visibility);
    }
}
