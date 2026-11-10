package com.nexbox.core.fake.service;

import java.util.Arrays;
import java.util.Set;

/** Read-only permission-query identity mapping; no permission is granted by this policy. */
final class PermissionCheckPolicy {
    enum Backend { ACTIVITY, PACKAGE, PERMISSION }
    private static final String STRING = "java.lang.String";

    static final class Layout {
        static final Layout UNKNOWN = new Layout(-1, -1, -1, -1, -1, -1);
        final int permission;
        final int packageName;
        final int uid;
        final int pid;
        final int user;
        final int device;

        Layout(int permission, int packageName, int uid, int pid, int user, int device) {
            this.permission = permission;
            this.packageName = packageName;
            this.uid = uid;
            this.pid = pid;
            this.user = user;
            this.device = device;
        }
    }

    static final class Identity {
        final String guestPackage;
        final int guestUid;
        final int guestUser;
        final String hostPackage;
        final int hostUid;
        final int hostUser;
        final int processPid;

        Identity(String guestPackage, int guestUid, int guestUser, String hostPackage,
                int hostUid, int hostUser, int processPid) {
            this.guestPackage = guestPackage;
            this.guestUid = guestUid;
            this.guestUser = guestUser;
            this.hostPackage = hostPackage;
            this.hostUid = hostUid;
            this.hostUser = hostUser;
            this.processPid = processPid;
        }
    }

    private PermissionCheckPolicy() { }

    interface HostCheck { int check() throws Throwable; }

    static int checkDeclared(Set<String> requested, String permission, HostCheck hostCheck) throws Throwable {
        if (requested == null || permission == null || !requested.contains(permission)) return -1;
        return hostCheck.check();
    }

    static Layout layout(Backend backend, String method, String[] types) {
        switch (backend) {
            case ACTIVITY:
                if ("checkPermission".equals(method) && matches(types, STRING, "int", "int")) {
                    return new Layout(0, -1, 2, 1, -1, -1);
                }
                if ("checkPermissionForDevice".equals(method)
                        && matches(types, STRING, "int", "int", "int")) {
                    return new Layout(0, -1, 2, 1, -1, 3);
                }
                break;
            case PACKAGE:
                // IPackageManager keeps permission first, including Android 15's legacy bridge.
                if ("checkPermission".equals(method) && matches(types, STRING, STRING, "int")) {
                    return new Layout(0, 1, -1, -1, 2, -1);
                }
                if ("checkUidPermission".equals(method) && matches(types, STRING, "int")) {
                    return new Layout(0, -1, 1, -1, -1, -1);
                }
                break;
            case PERMISSION:
                // Android 15 IPermissionManager puts package/UID first. The third package-query
                // argument is a persistent device identifier, NOT a package or Android user.
                if ("checkPermission".equals(method) && matches(types, STRING, STRING, STRING, "int")) {
                    return new Layout(1, 0, -1, -1, 3, 2);
                }
                if ("checkUidPermission".equals(method) && matches(types, "int", STRING, "int")) {
                    return new Layout(1, -1, 0, -1, -1, 2);
                }
                break;
        }
        return Layout.UNKNOWN;
    }

    static boolean isCurrentGuest(Layout layout, Object[] args, Identity identity) {
        if (layout == null || layout.permission < 0 || args == null || identity == null || identity.guestPackage == null
                || identity.guestPackage.isEmpty() || identity.guestUid < 10000
                || identity.hostPackage == null || identity.hostPackage.isEmpty()
                || identity.guestUser < 0 || identity.hostUid <= 0 || identity.hostUser < 0 || identity.processPid <= 0
                || identity.guestPackage.equals(identity.hostPackage)) return false;
        if (layout.permission >= args.length || !(args[layout.permission] instanceof String)) return false;
        if (layout.packageName >= 0) {
            if (layout.packageName >= args.length || layout.user >= args.length
                    || !identity.guestPackage.equals(args[layout.packageName])
                    || !(args[layout.user] instanceof Integer)) return false;
            int user = (Integer) args[layout.user];
            // Public guest Contexts use the real Android user; virtual PM clients may supply
            // the current virtual user. Never retarget a different user's package query.
            return user == identity.hostUser || user == identity.guestUser;
        }
        if (layout.uid < 0 || layout.uid >= args.length || !(args[layout.uid] instanceof Integer)) return false;
        int uid = (Integer) args[layout.uid];
        if (layout.pid >= 0) {
            if (layout.pid >= args.length || !(args[layout.pid] instanceof Integer)) return false;
            int pid = (Integer) args[layout.pid];
            if (pid != -1 && pid != identity.processPid) return false;
            return uid == identity.guestUid || (uid == identity.hostUid && pid == identity.processPid);
        }
        // A bare check for the real host UID is not assumed to be a query for this guest.
        return uid == identity.guestUid;
    }

    static Object[] mapCurrentGuest(Layout layout, Object[] original, Identity identity) {
        if (!isCurrentGuest(layout, original, identity)) throw new IllegalArgumentException("Not the current guest permission query");
        Object[] mapped = original.clone();
        if (layout.packageName >= 0) mapped[layout.packageName] = identity.hostPackage;
        if (layout.uid >= 0) mapped[layout.uid] = identity.hostUid;
        if (layout.user >= 0) mapped[layout.user] = identity.hostUser;
        // PID and device/device-persistent-ID values are never inferred from an integer's position.
        return mapped;
    }

    private static boolean matches(String[] actual, String... expected) {
        return Arrays.equals(actual, expected);
    }
}
