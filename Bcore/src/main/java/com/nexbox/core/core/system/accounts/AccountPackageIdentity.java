package com.nexbox.core.core.system.accounts;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.text.TextUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.utils.compat.PackageSignaturesCompat;

final class AccountPackageIdentity {
    private final BPackageManagerService mPms;

    AccountPackageIdentity(BPackageManagerService pms) {
        mPms = pms;
    }

    boolean sameAccountSigner(String first, String second, int userId) {
        if (TextUtils.isEmpty(first) || TextUtils.isEmpty(second)) return false;
        PackageInfo a = mPms.getPackageInfo(first, PackageSignaturesCompat.LEGACY_QUERY_FLAG, userId);
        PackageInfo b = mPms.getPackageInfo(second, PackageSignaturesCompat.LEGACY_QUERY_FLAG, userId);
        Signature[] firstSignatures = PackageSignaturesCompat.readLegacy(a);
        Signature[] secondSignatures = PackageSignaturesCompat.readLegacy(b);
        return a != null && b != null && firstSignatures != null && firstSignatures.length > 0
                && secondSignatures != null && Arrays.equals(firstSignatures, secondSignatures);
    }

    String accountSigner(String packageName, int userId) {
        PackageInfo info = mPms.getPackageInfo(packageName, PackageSignaturesCompat.LEGACY_QUERY_FLAG, userId);
        Signature[] signatures = PackageSignaturesCompat.readLegacy(info);
        if (info == null || signatures == null || signatures.length == 0) {
            throw new SecurityException("Account package has no verified signer");
        }
        try {
            List<String> signers = new ArrayList<>();
            for (Signature signature : signatures) {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder text = new StringBuilder(hash.length * 2);
                for (byte value : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
                signers.add(text.toString());
            }
            Collections.sort(signers);
            return TextUtils.join(":", signers);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
