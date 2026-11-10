package com.nexbox.core.utils;

/** Nestable provider/transport-bridge route, read only for self-PID calls by the Binder UID hook. */
public final class LocalProviderIdentity implements AutoCloseable {
    private static final ThreadLocal<Boolean> SYSTEM_PROVIDER = new ThreadLocal<>();
    private final Boolean previous;

    private LocalProviderIdentity(boolean systemProvider) {
        previous = SYSTEM_PROVIDER.get();
        SYSTEM_PROVIDER.set(systemProvider);
    }

    public static LocalProviderIdentity enter(boolean systemProvider) {
        return new LocalProviderIdentity(systemProvider);
    }

    public static boolean isSystemProvider() {
        return Boolean.TRUE.equals(SYSTEM_PROVIDER.get());
    }

    @Override public void close() {
        if (previous == null) SYSTEM_PROVIDER.remove();
        else SYSTEM_PROVIDER.set(previous);
    }
}
