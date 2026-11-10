package com.nexbox.core.core.connection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Shared, bounded startup attempt; no periodic work remains after success or failure. */
public final class ServiceReadiness {
    public enum State { IDLE, CONNECTING, READY, FAILED }

    public interface Scheduler {
        Runnable schedule(Runnable task, long delayMs);
    }

    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Supplier<CompletableFuture<Boolean>> connect;
    private final LongSupplier nanoTime;
    private final long timeoutMs;
    private final long retryDelayMs;
    private State state = State.IDLE;
    private Attempt current;
    private long invalidation;
    private long failedAtNanos;

    public ServiceReadiness(Scheduler scheduler, Supplier<CompletableFuture<Boolean>> connect,
                            LongSupplier nanoTime, long timeoutMs, long retryDelayMs) {
        this.scheduler = scheduler;
        this.connect = connect;
        this.nanoTime = nanoTime;
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.retryDelayMs = Math.max(1L, retryDelayMs);
    }

    public State getState() {
        synchronized (lock) {
            return state;
        }
    }

    public CompletableFuture<Boolean> request() {
        Attempt attempt;
        synchronized (lock) {
            if (state == State.READY) return CompletableFuture.completedFuture(true);
            if (state == State.CONNECTING) return current.result;
            if (state == State.FAILED && nanoTime.getAsLong() - failedAtNanos
                    < TimeUnit.MILLISECONDS.toNanos(retryDelayMs)) {
                return CompletableFuture.completedFuture(false);
            }
            attempt = new Attempt(nanoTime.getAsLong());
            current = attempt;
            state = State.CONNECTING;
            attempt.cancelTimeout = scheduler.schedule(() -> finish(attempt, false), timeoutMs);
        }
        runAttempt(attempt);
        return attempt.result;
    }

    public boolean await(long requestedTimeoutMs) {
        return Boolean.TRUE.equals(ServiceConnection.await(request(),
                Math.max(0L, Math.min(requestedTimeoutMs, timeoutMs))));
    }

    public void invalidate() {
        synchronized (lock) {
            invalidation++;
            if (state != State.CONNECTING) {
                state = State.IDLE;
                current = null;
            }
        }
    }

    private void runAttempt(Attempt attempt) {
        long version;
        synchronized (lock) {
            if (current != attempt || state != State.CONNECTING) return;
            version = invalidation;
        }
        CompletableFuture<Boolean> result;
        try {
            result = connect.get();
        } catch (RuntimeException failure) {
            retry(attempt);
            return;
        }
        result.whenComplete((ready, failure) -> {
            boolean valid;
            synchronized (lock) {
                valid = version == invalidation;
            }
            if (failure == null && Boolean.TRUE.equals(ready) && valid) {
                finish(attempt, true, version);
            } else {
                retry(attempt);
            }
        });
    }

    private void retry(Attempt attempt) {
        boolean expired;
        synchronized (lock) {
            if (current != attempt || state != State.CONNECTING) return;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - attempt.startedAtNanos);
            long remainingMs = timeoutMs - elapsedMs;
            expired = remainingMs <= 0;
            if (!expired) {
                attempt.cancelRetry = scheduler.schedule(() -> runAttempt(attempt),
                        Math.min(retryDelayMs, remainingMs));
            }
        }
        if (expired) finish(attempt, false);
    }

    private void finish(Attempt attempt, boolean ready) {
        finish(attempt, ready, -1);
    }

    private void finish(Attempt attempt, boolean ready, long expectedInvalidation) {
        boolean changed;
        synchronized (lock) {
            if (current != attempt || state != State.CONNECTING) return;
            if (ready && nanoTime.getAsLong() - attempt.startedAtNanos
                    >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) ready = false;
            changed = ready && invalidation != expectedInvalidation;
            if (!changed) {
                state = ready ? State.READY : State.FAILED;
                current = null;
                if (!ready) failedAtNanos = nanoTime.getAsLong();
                attempt.cancelTimeout.run();
                attempt.cancelRetry.run();
            }
        }
        if (changed) retry(attempt);
        else attempt.result.complete(ready);
    }

    private static final class Attempt {
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        final long startedAtNanos;
        Runnable cancelTimeout = () -> {};
        Runnable cancelRetry = () -> {};

        Attempt(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }
    }
}
