package com.nexbox.core.fake.service;

import java.lang.reflect.Method;

/** Version-aware Binder identity mapping, deliberately independent of clipboard payloads. */
final class ClipboardCallPolicy {
    private ClipboardCallPolicy() { }

    static boolean rewrite(Method method, Object[] args, String hostPackage, int hostUserId) {
        if (args == null || hostPackage == null || hostPackage.isEmpty() || hostUserId < 0) return false;
        Class<?>[] types = method.getParameterTypes();
        if (types.length != args.length) return false;
        switch (method.getName()) {
            case "getUserPrimaryClip":
                // MIUI uses a separate Binder read for a user-initiated paste. It must keep
                // that API's permission/interaction semantics, with the same transport
                // identity as getPrimaryClip; never retry it through the ordinary read.
                if (!isUserPrimaryClipSignature(types)) return false;
                break;
            case "setPrimaryClip":
            case "setPrimaryClipAsPackage":
            case "clearPrimaryClip":
            case "getPrimaryClip":
            case "getPrimaryClipDescription":
            case "hasPrimaryClip":
            case "hasClipboardText":
            case "addPrimaryClipChangedListener":
            case "removePrimaryClipChangedListener":
            case "getPrimaryClipSource":
                break;
            default:
                return false; // Never reinterpret an unknown/vendor method's arbitrary strings or IDs.
        }
        int packageIndex = -1;
        for (int i = 0; i < types.length && i < args.length; i++) {
            if (types[i] == String.class) {
                packageIndex = i;
                break;
            }
        }
        // Old remove-listener calls have only the listener Binder, and no calling identity.
        if (packageIndex < 0) return false;
        args[packageIndex] = hostPackage;

        // Android 12+ adds attributionTag immediately after callingPackage. Guest tags are
        // not declared by the host; do not pass them to AppOps under a different package.
        int afterPackage = packageIndex + 1;
        if (afterPackage < types.length && afterPackage < args.length && types[afterPackage] == String.class) {
            args[afterPackage] = null;
            afterPackage++;
        }
        // Android 9+ has userId after the identity; Android 14+ adds deviceId after userId.
        // Only the Android user is mapped. Do not confuse a virtual device or source package
        // (setPrimaryClipAsPackage) with the virtual container profile.
        if (afterPackage < types.length && afterPackage < args.length && types[afterPackage] == int.class) {
            args[afterPackage] = hostUserId;
        }
        return true;
    }

    private static boolean isUserPrimaryClipSignature(Class<?>[] types) {
        if (types.length < 2 || types[0] != String.class) return false;
        int userIndex = types[1] == String.class ? 2 : 1;
        return userIndex < types.length && types[userIndex] == int.class
                && (types.length == userIndex + 1
                || (types.length == userIndex + 2 && types[userIndex + 1] == int.class));
    }
}
