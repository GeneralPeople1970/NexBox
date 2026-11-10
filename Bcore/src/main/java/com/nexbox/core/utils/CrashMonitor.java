package com.nexbox.core.utils;

import com.nexbox.core.xposed.RuntimeLog;

/** Records the original failure once. An uncaught exception cannot be repaired by returning. */
public final class CrashMonitor {
    private static boolean initialized;

    private CrashMonitor() { }

    public static synchronized void initialize() {
        if (initialized) return;
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(createHandler(previous, () -> {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        }));
        initialized = true;
    }

    static Thread.UncaughtExceptionHandler createHandler(
            Thread.UncaughtExceptionHandler previous, Runnable terminate) {
        ThreadLocal<Boolean> handling = new ThreadLocal<>();
        return (thread, throwable) -> {
            if (Boolean.TRUE.equals(handling.get())) {
                terminate.run();
                return;
            }
            handling.set(true);
            try {
                try {
                    RuntimeLog.crash(thread, throwable);
                } catch (Throwable ignored) {
                    // Diagnostic failure must not prevent the system's terminal handler.
                }
                try {
                    if (previous != null) previous.uncaughtException(thread, throwable);
                } finally {
                    // Android normally terminates in previous; also fail closed if a guest handler returns.
                    terminate.run();
                }
            } finally {
                handling.remove();
            }
        };
    }
}
