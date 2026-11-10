package com.nexbox.core.core;

import android.content.Intent;
import android.content.pm.ComponentInfo;

/** Compatibility boundaries for the virtual Google runtime, not arbitrary guest apps. */
public final class GmsCompat {
    private GmsCompat() { }

    public static boolean isCorePackage(String packageName) {
        return "com.android.vending".equals(packageName)
                || "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.google.android.gsf.login".equals(packageName);
    }

    public static boolean requiresVirtualComponent(Intent request, ComponentInfo resolved) {
        String target = request == null ? null : request.getPackage();
        if (request != null && request.getComponent() != null) target = request.getComponent().getPackageName();
        return isCorePackage(target) || (resolved != null && isCorePackage(resolved.packageName));
    }

    public static boolean providerBelongsInProcess(String packageName, String processName,
            String providerPackage, String providerProcess, boolean multiprocess) {
        if (packageName == null || !packageName.equals(providerPackage) || processName == null) return false;
        String declared = providerProcess;
        if (declared == null || declared.isEmpty()) declared = providerPackage;
        if (declared.startsWith(":")) declared = providerPackage + declared;
        // A default-process provider is NOT a multiprocess provider. Play builds different
        // dependency components in :background; installing main providers there breaks casts.
        return multiprocess || processName.equals(declared);
    }
}
