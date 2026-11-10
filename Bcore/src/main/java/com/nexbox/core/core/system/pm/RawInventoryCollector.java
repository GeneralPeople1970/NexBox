package com.nexbox.core.core.system.pm;

import com.nexbox.core.utils.MutationReceipt.Rejected;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Deliberately has no visibility/Google/PackageInfo filter. Every installed raw identity is retained. */
final class RawInventoryCollector {
    private final Set<String> identities = new HashSet<>();
    private final Map<String, String> packages = new TreeMap<>();

    void identity(String key, String storedName) {
        if (key == null || key.length() > 255
                || !key.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
                || !key.equals(storedName) || !identities.add(storedName)) {
            throw new Rejected("invalid_inventory_identity");
        }
    }

    void installed(String name, String version) {
        if (!identities.contains(name) || version == null || version.isEmpty() || packages.put(name, version) != null) {
            throw new Rejected("invalid_inventory_identity");
        }
        if (packages.size() > 1024) throw new Rejected("inventory_unreadable");
    }

    Map<String, String> snapshot() { return new TreeMap<>(packages); }
}
