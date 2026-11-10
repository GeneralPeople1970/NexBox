package com.nexbox.core.xposed;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Host-owned container diagnostics. Never uploads or reads guest Logcat. */
public final class RuntimeLog {
    public interface Sink {
        boolean isEnabled();
        default boolean isEnabled(int priority) { return isEnabled(); }
        void write(int priority, String tag, String message, Throwable error);
        default void crash(Thread thread, Throwable error) { }
    }
    private static volatile Sink sink;
    private static volatile String processContext = "";
    private static final ThreadLocal<Boolean> suppressed = new ThreadLocal<>();
    private static final CopyOnWriteArrayList<Consumer<Boolean>> listeners = new CopyOnWriteArrayList<>();
    private RuntimeLog() { }

    public static void setProcessContext(String value) { processContext = value == null ? "" : value; }
    public static String getProcessContext() { return processContext; }

    public static void setSink(Sink value) { sink = value; configurationChanged(); }
    public static boolean isEnabled() {
        Sink current = sink;
        return !Boolean.TRUE.equals(suppressed.get()) && current != null && current.isEnabled();
    }
    public static boolean isEnabled(int priority) {
        Sink current = sink;
        return !Boolean.TRUE.equals(suppressed.get()) && current != null && current.isEnabled(priority);
    }
    public static int write(int priority, String tag, String message, Throwable error) {
        Sink current = sink;
        if (Boolean.TRUE.equals(suppressed.get()) || current == null || !current.isEnabled(priority)) return 0;
        try { current.write(priority, tag, message, error); }
        catch (RuntimeException ignored) { /* Logging must not break an intercepted call. */ }
        return 0;
    }
    public static void withoutLogging(Runnable action) {
        Boolean previous = suppressed.get();
        suppressed.set(true);
        try { action.run(); }
        finally { if (previous == null) suppressed.remove(); else suppressed.set(previous); }
    }
    public static void crash(Thread thread, Throwable error) {
        Sink current = sink;
        if (current != null && current.isEnabled()) {
            try { current.crash(thread, error); } catch (RuntimeException ignored) { }
        }
    }
    public static AutoCloseable addConfigurationListener(Consumer<Boolean> listener) {
        listeners.add(listener);
        Sink current = sink;
        listener.accept(current != null && current.isEnabled());
        return () -> listeners.remove(listener);
    }
    public static void configurationChanged() {
        Sink current = sink;
        boolean enabled = current != null && current.isEnabled();
        for (Consumer<Boolean> listener : listeners) {
            try { listener.accept(enabled); } catch (RuntimeException ignored) { }
        }
    }
    public static int v(String tag, String message) { return write(2, tag, message, null); }
    public static int d(String tag, String message) { return write(3, tag, message, null); }
    public static int i(String tag, String message) { return write(4, tag, message, null); }
    public static int w(String tag, String message) { return write(5, tag, message, null); }
    public static int e(String tag, String message) { return write(6, tag, message, null); }
    public static int v(String tag, String message, Throwable error) { return write(2, tag, message, error); }
    public static int d(String tag, String message, Throwable error) { return write(3, tag, message, error); }
    public static int i(String tag, String message, Throwable error) { return write(4, tag, message, error); }
    public static int w(String tag, String message, Throwable error) { return write(5, tag, message, error); }
    public static int e(String tag, String message, Throwable error) { return write(6, tag, message, error); }
    public static int println(int level, String tag, String message) { return write(level, tag, message, null); }
}
