package com.nexbox.core.app;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** A guest runtime belongs to exactly one core Binder lifetime; it cannot be rebound. */
final class GuestCoreConnection {
    private final Runnable onDisconnected;
    private final AtomicBoolean disconnected = new AtomicBoolean();
    private IBinder coreToken;

    GuestCoreConnection(Runnable onDisconnected) {
        this.onDisconnected = Objects.requireNonNull(onDisconnected);
    }

    synchronized void bind(IBinder token) {
        Objects.requireNonNull(token, "Core process token is required");
        if (disconnected.get()) throw new IllegalStateException("The guest's core process has exited");
        if (coreToken != null && !coreToken.equals(token)) {
            throw new IllegalStateException("Cannot attach an existing guest to a different core process");
        }
        if (coreToken == null) {
            coreToken = token;
            try {
                token.linkToDeath(this::disconnect, 0);
            } catch (RemoteException failure) {
                disconnect();
                throw new IllegalStateException("The core process exited during guest attachment", failure);
            }
        }
        if (!token.isBinderAlive() || disconnected.get()) {
            disconnect();
            throw new IllegalStateException("The core process exited during guest attachment");
        }
    }

    private void disconnect() {
        if (disconnected.compareAndSet(false, true)) onDisconnected.run();
    }
}
