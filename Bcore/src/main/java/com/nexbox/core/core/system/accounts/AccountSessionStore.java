package com.nexbox.core.core.system.accounts;

import android.os.Bundle;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Deferred credentials stay in the core; callers receive only a one-use capability. */
final class AccountSessionStore {
    static final String KEY_SESSION_ID = "com.nexbox.account.SESSION_ID";
    static final long LIFETIME_MS = 15 * 60_000L;
    private static final int MAX_SESSIONS = 64;
    private final Map<String, Entry> entries = new HashMap<>();

    static final class Entry {
        final int userId;
        final long userGeneration;
        final long resetEpoch;
        final String callerPackage;
        final String callerSigner;
        final String accountType;
        final String authenticatorPackage;
        final String authenticatorSigner;
        final Bundle credentials;
        final BAccount targetAccount;
        final long expiresAt;

        Entry(int userId, long userGeneration, long resetEpoch, String callerPackage,
              String callerSigner, String accountType, String authenticatorPackage,
              String authenticatorSigner, Bundle credentials, BAccount targetAccount) {
            this.userId = userId;
            this.userGeneration = userGeneration;
            this.resetEpoch = resetEpoch;
            this.callerPackage = callerPackage;
            this.callerSigner = callerSigner;
            this.accountType = accountType;
            this.authenticatorPackage = authenticatorPackage;
            this.authenticatorSigner = authenticatorSigner;
            this.credentials = credentials.deepCopy();
            this.targetAccount = targetAccount;
            this.expiresAt = SystemClock.elapsedRealtime() + LIFETIME_MS;
        }
    }

    synchronized Bundle put(Entry entry) {
        prune();
        if (entries.size() >= MAX_SESSIONS) throw new IllegalStateException("Too many pending account sessions");
        String id = UUID.randomUUID().toString();
        entries.put(id, entry);
        Bundle capability = new Bundle();
        capability.putString(KEY_SESSION_ID, id);
        return capability;
    }

    synchronized Entry take(Bundle capability, int userId, long generation, long resetEpoch,
                            String callerPackage, String callerSigner) {
        prune();
        if (capability == null || capability.size() != 1) throw new IllegalArgumentException("Invalid account session");
        String id = capability.getString(KEY_SESSION_ID);
        Entry entry = entries.get(id);
        if (entry == null || entry.userId != userId || entry.userGeneration != generation
                || ("com.google".equals(entry.accountType) && entry.resetEpoch != resetEpoch)
                || !Objects.equals(entry.callerPackage, callerPackage)
                || !Objects.equals(entry.callerSigner, callerSigner)) {
            throw new IllegalArgumentException("Account session expired or belongs to another caller");
        }
        entries.remove(id);
        return entry;
    }

    synchronized void removeUser(int userId) {
        entries.values().removeIf(entry -> entry.userId == userId);
    }

    synchronized void removeGoogleAccounts(int userId) {
        entries.values().removeIf(entry -> entry.userId == userId && "com.google".equals(entry.accountType));
    }

    synchronized void packageChanged(String packageName) {
        entries.values().removeIf(entry -> packageName.equals(entry.callerPackage)
                || packageName.equals(entry.authenticatorPackage));
    }

    synchronized void prune() {
        long now = SystemClock.elapsedRealtime();
        entries.values().removeIf(entry -> entry.expiresAt <= now);
    }
}
