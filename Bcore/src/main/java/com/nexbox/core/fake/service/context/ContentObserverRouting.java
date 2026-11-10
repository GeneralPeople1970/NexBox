package com.nexbox.core.fake.service.context;

/** Pure URI boundary shared by observer registration and notification forwarding. */
final class ContentObserverRouting {
    enum Destination { BLOCKED, MEDIA, DIAGNOSTICS }

    private ContentObserverRouting() { }

    static Destination destination(String scheme, String authority, String hostPackage,
                                   String publicMediaAuthority, String privateMediaAuthority) {
        // Matches DiagnosticLogProvider's context.packageName + ".diagnostics" authority.
        // Do not let another guest/provider opt in by sharing only the suffix.
        if ("content".equals(scheme) && hostPackage != null && !hostPackage.isEmpty()
                && (hostPackage + ".diagnostics").equals(authority)) {
            return Destination.DIAGNOSTICS;
        }
        if (authority != null && (authority.equals(publicMediaAuthority)
                || authority.equals(privateMediaAuthority))) {
            return Destination.MEDIA;
        }
        return Destination.BLOCKED;
    }
}
