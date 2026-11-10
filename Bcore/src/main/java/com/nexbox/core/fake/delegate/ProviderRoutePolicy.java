package com.nexbox.core.fake.delegate;

/** Pure routing decisions. An authority's brand or substring is never host-access authority. */
public final class ProviderRoutePolicy {
    public enum Route { SYSTEM, GUEST, UNAVAILABLE }

    private ProviderRoutePolicy() { }

    public static boolean isReservedSystemAuthority(String authority) {
        return "settings".equals(authority) || "media".equals(authority)
                || "telephony".equals(authority)
                // GMS account enrollment reads this immutable SetupWizard partner
                // configuration before contacting the account backend. It is a
                // platform provider, not an account or credential store, so the
                // container must use the system instance when GMS has no guest copy.
                || "com.google.android.setupwizard.partner".equals(authority)
                || "com.huawei.android.launcher.settings".equals(authority)
                || "com.hihonor.android.launcher.settings".equals(authority);
    }

    public static boolean isContainerAuthority(String authority, String hostPackage, int proxyCount) {
        if (authority == null || hostPackage == null || hostPackage.isEmpty()) return false;
        if ((hostPackage + ".diagnostics").equals(authority)
                || (hostPackage + ".rocker").equals(authority)
                || (hostPackage + ".nexbox.Documents").equals(authority)
                || (hostPackage + ".nexbox.MediaProvider").equals(authority)
                || (hostPackage + ".nexbox.SystemCallProvider").equals(authority)) return true;
        String prefix = hostPackage + ".proxy_content_provider_";
        if (!authority.startsWith(prefix)) return false;
        String suffix = authority.substring(prefix.length());
        try {
            int index = Integer.parseInt(suffix);
            return index >= 0 && index < proxyCount && suffix.equals(String.valueOf(index));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static Route choose(String authority, boolean guestExists, boolean openHostPackage,
            boolean hostFallbackAllowed) {
        if (isReservedSystemAuthority(authority)) return Route.SYSTEM;
        if (guestExists) return Route.GUEST;
        return openHostPackage && hostFallbackAllowed ? Route.SYSTEM : Route.UNAVAILABLE;
    }

    public static boolean requiresSamsungAccountOptIn(String callerPackage, String authority,
            String providerPackage) {
        return "com.sec.android.app.shealth".equals(callerPackage)
                && "com.samsung.android.samsungaccount.accountmanagerprovider".equals(authority)
                && "com.osp.app.signin".equals(providerPackage);
    }

    /** Full, user-scoped data-directory identity; shared APK paths and app ids are insufficient. */
    public static boolean sameDataDirectory(String actual, String expected) {
        return actual != null && !actual.isEmpty() && actual.equals(expected);
    }
}
