package com.nexbox.core.fake.service;

import java.util.Arrays;

/** Verified Connectivity Binder signatures. Request handles and network payloads stay untouched. */
final class ConnectivityCallPolicy {
    private static final String INT = "int";
    private static final String BOOL = "boolean";
    private static final String STRING = "java.lang.String";
    private static final String NETWORK = "android.net.Network";
    private static final String CAPABILITIES = "android.net.NetworkCapabilities";
    private static final String MESSENGER = "android.os.Messenger";
    private static final String BINDER = "android.os.IBinder";
    private static final String PENDING_INTENT = "android.app.PendingIntent";

    private ConnectivityCallPolicy() { }

    static Object[] rewrite(String method, String[] types, Object[] args, String guestPackage,
            int guestUid, int guestUserId, String hostPackage, int hostUid, int hostUserId) {
        if (args == null) return null;
        Object[] mapped = args.clone();
        if (types == null || types.length != args.length || guestPackage == null
                || guestPackage.isEmpty() || guestPackage.equals(hostPackage)) return mapped;
        Layout layout = layout(method, types);
        if (layout == null) return mapped; // No generic String/last-int substitution on vendor APIs.
        if (hostPackage == null || hostPackage.isEmpty() || hostUid <= 0 || hostUserId < 0) {
            throw new IllegalStateException("Connectivity host identity is unavailable");
        }

        boolean ownPackage = layout.packageIndex < 0
                || guestPackage.equals(args[layout.packageIndex])
                || hostPackage.equals(args[layout.packageIndex]);
        boolean ownUid = layout.uidIndex < 0 || isOwnUid(args[layout.uidIndex], guestUid, hostUid);
        if (!ownPackage) return mapped;
        // Redaction APIs name a subject, not just the caller. Do not turn a query about a
        // different app into a query about the host or broaden its access to network details.
        if (layout.subjectQuery && (!ownPackage || !ownUid)) return mapped;
        if (layout.packageIndex >= 0 && ownPackage) {
            mapped[layout.packageIndex] = hostPackage;
            if (layout.attributionIndex >= 0) mapped[layout.attributionIndex] = null;
        }
        if (layout.uidIndex >= 0 && args[layout.uidIndex] instanceof Integer
                && guestUid > 0 && ((Integer) args[layout.uidIndex]) == guestUid) {
            mapped[layout.uidIndex] = hostUid;
        }
        if (layout.userIndex >= 0 && args[layout.userIndex] instanceof Integer
                && guestUserId >= 0 && ((Integer) args[layout.userIndex]) == guestUserId) {
            mapped[layout.userIndex] = hostUserId;
        }
        return mapped;
    }

    static boolean isRegistration(String method) {
        return "requestNetwork".equals(method) || "listenForNetwork".equals(method)
                || "pendingRequestForNetwork".equals(method) || "pendingListenForNetwork".equals(method)
                || "registerConnectivityDiagnosticsCallback".equals(method);
    }

    private static boolean isOwnUid(Object uid, int guestUid, int hostUid) {
        return uid instanceof Integer && (((Integer) uid) == hostUid
                || guestUid > 0 && ((Integer) uid) == guestUid);
    }

    private static Layout layout(String method, String[] types) {
        switch (method) {
            case "requestNetwork":
                // Android 12-15; newer Connectivity modules append declaredMethods flags.
                if (matches(types, INT, CAPABILITIES, INT, MESSENGER, INT, BINDER, INT, INT, STRING, STRING)
                        || matches(types, INT, CAPABILITIES, INT, MESSENGER, INT, BINDER, INT, INT, STRING, STRING, INT)) {
                    return new Layout(8, 9, 0, -1, false);
                }
                return null;
            case "listenForNetwork":
                if (matches(types, CAPABILITIES, MESSENGER, BINDER, INT, STRING, STRING)
                        || matches(types, CAPABILITIES, MESSENGER, BINDER, INT, STRING, STRING, INT)) {
                    return new Layout(4, 5, -1, -1, false);
                }
                return null;
            case "pendingRequestForNetwork":
            case "pendingListenForNetwork":
                return matches(types, CAPABILITIES, PENDING_INTENT, STRING, STRING)
                        ? new Layout(2, 3, -1, -1, false) : null;
            case "getNetworkCapabilities":
                return matches(types, NETWORK, STRING, STRING)
                        ? new Layout(1, 2, -1, -1, false) : null;
            case "getDefaultNetworkCapabilitiesForUser":
                return matches(types, INT, STRING, STRING)
                        ? new Layout(1, 2, -1, 0, false) : null;
            case "getActiveNetworkForUid":
            case "getActiveNetworkInfoForUid":
                return matches(types, INT, BOOL) ? new Layout(-1, -1, 0, -1, false) : null;
            case "getNetworkInfoForUid":
                return matches(types, NETWORK, INT, BOOL) ? new Layout(-1, -1, 1, -1, false) : null;
            case "getRedactedLinkPropertiesForPackage":
                return matches(types, "android.net.LinkProperties", INT, STRING, STRING)
                        ? new Layout(2, 3, 1, -1, true) : null;
            case "getRedactedNetworkCapabilitiesForPackage":
                return matches(types, CAPABILITIES, INT, STRING, STRING)
                        ? new Layout(2, 3, 1, -1, true) : null;
            case "requestRouteToHostAddress":
                return matches(types, INT, "[B", STRING, STRING)
                        ? new Layout(2, 3, -1, -1, false) : null;
            case "registerConnectivityDiagnosticsCallback":
                return matches(types, "android.net.IConnectivityDiagnosticsCallback", "android.net.NetworkRequest", STRING)
                        ? new Layout(2, -1, -1, -1, false) : null;
            default:
                return null;
        }
    }

    private static boolean matches(String[] actual, String... expected) {
        return Arrays.equals(actual, expected);
    }

    private static final class Layout {
        final int packageIndex;
        final int attributionIndex;
        final int uidIndex;
        final int userIndex;
        final boolean subjectQuery;

        Layout(int packageIndex, int attributionIndex, int uidIndex, int userIndex, boolean subjectQuery) {
            this.packageIndex = packageIndex;
            this.attributionIndex = attributionIndex;
            this.uidIndex = uidIndex;
            this.userIndex = userIndex;
            this.subjectQuery = subjectQuery;
        }
    }
}
