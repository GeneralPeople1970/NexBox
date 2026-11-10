package com.nexbox.core.media;

import com.nexbox.core.utils.MutationReceipt.Rejected;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.zip.CRC32;

/** Bounded, owned bytes: no source path is opened after validation. */
public final class VerifiedPng {
    public static final int MAX_BYTES = 65536;
    public final int width;
    public final int height;
    public final String sha256;
    private final byte[] bytes;

    private VerifiedPng(byte[] bytes, int width, int height, String sha256) {
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
    }

    public byte[] copyBytes() { return bytes.clone(); }

    public static VerifiedPng decode(String encoded, long length, String expectedSha256, String mime) {
        if (encoded == null || encoded.length() > 4 * ((MAX_BYTES + 2) / 3) || length > MAX_BYTES) {
            throw new Rejected("source_too_large");
        }
        if (length < 1 || !"image/png".equals(mime) || expectedSha256 == null
                || !expectedSha256.matches("[0-9a-fA-F]{64}")) throw new Rejected("source_mismatch");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException invalid) { throw new Rejected("source_mismatch"); }
        if (bytes.length != length || !digest(bytes).equalsIgnoreCase(expectedSha256)) throw new Rejected("source_mismatch");
        if (bytes.length < 45 || ByteBuffer.wrap(bytes).getLong() != 0x89504e470d0a1a0aL) throw new Rejected("source_mismatch");
        ByteBuffer png = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        png.position(8);
        int width = 0;
        int height = 0;
        boolean header = false;
        boolean data = false;
        boolean end = false;
        while (png.remaining() >= 12) {
            int count = png.getInt();
            if (count < 0 || count > png.remaining() - 8) throw new Rejected("source_mismatch");
            int typeOffset = png.position();
            byte[] typeBytes = new byte[4];
            png.get(typeBytes);
            String type = new String(typeBytes, StandardCharsets.US_ASCII);
            if (!type.matches("[A-Za-z]{4}")) throw new Rejected("source_mismatch");
            if (!header && !"IHDR".equals(type)) throw new Rejected("source_mismatch");
            if ("IHDR".equals(type)) {
                if (header || count != 13) throw new Rejected("source_mismatch");
                width = png.getInt(png.position());
                height = png.getInt(png.position() + 4);
                if (width <= 0 || height <= 0) throw new Rejected("source_mismatch");
                if (width > 1024 || height > 1024 || (long) width * height > 1_048_576) throw new Rejected("source_too_large");
                header = true;
            } else if ("IDAT".equals(type)) data = true;
            else if ("IEND".equals(type)) {
                if (!data || count != 0) throw new Rejected("source_mismatch");
                end = true;
            } else if ("acTL".equals(type) || "fcTL".equals(type) || "fdAT".equals(type)) {
                throw new Rejected("source_mismatch");
            }
            CRC32 crc = new CRC32();
            crc.update(bytes, typeOffset, count + 4);
            png.position(png.position() + count);
            if ((png.getInt() & 0xffffffffL) != crc.getValue()) throw new Rejected("source_mismatch");
            if (end) break;
        }
        if (!end || png.hasRemaining()) throw new Rejected("source_mismatch");
        return new VerifiedPng(bytes, width, height, digest(bytes));
    }

    public static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash) hex.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
