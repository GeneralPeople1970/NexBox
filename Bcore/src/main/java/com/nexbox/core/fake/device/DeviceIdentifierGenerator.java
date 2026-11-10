package com.nexbox.core.fake.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;

/** Local virtual identifiers, not a database of real subscribers or certified hardware IDs. */
public final class DeviceIdentifierGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String SERIAL_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private DeviceIdentifierGenerator() {}

    public static String androidId() {
        String value;
        do {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            value = hex(bytes, bytes.length);
        } while (!isValidAndroidId(value));
        return value;
    }

    public static String imei() {
        // Synthetic prefix only: this is NOT an allocated or model-verified GSMA TAC.
        return withImeiCheckDigit("99" + randomDigits(12));
    }

    /** For a future, separately verified model-to-TAC catalogue. */
    public static String imei(String tac) {
        if (tac == null || !tac.matches("[0-9]{8}") || "00000000".equals(tac)) {
            throw new IllegalArgumentException("TAC must contain eight digits and cannot be all zero");
        }
        return withImeiCheckDigit(tac + randomDigits(6));
    }

    public static String macAddress() {
        byte[] bytes = new byte[6];
        RANDOM.nextBytes(bytes);
        return formatLocalMac(bytes);
    }

    public static String serial() {
        StringBuilder value = new StringBuilder(12);
        for (int i = 0; i < 12; i++) value.append(SERIAL_CHARS.charAt(RANDOM.nextInt(SERIAL_CHARS.length())));
        return value.toString();
    }

    public static String oaid() {
        return UUID.randomUUID().toString();
    }

    public static boolean isValidAndroidId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{16}") && !value.equals("0000000000000000");
    }

    public static boolean isValidImei(String value) {
        return value != null && value.matches("[0-9]{15}") && !value.equals("000000000000000")
                && value.charAt(14) - '0' == imeiCheckDigit(value.substring(0, 14));
    }

    public static boolean isValidMac(String value) {
        if (value == null || !value.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) return false;
        return (Integer.parseInt(value.substring(0, 2), 16) & 1) == 0
                && !value.equalsIgnoreCase("00:00:00:00:00:00")
                && !value.equalsIgnoreCase("02:00:00:00:00:00");
    }

    public static boolean isValidSerial(String value) {
        return value != null && value.matches("[0-9A-Za-z]{1,64}") && !value.equalsIgnoreCase("unknown");
    }

    public static boolean isValidOaid(String value) {
        if (value == null) return false;
        boolean format = value.matches("(?i)[0-9a-f]{32}")
                || value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        return format && !value.replace("-", "").matches("0+");
    }

    // Domain-separated, deterministic repairs: reading an old/incomplete profile must not
    // generate a new identity on every call, process restart, or Parcelable reconstruction.
    public static String stableAndroidId(String seed) {
        String value = hex(digest("android-id", seed), 8);
        return value.equals("0000000000000000") ? "0000000000000001" : value;
    }

    public static String stableImei(String seed) {
        byte[] bytes = digest("imei", seed);
        StringBuilder value = new StringBuilder("99");
        for (int i = 0; i < 12; i++) value.append((bytes[i] & 0xff) % 10);
        return withImeiCheckDigit(value.toString());
    }

    public static String stableMac(String seed) {
        return formatLocalMac(digest("mac", seed));
    }

    public static String stableSerial(String seed) {
        return hex(digest("serial", seed), 6).toUpperCase(Locale.ROOT);
    }

    public static String stableOaid(String seed) {
        return UUID.nameUUIDFromBytes(digest("oaid", seed)).toString();
    }

    private static byte[] digest(String domain, String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    ("nexbox/" + domain + "/" + seed).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String randomDigits(int count) {
        StringBuilder value = new StringBuilder(count);
        for (int i = 0; i < count; i++) value.append(RANDOM.nextInt(10));
        return value.toString();
    }

    private static String withImeiCheckDigit(String base) {
        return base + imeiCheckDigit(base);
    }

    private static int imeiCheckDigit(String base) {
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int digit = base.charAt(i) - '0';
            if ((i & 1) == 1) digit *= 2;
            sum += digit / 10 + digit % 10;
        }
        return (10 - sum % 10) % 10;
    }

    private static String formatLocalMac(byte[] bytes) {
        bytes[0] = (byte) ((bytes[0] & 0xfc) | 0x02); // Locally administered, unicast.
        boolean placeholder = bytes[0] == 2;
        for (int i = 1; i < 6; i++) placeholder &= bytes[i] == 0;
        if (placeholder) bytes[5] = 1;
        StringBuilder value = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i != 0) value.append(':');
            int octet = bytes[i] & 0xff;
            value.append(HEX[octet >>> 4]).append(HEX[octet & 15]);
        }
        return value.toString().toUpperCase(Locale.ROOT);
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder value = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            int octet = bytes[i] & 0xff;
            value.append(HEX[octet >>> 4]).append(HEX[octet & 15]);
        }
        return value.toString();
    }
}
