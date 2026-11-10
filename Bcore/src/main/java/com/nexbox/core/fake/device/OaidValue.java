package com.nexbox.core.fake.device;

/** Substitute only a successful, nonzero vendor result; do not turn opt-out into an identity. */
public final class OaidValue {
    private OaidValue() {}

    public static String replace(String original) {
        // Only the UUID/32-hex formats represented by the profile are supported. Preserve
        // error strings, zero IDs and unknown future encodings instead of inventing success.
        if (!DeviceIdentifierGenerator.isValidOaid(original)) {
            return original;
        }
        String compact = DeviceSpoofManager.getOaidForCurrentUser().replace("-", "");
        if (original.matches("(?i)[0-9a-f]{32}")) return compact;
        return compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20);
    }
}
