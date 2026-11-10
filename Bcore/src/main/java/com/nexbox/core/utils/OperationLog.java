package com.nexbox.core.utils;

import java.util.concurrent.atomic.AtomicLong;

/** Operation boundaries without wrapping, swallowing or changing the original exception. */
public final class OperationLog {
    public interface Action<T, E extends Throwable> { T run() throws E; }
    private static final AtomicLong ids = new AtomicLong();
    private OperationLog() { }

    public static <T, E extends Throwable> T call(String tag, String action, String fields, Action<T, E> work) throws E {
        // Keep failure boundaries when detailed INFO traces are disabled.
        if (!Slog.isLoggable(tag, android.util.Log.WARN)) return work.run();
        long start = System.nanoTime();
        String context = "op=core-" + Long.toUnsignedString(start, 36) + "-" + ids.incrementAndGet() +
                " action=" + action + " " + fields;
        Slog.iLazy(tag, () -> context + " stage=start");
        try {
            T result = work.run();
            if (Boolean.FALSE.equals(result) || Slog.isLoggable(tag, android.util.Log.INFO)) {
                String outcome = result instanceof Boolean || result instanceof Number ? " returned=" + result : "";
                String message = context + " stage=finish durationMs=" + ((System.nanoTime() - start) / 1_000_000L) + outcome;
                if (Boolean.FALSE.equals(result)) Slog.w(tag, message); else Slog.i(tag, message);
            }
            return result;
        } catch (Throwable error) {
            Slog.e(tag, context + " stage=failed durationMs=" + ((System.nanoTime() - start) / 1_000_000L), error);
            throw error;
        }
    }
}
