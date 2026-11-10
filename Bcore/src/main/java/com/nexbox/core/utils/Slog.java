

package com.nexbox.core.utils;

import android.util.Log;
import com.nexbox.core.xposed.RuntimeLog;
import java.util.function.Supplier;


public final class Slog {
     public static final int LOG_ID_SYSTEM = 3;

    private Slog() {
    }

    public static boolean isLoggable(String tag, int level) {
        try { return RuntimeLog.isEnabled(level); }
        catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    public static int dLazy(String tag, Supplier<String> message) {
        return writeLazy(Log.DEBUG, tag, message);
    }

    public static int iLazy(String tag, Supplier<String> message) {
        return writeLazy(Log.INFO, tag, message);
    }

    private static int writeLazy(int priority, String tag, Supplier<String> message) {
        try {
            return RuntimeLog.isEnabled(priority) ? RuntimeLog.write(priority, tag, message.get(), null) : 0;
        } catch (RuntimeException | LinkageError unavailable) {
            // Diagnostic expressions and an unavailable sink must not change an intercepted call.
            return 0;
        }
    }

    public static int v(String tag, String msg) {
        return RuntimeLog.write(Log.VERBOSE, tag, msg, null);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return RuntimeLog.write(Log.VERBOSE, tag, msg, tr);
    }

    
    public static int d(String tag, String msg) {
        return RuntimeLog.write(Log.DEBUG, tag, msg, null);
    }

    
    public static int d(String tag, String msg, Throwable tr) {
        return RuntimeLog.write(Log.DEBUG, tag, msg, tr);
    }

    
    public static int i(String tag, String msg) {
        return RuntimeLog.write(Log.INFO, tag, msg, null);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return RuntimeLog.write(Log.INFO, tag, msg, tr);
    }

    
    public static int w(String tag, String msg) {
        return RuntimeLog.write(Log.WARN, tag, msg, null);
    }

    
    public static int w(String tag, String msg, Throwable tr) {
        return RuntimeLog.write(Log.WARN, tag, msg, tr);
    }

    public static int w(String tag, Throwable tr) {
        return RuntimeLog.write(Log.WARN, tag, "Exception", tr);
    }

    
    public static int e(String tag, String msg) {
        return RuntimeLog.write(Log.ERROR, tag, msg, null);
    }

    
    public static int e(String tag, String msg, Throwable tr) {
        return RuntimeLog.write(Log.ERROR, tag, msg, tr);
    }

    public static int println(int priority, String tag, String msg) {
        return RuntimeLog.write(priority, tag, msg, null);
    }

    public static int wtf(String tag, String msg) { return e(tag, msg); }
    public static int wtf(String tag, String msg, Throwable error) { return e(tag, msg, error); }
}
