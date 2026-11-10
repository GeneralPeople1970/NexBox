package com.nexbox.core.app;

import android.content.BroadcastReceiver;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.entity.am.PendingResultData;
import com.nexbox.core.utils.Slog;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Routes framework PendingResult.finish() back to its virtual delivery. */
public final class GuestBroadcastCompletion {
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<IBinder, Entry> PENDING = new HashMap<>();
    // PendingResult retains its token, so late/duplicate finishes remain recognized without
    // retaining every completed delivery for the lifetime of the guest process.
    private static final Map<IBinder, Boolean> OWNED = new WeakHashMap<>();
    private static final long RETENTION_MS = 60_000;
    private static final AtomicLong NEXT_DELIVERY = new AtomicLong();

    private GuestBroadcastCompletion() {
    }

    public static boolean register(PendingResultData data) {
        if (data == null || data.mToken == null || data.mBToken == null || data.mFinished) {
            throw new IllegalArgumentException("Incomplete virtual broadcast completion");
        }
        synchronized (PENDING) {
            if (OWNED.containsKey(data.mToken)) return false;
            OWNED.put(data.mToken, Boolean.TRUE);
            Entry entry = new Entry(data);
            PENDING.put(data.mToken, entry);
            if (!HANDLER.postDelayed(entry.expire, RETENTION_MS)) {
                PENDING.remove(data.mToken);
                throw new IllegalStateException("Broadcast completion queue unavailable");
            }
        }
        return true;
    }

    public static boolean finish(IBinder token, int code, String result,
                                 Bundle extras, boolean abort) {
        return finish(token, code, result, extras, abort, "receiverFinished");
    }

    private static boolean finish(IBinder token, int code, String result,
                                  Bundle extras, boolean abort, String outcome) {
        Entry entry;
        synchronized (PENDING) {
            if (!OWNED.containsKey(token)) {
                return false;
            }
            entry = PENDING.remove(token);
        }
        if (entry != null) {
            HANDLER.removeCallbacks(entry.expire);
            PendingResultData data = entry.data;
            data.mResultCode = code;
            data.mResultData = result;
            data.mResultExtras = extras;
            data.mAbortBroadcast = abort;
            data.mFinished = true;
            report(data, entry.id, outcome);
        }
        return true;
    }

    public static void fail(PendingResultData original, BroadcastReceiver.PendingResult pending) {
        int code = original.mResultCode;
        String data = original.mResultData;
        Bundle extras = original.mResultExtras;
        boolean abort = original.mAbortBroadcast;
        if (pending != null) {
            try {
                code = pending.getResultCode();
                data = pending.getResultData();
                extras = pending.getResultExtras(false);
                abort = pending.getAbortBroadcast();
            } catch (Throwable error) {
                Slog.e("GuestBroadcastCompletion", "action=finishBroadcast stage=readFailureResult result=failed", error);
            }
        }
        finish(original.mToken, code, data, extras, abort, "deliveryFailed");
    }

    private static void report(PendingResultData data, long localId, String outcome) {
        try {
            IBActivityManagerService service = NexBoxCore.getBActivityManager().getService();
            if (service == null) throw new IllegalStateException("Virtual activity service unavailable");
            service.finishBroadcast(data);
            Slog.dLazy("GuestBroadcastCompletion", () -> "action=finishBroadcast stage=completionDispatched"
                    + " delivery=" + localId + " outcome=" + outcome);
        } catch (Throwable error) {
            // Server-side timeout/process death still releases the host delivery.
            Slog.e("GuestBroadcastCompletion", "action=finishBroadcast stage=completionDispatch"
                    + " delivery=" + localId + " outcome=" + outcome + " result=failed", error);
        }
    }

    private static final class Entry {
        final PendingResultData data;
        final Runnable expire;
        final long id = NEXT_DELIVERY.incrementAndGet();

        Entry(PendingResultData data) {
            this.data = data;
            expire = () -> {
                boolean expired = false;
                synchronized (PENDING) {
                    if (PENDING.get(data.mToken) == this) {
                        PENDING.remove(data.mToken);
                        expired = true;
                    }
                }
                if (expired) Slog.w("GuestBroadcastCompletion", "action=finishBroadcast stage=localRetentionExpired"
                        + " delivery=" + id + " result=unconfirmed");
            };
        }
    }
}
