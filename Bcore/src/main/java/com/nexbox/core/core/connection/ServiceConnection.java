package com.nexbox.core.core.connection;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** One acquisition and one death registration per cached endpoint. */
public final class ServiceConnection<T> {
    public interface Endpoint<T> {
        T connect() throws Exception;
        boolean isAlive(T value);
        AutoCloseable observeDeath(T value, Runnable onDeath) throws Exception;
    }

    private final Object lock = new Object();
    private final Executor executor;
    private final Endpoint<T> endpoint;
    private final Runnable onInvalidated;
    private final LongSupplier nanoTime;
    private final long retryDelayNanos;
    private Entry cached;
    private CompletableFuture<T> inFlight;
    private boolean failed;
    private long failedAtNanos;

    public ServiceConnection(Executor executor, Endpoint<T> endpoint, Runnable onInvalidated,
                             LongSupplier nanoTime, long retryDelayMs) {
        this.executor = executor;
        this.endpoint = endpoint;
        this.onInvalidated = onInvalidated;
        this.nanoTime = nanoTime;
        retryDelayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, retryDelayMs));
    }

    public T peek() {
        Entry entry;
        synchronized (lock) {
            entry = cached;
        }
        if (entry == null) return null;
        if (entry.isInvalidated() || !endpoint.isAlive(entry.value)) {
            invalidate(entry);
            return null;
        }
        return entry.value;
    }

    public CompletableFuture<T> request() {
        T value = peek();
        if (value != null) return CompletableFuture.completedFuture(value);
        CompletableFuture<T> attempt;
        synchronized (lock) {
            if (cached != null) return CompletableFuture.completedFuture(cached.value);
            if (inFlight != null) return inFlight;
            if (failed && nanoTime.getAsLong() - failedAtNanos < retryDelayNanos) {
                return CompletableFuture.completedFuture(null);
            }
            attempt = new CompletableFuture<>();
            inFlight = attempt;
        }
        try {
            executor.execute(() -> acquire(attempt));
        } catch (RuntimeException failure) {
            finish(attempt, null);
        }
        return attempt;
    }

    public T await(long timeoutMs) {
        T value = peek();
        if (value != null) return value;
        return await(request(), timeoutMs);
    }

    public static <V> V await(CompletableFuture<V> future, long timeoutMs) {
        try {
            return future.get(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            // A cancelled waiter must not cancel the shared acquisition for other callers.
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failure) {
            // Failure remains visible as an unavailable service, never a fake fallback.
        }
        return null;
    }

    public void invalidate(T expected) {
        Entry entry;
        synchronized (lock) {
            entry = cached;
            if (entry == null || entry.value != expected) return;
        }
        invalidate(entry);
    }

    public void clear() {
        Entry entry;
        CompletableFuture<T> attempt;
        synchronized (lock) {
            entry = cached;
            cached = null;
            attempt = inFlight;
            inFlight = null;
            failed = false;
        }
        if (entry != null) entry.close();
        if (attempt != null) attempt.complete(null);
        onInvalidated.run();
    }

    private void acquire(CompletableFuture<T> attempt) {
        synchronized (lock) {
            if (inFlight != attempt) return;
        }
        Entry entry = null;
        try {
            T value = endpoint.connect();
            if (value != null && endpoint.isAlive(value)) {
                entry = new Entry(value);
                Entry observed = entry;
                entry.attach(endpoint.observeDeath(value, () -> invalidate(observed)));
                if (!endpoint.isAlive(value)) entry.close();
            }
        } catch (Exception failure) {
            if (entry != null) entry.close();
            entry = null;
        } catch (Error failure) {
            if (entry != null) entry.close();
            finish(attempt, null);
            throw failure;
        }
        finish(attempt, entry);
    }

    private void finish(CompletableFuture<T> attempt, Entry entry) {
        T value = null;
        boolean accepted = false;
        synchronized (lock) {
            if (inFlight == attempt) {
                inFlight = null;
                if (entry != null && !entry.isInvalidated()) {
                    cached = entry;
                    value = entry.value;
                    failed = false;
                    accepted = true;
                } else {
                    failed = true;
                    failedAtNanos = nanoTime.getAsLong();
                }
            }
        }
        if (!accepted && entry != null) entry.close();
        attempt.complete(value);
    }

    private void invalidate(Entry expected) {
        expected.close();
        boolean removed;
        synchronized (lock) {
            removed = cached == expected;
            if (removed) {
                cached = null;
                failed = false;
            }
        }
        if (removed) onInvalidated.run();
    }

    private final class Entry {
        private final T value;
        private AutoCloseable registration;
        private boolean invalidated;

        private Entry(T value) {
            this.value = value;
        }

        synchronized boolean isInvalidated() {
            return invalidated;
        }

        void attach(AutoCloseable registration) {
            boolean discard;
            synchronized (this) {
                discard = invalidated;
                if (!discard) this.registration = registration;
            }
            if (discard) closeRegistration(registration);
        }

        void close() {
            AutoCloseable old;
            synchronized (this) {
                invalidated = true;
                old = registration;
                registration = null;
            }
            closeRegistration(old);
        }

        private void closeRegistration(AutoCloseable old) {
            if (old == null) return;
            try {
                old.close();
            } catch (Exception ignored) {
                // Dead Binder implementations may already have removed their recipient.
            }
        }
    }
}
