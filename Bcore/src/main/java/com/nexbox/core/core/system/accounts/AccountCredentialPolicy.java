package com.nexbox.core.core.system.accounts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** Internal metadata uses the existing account map format; never expose it through user-data APIs. */
final class AccountCredentialPolicy {
    static final String PREFIX = "com.nexbox.internal.account.";
    static final String OWNER_PACKAGE = PREFIX + "owner.package";
    static final String OWNER_SIGNER = PREFIX + "owner.signer";
    private static final String VISIBILITY = PREFIX + "visibility.";
    private static final String GRANT = PREFIX + "grant.";

    private AccountCredentialPolicy() { }

    static boolean isInternal(String key) {
        return key != null && key.startsWith(PREFIX);
    }

    static String visibilityKey(String packageName) {
        return VISIBILITY + packageName;
    }

    static String grantKey(String packageName, String tokenType) {
        return GRANT + packageName + ":" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tokenType.getBytes(StandardCharsets.UTF_8));
    }

    static void removePackageGrants(Map<String, String> metadata, String packageName) {
        metadata.keySet().removeIf(key -> key.equals(visibilityKey(packageName))
                || key.startsWith(GRANT + packageName + ":"));
    }
}
