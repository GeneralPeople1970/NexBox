package com.nexbox.core.core.system;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Server-owned Google crash circuit. All calls are made under the process lock. */
final class GmsProcessRestartPolicy {
    private static final long STABLE_RUN_MS = 120_000L;
    private static final long FIRST_DELAY_MS = 5_000L;
    private static final int MAX_FAILURES = 6;
    private final Map<Key, State> states = new HashMap<>();

    static final class Key {
        final String packageName;
        final String processName;
        final int userId;
        final long generation;

        Key(String packageName, String processName, int userId, long generation) {
            this.packageName = packageName;
            this.processName = processName;
            this.userId = userId;
            this.generation = generation;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return userId == key.userId && generation == key.generation
                    && Objects.equals(packageName, key.packageName) && Objects.equals(processName, key.processName);
        }

        @Override public int hashCode() { return Objects.hash(packageName, processName, userId, generation); }
    }

    private static final class State {
        int failures;
        long startedAt = -1;
        long retryAt;
        boolean rejectionLogged;
    }

    /** -1 means open circuit; otherwise the remaining delay, without sleeping or retrying. */
    long remainingDelay(Key key, long now) {
        State state = states.get(key);
        if (state == null) return 0;
        if (state.failures >= MAX_FAILURES) return -1;
        return Math.max(0, state.retryAt - now);
    }

    boolean shouldLogRejection(Key key) {
        State state = states.get(key);
        if (state == null || state.rejectionLogged) return false;
        state.rejectionLogged = true;
        return true;
    }

    void onStart(Key key, long now) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        state.startedAt = now;
        state.rejectionLogged = false;
    }

    void onFailure(Key key, long now) {
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.startedAt >= 0 && now - state.startedAt >= STABLE_RUN_MS) state.failures = 0;
        state.failures = Math.min(MAX_FAILURES, state.failures + 1);
        state.startedAt = -1;
        state.retryAt = now + (FIRST_DELAY_MS << (state.failures - 1));
        state.rejectionLogged = false;
    }

    // Explicit stop/reset/uninstall (or a server restart) permits another attempt. Idle time
    // alone must not reset a permanently broken Google process into another crash loop.
    void clearPackage(String packageName, int userId) {
        states.keySet().removeIf(key -> key.packageName.equals(packageName)
                && (userId < 0 || key.userId == userId));
    }

    void clearUser(int userId) { states.keySet().removeIf(key -> key.userId == userId); }
}
