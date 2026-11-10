package com.nexbox.core.utils.compat;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

/** Preserves the legacy signature view exposed by the virtual package manager. */
public final class PackageSignaturesCompat {
    @SuppressWarnings("deprecation")
    public static final int LEGACY_QUERY_FLAG = PackageManager.GET_SIGNATURES;

    private PackageSignaturesCompat() {
    }

    @SuppressWarnings("deprecation")
    public static Signature[] readLegacy(PackageInfo info) {
        return info == null ? null : info.signatures;
    }

    @SuppressWarnings("deprecation")
    public static void writeLegacy(PackageInfo info, Signature[] signatures) {
        info.signatures = signatures;
    }

    /**
     * Recreates the legacy view only for SigningInfo returned by the platform package manager.
     * Virtual SigningInfo has a different history representation and must keep its legacy field.
     */
    public static Signature[] platformLegacyView(SigningInfo info) {
        if (info == null) return null;
        if (info.hasMultipleSigners()) {
            Signature[] signers = info.getApkContentsSigners();
            return signers == null || signers.length == 0 ? null : signers.clone();
        }
        Signature[] history = info.getSigningCertificateHistory();
        return history == null || history.length == 0 ? null : new Signature[]{history[0]};
    }
}
