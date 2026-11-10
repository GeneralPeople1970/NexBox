package com.nexbox.core.core.system.pm;

/** Pure guards shared by the strict Binder boundary and its unit tests. */
final class InstallRequestPolicy {
    private InstallRequestPolicy() { }

    static boolean hasInstalledPackages(Iterable<BPackageSettings> packages, int userId) {
        for (BPackageSettings settings : packages) {
            if (settings.getInstalled(userId)) return true;
        }
        return false;
    }

    static String targetError(int userId, long expectedCreateTime, Long actualCreateTime) {
        if (userId < 0 || expectedCreateTime < 0) return "Invalid virtual user identity";
        if (expectedCreateTime == 0) {
            return actualCreateTime == null ? null : "The requested new virtual user already exists";
        }
        return actualCreateTime != null && actualCreateTime == expectedCreateTime
                ? null : "The target virtual user identity changed";
    }

    static String packageError(String expectedPackageName, String actualPackageName, boolean alreadyInstalled) {
        if (expectedPackageName == null || expectedPackageName.trim().isEmpty()) {
            return "An expected package name is required";
        }
        if (!expectedPackageName.equals(actualPackageName)) return "The installation package changed";
        return alreadyInstalled ? "This application is already installed in the target user" : null;
    }
}
