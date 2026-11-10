package com.nexbox.core.fake.service;

import java.util.Arrays;

/** BlobStore caller slots are distinct from blob/session IDs, descriptions and access targets. */
final class BlobStoreCallPolicy {
    private static final String STRING = "java.lang.String";
    private static final String HANDLE = "android.app.blob.BlobHandle";
    private static final int INVALID_RES_ID = -1;

    interface DescriptionResolver {
        CharSequence resolve(int resourceId);
    }

    private BlobStoreCallPolicy() { }

    static Object[] rewrite(String method, String[] types, Object[] args, String guestPackage,
            String hostPackage, DescriptionResolver descriptions) {
        if (args == null || types == null || types.length != args.length
                || guestPackage == null || guestPackage.isEmpty() || guestPackage.equals(hostPackage)) {
            return args;
        }
        int packageIndex = callingPackageIndex(method, types);
        if (packageIndex < 0 || !guestPackage.equals(args[packageIndex])) return args;
        if (hostPackage == null || hostPackage.isEmpty()) {
            throw new IllegalStateException("BlobStore host identity is unavailable");
        }

        Object[] mapped = args.clone();
        mapped[packageIndex] = hostPackage;
        if ("acquireLease".equals(method)) {
            // system_server resolves this resource in the calling package. Preserve the
            // guest description as text instead of resolving a guest ID in host resources.
            int resourceId = (Integer) args[1];
            if (args[2] != null) {
                mapped[1] = INVALID_RES_ID;
            } else if (resourceId != INVALID_RES_ID) {
                CharSequence description = descriptions.resolve(resourceId);
                if (description == null) throw new IllegalArgumentException("Lease description is unavailable");
                mapped[1] = INVALID_RES_ID;
                mapped[2] = description;
            }
        }
        return mapped;
    }

    private static int callingPackageIndex(String method, String[] types) {
        // Android 11+ IBlobStoreManager uses a calling package and the implicit Binder UID.
        // There is no standalone UID/AttributionSource slot; never guess at vendor additions.
        switch (method) {
            case "getLeasedBlobs":
            case "getRemainingLeaseQuotaBytes":
            case "releaseAllLeases":
                return matches(types, STRING) ? 0 : -1;
            case "createSession":
            case "openBlob":
            case "releaseLease":
            case "getLeaseInfo":
                return matches(types, HANDLE, STRING) ? 1 : -1;
            case "openSession":
            case "abandonSession":
                return matches(types, "long", STRING) ? 1 : -1;
            case "acquireLease":
                return matches(types, HANDLE, "int", "java.lang.CharSequence", "long", STRING) ? 4 : -1;
            default:
                // Administrative APIs and IBlobStoreSession access-target packages are not callers.
                return -1;
        }
    }

    private static boolean matches(String[] actual, String... expected) {
        return Arrays.equals(actual, expected);
    }
}
