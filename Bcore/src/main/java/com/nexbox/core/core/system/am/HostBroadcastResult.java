package com.nexbox.core.core.system.am;

import android.content.BroadcastReceiver;

import com.nexbox.core.entity.am.PendingResultData;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Owns the actual OS result, including platform-only completion hints and token identity. */
final class HostBroadcastResult {
    private final BroadcastReceiver.PendingResult original;
    private final Consumer<BroadcastReceiver.PendingResult> finisher;
    private final AtomicBoolean finished = new AtomicBoolean();

    HostBroadcastResult(BroadcastReceiver.PendingResult original) {
        this(original, BroadcastReceiver.PendingResult::finish);
    }

    HostBroadcastResult(BroadcastReceiver.PendingResult original,
                        Consumer<BroadcastReceiver.PendingResult> finisher) {
        this.original = Objects.requireNonNull(original, "Host broadcast result is missing");
        this.finisher = Objects.requireNonNull(finisher);
    }

    PendingResultData snapshot() {
        return new PendingResultData(original);
    }

    boolean finish(PendingResultData state) {
        if (!finished.compareAndSet(false, true)) return false;
        Throwable failure = null;
        try {
            if (state != null && state.mOrderedHint) {
                original.setResult(state.mResultCode, state.mResultData, state.mResultExtras);
                if (state.mAbortBroadcast) original.abortBroadcast();
                else original.clearAbortBroadcast();
            }
        } catch (RuntimeException | Error error) {
            failure = error;
        }
        try {
            finisher.accept(original);
        } catch (RuntimeException | Error error) {
            if (failure == null) failure = error;
            else if (failure != error) failure.addSuppressed(error);
        }
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw (RuntimeException) failure;
        return true;
    }
}
