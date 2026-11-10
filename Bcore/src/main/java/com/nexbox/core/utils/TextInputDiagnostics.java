package com.nexbox.core.utils;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Input diagnostics never inspect payloads or exception messages; window rejection codes are safe. */
public final class TextInputDiagnostics {
    public static final String TAG = "TextInput";
    private static final AtomicLong IDS = new AtomicLong();

    public enum Component {
        CLIPBOARD, TEXT_CLASSIFIER, SELECTION_TOOLBAR, NATIVE_MENU, WINDOW;

        private final Budget calls = new Budget(30);
        private final Budget failures = new Budget(20);
    }

    private TextInputDiagnostics() { }

    public static <T> T call(Component component, String method,
            OperationLog.Action<T, Throwable> action) throws Throwable {
        return call(component, method, "", action);
    }

    /** Contract details are type names and fixed routing labels only, never argument values. */
    public static <T> T call(Component component, String method, String contract,
            OperationLog.Action<T, Throwable> action) throws Throwable {
        boolean detailed = Slog.isLoggable(TAG, android.util.Log.DEBUG);
        if (!detailed && !Slog.isLoggable(TAG, android.util.Log.ERROR)) return action.run();
        String operation = detailed ? operationContext(component, method, contract) : null;
        int skipped = detailed ? component.calls.claim() : -1;
        if (skipped >= 0) record(operation + " stage=call suppressed=" + skipped, false);
        try {
            T result = action.run();
            // A returned Binder call is not proof that text was pasted or a window was displayed.
            if (skipped >= 0) record(operation + " stage=returned", false);
            return result;
        } catch (Throwable failure) {
            failedOperation(component, operation == null ? operationContext(component, method, contract) : operation, failure);
            throw failure;
        }
    }

    private static String operationContext(Component component, String method, String contract) {
        return "op=input-" + IDS.incrementAndGet() + " component=" + component
                + " method=" + method + (contract.isEmpty() ? "" : " " + contract);
    }

    public static String signature(Method method) {
        StringBuilder result = new StringBuilder("(");
        Class<?>[] parameters = method.getParameterTypes();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) result.append(',');
            result.append(parameters[index].getName());
        }
        return result.append(')').toString();
    }

    /** Fixed capability fields only; shares the ordinary per-component trace budget. */
    public static void state(Component component, String method, String capability) {
        if (!Slog.isLoggable(TAG, android.util.Log.DEBUG)) return;
        int skipped = component.calls.claim();
        if (skipped >= 0) record("component=" + component + " method=" + method
                + " stage=capability " + capability + " suppressed=" + skipped, false);
    }

    public static void failed(Component component, String method, Throwable failure) {
        failedOperation(component, "component=" + component + " method=" + method, failure);
    }

    private static void failedOperation(Component component, String operation, Throwable failure) {
        if (!Slog.isLoggable(TAG, android.util.Log.ERROR)) return;
        try {
            int skipped = component.failures.claim();
            if (skipped >= 0) record(operation + " stage=failed suppressed=" + skipped + " messages=omitted\n"
                    + describeFailure(failure), true);
        } catch (Throwable ignored) {
            // The original failure, including a fatal error, must reach the caller unchanged.
        }
    }

    public static void rejected(Component component, String method, int result) {
        if (!Slog.isLoggable(TAG, android.util.Log.ERROR)) return;
        int skipped = component.failures.claim();
        if (skipped >= 0) record("component=" + component + " method=" + method
                + " stage=rejected result=" + result + " suppressed=" + skipped, true);
    }

    private static void record(String message, boolean error) {
        try {
            if (error) Slog.e(TAG, message); else Slog.d(TAG, message);
        } catch (Throwable ignored) {
            // Logging must not turn a successful framework call into an input failure.
        }
    }

    static String describeFailure(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        appendFailure(result, failure, "", seen, new int[]{48}, 0);
        return result.toString();
    }

    private static void appendFailure(StringBuilder result, Throwable failure, String prefix,
            Set<Throwable> seen, int[] framesLeft, int depth) {
        if (failure == null) return;
        if (depth >= 8 || seen.size() >= 16 || !seen.add(failure)) {
            result.append(prefix).append("[exception chain truncated]\n");
            return;
        }
        result.append(prefix).append(failure.getClass().getName()).append('\n');
        StackTraceElement[] frames = failure.getStackTrace();
        int count = Math.min(frames.length, framesLeft[0]);
        for (int index = 0; index < count; index++) {
            result.append("\tat ").append(frames[index]).append('\n');
        }
        framesLeft[0] -= count;
        if (count < frames.length) result.append("\t[frames truncated]\n");
        appendFailure(result, failure.getCause(), "Caused by: ", seen, framesLeft, depth + 1);
        Throwable[] suppressed = failure.getSuppressed();
        for (int index = 0; index < Math.min(suppressed.length, 2); index++) {
            appendFailure(result, suppressed[index], "Suppressed: ", seen, framesLeft, depth + 1);
        }
        if (suppressed.length > 2) result.append("[suppressed exceptions truncated]\n");
    }

    private static final class Budget {
        private static final long WINDOW_NANOS = 60_000_000_000L;
        private final int limit;
        private long started = System.nanoTime();
        private int accepted;
        private int suppressed;

        Budget(int limit) { this.limit = limit; }

        synchronized int claim() {
            long now = System.nanoTime();
            if (now - started >= WINDOW_NANOS) {
                started = now;
                accepted = 0;
            }
            if (accepted >= limit) {
                if (suppressed < Integer.MAX_VALUE) suppressed++;
                return -1;
            }
            accepted++;
            int skipped = suppressed;
            suppressed = 0;
            return skipped;
        }
    }
}
