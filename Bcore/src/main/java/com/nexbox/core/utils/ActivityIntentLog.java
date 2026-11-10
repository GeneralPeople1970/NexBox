package com.nexbox.core.utils;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;

/** Bounded metadata only: link contents and extra values never enter the log message. */
public final class ActivityIntentLog {
    public static final String TAG = "ActivityIntent";
    private static final int MAX_KEYS = 24;
    private static final int MAX_DIGEST_CHARS = 16384;

    private ActivityIntentLog() { }

    public static void log(String stage, Intent intent, int userId, long generation,
                           boolean inspectExtraKeys, String detail) {
        Slog.dLazy(TAG, () -> "stage=" + stage + " userId=" + userId
                + (generation > 0 ? " generation=" + generation : "")
                + (detail.isEmpty() ? "" : " " + detail) + " " + describe(intent, inspectExtraKeys));
    }

    public static void failure(String stage, int userId, long generation, String reason) {
        try {
            Slog.w(TAG, "stage=" + stage + " userId=" + userId
                    + (generation > 0 ? " generation=" + generation : "") + " reason=" + reason);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostic failures must not replace the launch failure.
        }
    }

    private static String describe(Intent intent, boolean inspectExtraKeys) {
        if (intent == null) return "intent=missing";
        StringBuilder out = new StringBuilder(256);
        out.append("intentAction=").append(identifier(intent.getAction()))
                .append(" component=").append(intent.getComponent() == null ? "none"
                        : identifier(intent.getComponent().flattenToShortString()))
                .append(" intentPackage=").append(identifier(intent.getPackage()))
                .append(" type=").append(identifier(intent.getType()))
                .append(" flags=0x").append(Integer.toHexString(intent.getFlags()))
                .append(" categories=").append(describeNames(intent.getCategories(), false));
        appendUri(out, intent.getData());
        Intent selector = intent.getSelector();
        out.append(" selector=").append(selector != null);
        if (selector != null) {
            out.append(" selectorAction=").append(identifier(selector.getAction()))
                    .append(" selectorUriHash=").append(selector.getData() == null ? "none"
                            : fingerprint(selector.getData().toString()));
        }
        out.append(" clipItems=").append(intent.getClipData() == null ? 0 : intent.getClipData().getItemCount());
        if (!inspectExtraKeys) {
            // The core process does not have the originating app/module's Parcelable classes.
            out.append(" extras=opaque");
            return out.toString();
        }
        try {
            Bundle extras = intent.getExtras();
            int count = extras == null ? 0 : extras.size();
            String keys = extras == null ? "[]" : describeNames(extras.keySet(), true);
            out.append(" extraCount=").append(count).append(" extraKeys=").append(keys);
        } catch (RuntimeException | LinkageError unreadable) {
            out.append(" extras=unreadable");
        }
        return out.toString();
    }

    private static void appendUri(StringBuilder out, Uri uri) {
        if (uri == null) {
            out.append(" data=none scheme=none uriHash=none");
            return;
        }
        String value = uri.toString();
        out.append(" data=present scheme=").append(identifier(uri.getScheme()))
                .append(" uriHash=").append(fingerprint(value))
                .append(" uriLength=").append(value.length())
                .append(" opaque=").append(uri.isOpaque())
                .append(" hasAuthority=").append(uri.getEncodedAuthority() != null)
                .append(" hasPath=").append(uri.getEncodedPath() != null && !uri.getEncodedPath().isEmpty())
                .append(" hasQuery=").append(uri.isHierarchical() && uri.getEncodedQuery() != null)
                .append(" hasFragment=").append(uri.getEncodedFragment() != null);
    }

    private static String describeNames(Set<String> names, boolean extras) {
        if (names == null || names.isEmpty()) return "[]";
        if (names.size() > MAX_KEYS) return "omitted(count=" + names.size() + ")";
        ArrayList<String> described = new ArrayList<>(names.size());
        for (String name : names) {
            boolean platformName = name != null && name.startsWith(extras
                    ? "android.intent.extra." : "android.intent.category.");
            described.add(platformName ? identifier(name) : "custom#" + fingerprint(name));
        }
        Collections.sort(described);
        return described.toString();
    }

    private static String identifier(String value) {
        if (value == null) return "none";
        if (value.length() > 192 || value.contains("://")
                || !value.matches("[A-Za-z0-9_.$:/+*-]*")) return "<redacted>";
        return value;
    }

    private static String fingerprint(String value) {
        if (value == null) return "none";
        if (value.length() > MAX_DIGEST_CHARS) return "omitted";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[24];
            String hex = "0123456789abcdef";
            for (int i = 0; i < result.length / 2; i++) {
                result[i * 2] = hex.charAt((digest[i] >>> 4) & 15);
                result[i * 2 + 1] = hex.charAt(digest[i] & 15);
            }
            return new String(result);
        } catch (NoSuchAlgorithmException unavailable) {
            return "unavailable";
        }
    }
}
