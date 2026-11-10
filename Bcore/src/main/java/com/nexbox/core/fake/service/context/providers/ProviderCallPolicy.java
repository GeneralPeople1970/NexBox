package com.nexbox.core.fake.service.context.providers;

import java.util.Arrays;

/** Exact IContentProvider identity slots; URI, SQL, RPC names and Bundle values are payloads. */
final class ProviderCallPolicy {
    static final String SOURCE = "android.content.AttributionSource";
    private static final String STRING = "java.lang.String";
    private static final String STRINGS = "[Ljava.lang.String;";
    private static final String URI = "android.net.Uri";
    private static final String BUNDLE = "android.os.Bundle";
    private static final String VALUES = "android.content.ContentValues";
    private static final String VALUES_ARRAY = "[Landroid.content.ContentValues;";
    private static final String CANCELLATION = "android.os.ICancellationSignal";
    private static final String CALLBACK = "android.os.RemoteCallback";
    private static final String BINDER = "android.os.IBinder";
    private static final String OPERATIONS = "java.util.ArrayList";

    enum Kind { NONE, ATTRIBUTION_SOURCE, CALLING_PACKAGE }

    static final class Layout {
        static final Layout NONE = new Layout(Kind.NONE, -1, -1, -1);
        final Kind kind;
        final int attributionTag;
        final int authority;
        final int callMethod;

        Layout(Kind kind, int attributionTag, int authority, int callMethod) {
            this.kind = kind;
            this.attributionTag = attributionTag;
            this.authority = authority;
            this.callMethod = callMethod;
        }
    }

    interface SourceCopy {
        Object copy(Object original, String callerPackage, int callerUid);
    }

    private ProviderCallPolicy() { }

    static boolean isOneway(String name, String[] types) {
        if ("getTypeAnonymousAsync".equals(name)) return matches(types, URI, CALLBACK);
        if (!"getTypeAsync".equals(name) && !"canonicalizeAsync".equals(name)
                && !"uncanonicalizeAsync".equals(name)) return false;
        return layout(name, types).kind != Kind.NONE || isLegacyGetTypeAsync(name, types);
    }

    static boolean isLegacyGetTypeAsync(String name, String[] types) {
        return "getTypeAsync".equals(name) && matches(types, URI, CALLBACK);
    }

    static Object[] rewrite(String name, String[] types, Object[] original, String callerPackage,
            int callerUid, SourceCopy copySource) {
        return rewrite(name, types, original, callerPackage, callerUid, false, copySource);
    }

    static Object[] rewriteGuest(String name, String[] types, Object[] original, String callerPackage,
            int callerUid, SourceCopy copySource) {
        return rewrite(name, types, original, callerPackage, callerUid, true, copySource);
    }

    private static Object[] rewrite(String name, String[] types, Object[] original, String callerPackage,
            int callerUid, boolean preserveMatchingTag, SourceCopy copySource) {
        if (original == null) return null;
        Object[] mapped = original.clone();
        if (types.length != mapped.length) return mapped;
        Layout layout = layout(name, types);
        if (layout.kind == Kind.NONE) return mapped;
        if (callerPackage == null || callerPackage.isEmpty() || callerUid < 0) {
            throw new IllegalStateException("Provider caller identity is unavailable");
        }
        if (layout.kind == Kind.ATTRIBUTION_SOURCE) {
            // Null is left to the real provider's validation, never turned into a fabricated source.
            if (mapped[0] != null) mapped[0] = copySource.copy(mapped[0], callerPackage, callerUid);
        } else {
            boolean samePackage = callerPackage.equals(mapped[0]);
            mapped[0] = callerPackage;
            if (layout.attributionTag >= 0 && (!preserveMatchingTag || !samePackage)) {
                mapped[layout.attributionTag] = null;
            }
        }
        return mapped;
    }

    static Layout layout(String name, String[] types) {
        if (types == null || types.length == 0) return Layout.NONE;
        if (SOURCE.equals(types[0])) {
            String[] tail = Arrays.copyOfRange(types, 1, types.length);
            if (modernTail(name, tail)) {
                return new Layout(Kind.ATTRIBUTION_SOURCE, -1,
                        "call".equals(name) || "applyBatch".equals(name) ? 1 : -1,
                        "call".equals(name) ? 2 : -1);
            }
            return Layout.NONE;
        }
        if (!STRING.equals(types[0])) return Layout.NONE;

        // API 30 uses (callingPackage, attributionTag, ...). Do not infer a tag from
        // adjacent Strings: the four-argument legacy call's second String is its method.
        if (types.length >= 2 && STRING.equals(types[1])) {
            String[] tail = Arrays.copyOfRange(types, 2, types.length);
            if (taggedLegacyTail(name, tail)) {
                return new Layout(Kind.CALLING_PACKAGE, 1,
                        "call".equals(name) || "applyBatch".equals(name) ? 2 : -1,
                        "call".equals(name) ? 3 : -1);
            }
        }

        String[] tail = Arrays.copyOfRange(types, 1, types.length);
        // Deprecated bridges retained by Android 12-15, plus the pre-tag IPC shapes.
        if (untaggedLegacyTail(name, tail)) {
            boolean authorityPresent = "call".equals(name) && tail.length == 4;
            return new Layout(Kind.CALLING_PACKAGE, -1,
                    "applyBatch".equals(name) || authorityPresent ? 1 : -1,
                    "call".equals(name) ? (authorityPresent ? 2 : 1) : -1);
        }
        return Layout.NONE;
    }

    private static boolean modernTail(String name, String[] types) {
        switch (name) {
            case "query": return matches(types, URI, STRINGS, BUNDLE, CANCELLATION);
            case "insert": return matches(types, URI, VALUES, BUNDLE);
            case "bulkInsert": return matches(types, URI, VALUES_ARRAY);
            case "delete": return matches(types, URI, BUNDLE);
            case "update": return matches(types, URI, VALUES, BUNDLE);
            case "openFile": return matches(types, URI, STRING, CANCELLATION)
                    || matches(types, URI, STRING, CANCELLATION, BINDER);
            case "openAssetFile": return matches(types, URI, STRING, CANCELLATION);
            case "openTypedAssetFile": return matches(types, URI, STRING, BUNDLE, CANCELLATION);
            case "applyBatch": return matches(types, STRING, OPERATIONS);
            case "call": return matches(types, STRING, STRING, STRING, BUNDLE);
            case "checkUriPermission": return matches(types, URI, "int", "int");
            case "canonicalize": case "uncanonicalize": case "getType":
                return matches(types, URI);
            case "canonicalizeAsync": case "uncanonicalizeAsync": case "getTypeAsync":
                return matches(types, URI, CALLBACK);
            case "refresh": return matches(types, URI, BUNDLE, CANCELLATION);
            case "getStreamTypes": return matches(types, URI, STRING);
            default: return false;
        }
    }

    private static boolean taggedLegacyTail(String name, String[] types) {
        // API 30 getType/getStreamTypes have no calling identity at all.
        if ("getType".equals(name) || "getTypeAsync".equals(name) || "getStreamTypes".equals(name)) {
            return false;
        }
        return modernTail(name, types);
    }

    private static boolean untaggedLegacyTail(String name, String[] types) {
        switch (name) {
            case "query": return matches(types, URI, STRINGS, BUNDLE, CANCELLATION)
                    || matches(types, URI, STRINGS, STRING, STRINGS, STRING, CANCELLATION);
            case "insert": return matches(types, URI, VALUES);
            case "bulkInsert": return matches(types, URI, VALUES_ARRAY);
            case "delete": return matches(types, URI, STRING, STRINGS);
            case "update": return matches(types, URI, VALUES, STRING, STRINGS);
            case "call": return matches(types, STRING, STRING, BUNDLE)
                    || matches(types, STRING, STRING, STRING, BUNDLE);
            case "applyBatch": return matches(types, STRING, OPERATIONS);
            case "openFile": return matches(types, URI, STRING, CANCELLATION, BINDER);
            case "openAssetFile": return matches(types, URI, STRING, CANCELLATION);
            case "openTypedAssetFile": return matches(types, URI, STRING, BUNDLE, CANCELLATION);
            case "canonicalize": case "uncanonicalize": return matches(types, URI);
            case "refresh": return matches(types, URI, BUNDLE, CANCELLATION);
            default: return false;
        }
    }

    private static boolean matches(String[] actual, String... expected) {
        return Arrays.equals(actual, expected);
    }
}
