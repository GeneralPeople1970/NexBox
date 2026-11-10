package com.nexbox.core.fake.service;

import android.content.pm.ApplicationInfo;
import java.lang.reflect.Method;

/** Metadata access for ROM text UI, not a general host-package allowlist. */
final class SystemTextMenuPolicy {
    static final String EDITOR_PACKAGE = "com.miuix.editor";
    static final String CLIPBOARD_UI_PACKAGE = "com.miui.phrase";

    private SystemTextMenuPolicy() { }

    static boolean isTextUiPackage(String packageName) {
        return EDITOR_PACKAGE.equals(packageName) || CLIPBOARD_UI_PACKAGE.equals(packageName);
    }

    static boolean isSystemTextUi(String packageName, int flags) {
        return isTextUiPackage(packageName)
                && (flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
    }

    static int androidUserIndex(Method method) {
        if (!"getApplicationInfo".equals(method.getName())
                && !"getPackageInfo".equals(method.getName())) return -1;
        Class<?>[] types = method.getParameterTypes();
        return types.length == 3 && types[0] == String.class
                && (types[1] == int.class || types[1] == long.class)
                && types[2] == int.class ? 2 : -1;
    }
}
