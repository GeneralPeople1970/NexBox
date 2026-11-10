package com.nexbox.core.utils;

/** A verified, transaction-scoped identity for an incoming guest provider oneway call. */
public final class GuestProviderIdentity implements AutoCloseable {
    private static final ThreadLocal<Integer> CALLER_UID = new ThreadLocal<>();
    private final Integer previous;

    private GuestProviderIdentity(int callerUid) {
        previous = CALLER_UID.get();
        CALLER_UID.set(callerUid);
    }

    public static GuestProviderIdentity enter(int callerUid) {
        if (callerUid < 0) throw new IllegalArgumentException("A verified guest UID is required");
        return new GuestProviderIdentity(callerUid);
    }

    public static int getUid() {
        Integer uid = CALLER_UID.get();
        return uid == null ? -1 : uid;
    }

    @Override public void close() {
        if (previous == null) CALLER_UID.remove();
        else CALLER_UID.set(previous);
    }
}
