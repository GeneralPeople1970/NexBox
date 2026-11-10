package com.nexbox.core.core.system.pm;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

import com.nexbox.core.utils.MutationReceipt.Rejected;

/** Access only under the package lifecycle lock. Restart discards all issued snapshots. */
final class InventorySnapshotStore {
    static final long RETENTION_MILLIS = 10 * 60 * 1000L;
    private static final int MAX_SNAPSHOTS = 128;
    private final Map<String, Instance> instances = new HashMap<>();
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();

    private static final class Instance {
        Object settings;
        Object pkg;
        Object userState;
        String source;
        String version;
    }

    private static final class Snapshot {
        int userId;
        long generation;
        long issuedAt;
        Map<String, String> packages;
    }

    String version(int userId, long generation, String packageName, Object settings,
                   Object pkg, Object userState, String source) {
        String key = userId + ":" + generation + ":" + packageName;
        Instance value = instances.get(key);
        if (value == null || value.settings != settings || value.pkg != pkg
                || value.userState != userState || !Objects.equals(value.source, source)) {
            value = new Instance();
            value.settings = settings;
            value.pkg = pkg;
            value.userState = userState;
            value.source = source;
            value.version = "install1." + UUID.randomUUID();
            instances.put(key, value);
        }
        return value.version;
    }

    // Invalidate before mutations, including failed attempts with uncertain disk outcomes.
    void invalidate(String packageName) {
        instances.keySet().removeIf(key -> key.endsWith(":" + packageName));
    }

    void forgetUser(int userId) {
        instances.keySet().removeIf(key -> key.startsWith(userId + ":"));
    }

    String issue(int userId, long generation, Map<String, String> packages, long now) {
        expire(now);
        while (snapshots.size() >= MAX_SNAPSHOTS) snapshots.remove(snapshots.keySet().iterator().next());
        Snapshot value = new Snapshot();
        value.userId = userId;
        value.generation = generation;
        value.issuedAt = now;
        value.packages = Collections.unmodifiableMap(new TreeMap<>(packages));
        String token = "inventory1." + UUID.randomUUID();
        snapshots.put(token, value);
        return token;
    }

    void require(String token, int userId, long generation, Map<String, String> packages, long now) {
        expire(now);
        Snapshot value = snapshots.get(token);
        if (value == null) throw new Rejected("snapshot_expired");
        if (value.userId != userId || value.generation != generation) throw new Rejected("stale_user");
        if (!value.packages.equals(packages)) throw new Rejected("inventory_changed");
    }

    private void expire(long now) {
        Iterator<Snapshot> values = snapshots.values().iterator();
        while (values.hasNext()) {
            Snapshot value = values.next();
            if (now < value.issuedAt || now - value.issuedAt >= RETENTION_MILLIS) values.remove();
        }
    }
}
