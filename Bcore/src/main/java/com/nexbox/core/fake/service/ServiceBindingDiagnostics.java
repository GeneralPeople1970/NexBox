package com.nexbox.core.fake.service;

import android.os.SystemClock;
import java.util.LinkedHashMap;
import java.util.Map;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.utils.Slog;

/** Identifiers only: never record service extras, URIs, tokens or arbitrary exception messages. */
final class ServiceBindingDiagnostics {
    private static final long INTERVAL_MS = 5_000L;
    private static final Map<String, Window> WINDOWS = new LinkedHashMap<>();

    private static final class Window {
        long lastLog;
        int suppressed;
    }

    private ServiceBindingDiagnostics() { }

    static void report(String method, String stage, String route, String component,
            String callerBefore, String callerAfter, int virtualUser, long flagsBefore, long flagsAfter,
            String instanceBefore, String instanceAfter, Object result, Throwable failure) {
        report(method, stage, route, component, callerBefore, callerAfter, virtualUser, flagsBefore,
                flagsAfter, instanceBefore, instanceAfter, result, failure, null, "unchanged");
    }

    static void report(String method, String stage, String route, String component,
            String callerBefore, String callerAfter, int virtualUser, long flagsBefore, long flagsAfter,
            String instanceBefore, String instanceAfter, Object result, Throwable failure,
            OaidServiceBinder.RequestInfo oaid, String connectionState) {
        try {
            reportSafely(method, stage, route, component, callerBefore, callerAfter, virtualUser,
                    flagsBefore, flagsAfter, instanceBefore, instanceAfter, result, failure, oaid, connectionState);
        } catch (Throwable ignored) {
            // Diagnostics must never replace the real bind result or its original exception.
        }
    }

    private static void reportSafely(String method, String stage, String route, String component,
            String callerBefore, String callerAfter, int virtualUser, long flagsBefore, long flagsAfter,
            String instanceBefore, String instanceAfter, Object result, Throwable failure,
            OaidServiceBinder.RequestInfo oaid, String connectionState) {
        boolean success = failure == null && result instanceof Number && ((Number) result).intValue() > 0;
        if (success && oaid == null && instanceBefore == null && !ServiceBindingPolicy.isExternal(flagsBefore)) return;
        String outcome = failure == null ? "result=" + (result instanceof Number ? result : "unavailable")
                : "error=" + failure.getClass().getSimpleName();
        // Do not key on the instance token: Chromium can generate a new one on every attempt.
        String key = virtualUser + "/" + callerBefore + '/' + method + '/' + stage + '/' + route + '/' + component + '/' + outcome
                + (oaid == null ? "" : '/' + oaid.key());
        int suppressed;
        long now = SystemClock.elapsedRealtime();
        synchronized (WINDOWS) {
            Window window = WINDOWS.get(key);
            if (window == null) {
                if (WINDOWS.size() >= 64) WINDOWS.remove(WINDOWS.keySet().iterator().next());
                window = new Window();
                WINDOWS.put(key, window);
            } else if (now - window.lastLog < INTERVAL_MS) {
                window.suppressed++;
                return;
            }
            suppressed = window.suppressed;
            window.suppressed = 0;
            window.lastLog = now;
        }
        String message = "action=bindService method=" + method + " stage=" + stage + " route=" + route
                + " component=" + component + " callerBefore=" + callerBefore + " callerAfter=" + callerAfter
                + " uid=" + NexBoxCore.getHostUid() + " virtualUser=" + virtualUser
                + " hostUser=" + NexBoxCore.getHostUserId()
                + " flagsBefore=0x" + Long.toHexString(flagsBefore) + " flagsAfter=0x" + Long.toHexString(flagsAfter)
                + " instanceBefore=" + (instanceBefore == null ? "absent" : "length:" + instanceBefore.length())
                + " instanceAfter=" + (instanceAfter == null ? "absent" : "length:" + instanceAfter.length())
                + " " + outcome + " suppressed=" + suppressed
                + (oaid == null ? "" : oaid.details + " connectionState=" + connectionState);
        if (success) Slog.d("ServiceBinding", message);
        else Slog.w("ServiceBinding", message);
    }
}
