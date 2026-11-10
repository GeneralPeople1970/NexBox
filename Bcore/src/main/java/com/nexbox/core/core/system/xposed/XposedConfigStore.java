package com.nexbox.core.core.system.xposed;

import java.io.IOException;
import java.util.Collection;
import java.util.TreeSet;

/** Single writer with copy-on-write commits. Failed writes never publish speculative UI state. */
public final class XposedConfigStore {
    public interface Storage {
        /** null means absent, not a corrupt or unreadable file. */
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }

    private final Storage storage;
    private XposedConfigState state = XposedConfigState.empty();
    private String error;
    private boolean corrupt;

    public XposedConfigStore(Storage storage) { this.storage = storage; }

    public synchronized void load() {
        try {
            byte[] bytes = storage.read();
            state = bytes == null ? XposedConfigState.empty() : XposedConfigState.decode(bytes);
            error = null;
            corrupt = false;
        } catch (IOException e) {
            state = XposedConfigState.empty();
            corrupt = true;
            error = "Cannot read Xposed config: " + e.getMessage();
            // Preserve the bad file for diagnosis. Do not silently overwrite user configuration.
        }
    }

    public synchronized XposedConfigState snapshot() { return state; }
    public synchronized String error() { return error; }

    public synchronized boolean setModule(int userId, String name, boolean enabled, Collection<String> scope) {
        return commit(state.withModule(userId, name, enabled, scope));
    }

    /** Narrow only the exact approved configuration observed by a module service transaction. */
    public synchronized boolean removeScopeIfUnchanged(int userId, String name,
            XposedConfigState.ModuleConfig expected, Collection<String> packages) {
        XposedConfigState.ModuleConfig current = state.modules(userId).get(name);
        if (current == null || current != expected) return false;
        TreeSet<String> remaining = new TreeSet<>(current.scope);
        remaining.removeAll(packages);
        return remaining.equals(current.scope)
                || commit(state.withModule(userId, name, current.enabled, remaining));
    }

    public synchronized boolean migrateLegacy(Collection<String> names) {
        return state.legacyMigrated || commit(state.migrateLegacy(names));
    }

    public synchronized boolean removePackage(int userId, String name, boolean allUsers) {
        return commit(state.removePackage(userId, name, allUsers));
    }

    public synchronized boolean removeUser(int userId) { return commit(state.removeUser(userId)); }

    private boolean commit(XposedConfigState next) {
        if (corrupt) return false;
        try {
            storage.write(next.encode());
            state = next;
            error = null;
            return true;
        } catch (IOException e) {
            error = "Cannot save Xposed config: " + e.getMessage();
            return false;
        }
    }
}
