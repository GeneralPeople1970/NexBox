package com.nexbox.core.fake.delegate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.utils.Slog;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;

/** Resolves a registered receiver by Binder identity, never by guest-supplied intent data. */
final class ReceiverDispatcherCompat {
    private final Object dispatcher;
    private final Field forgottenField;
    final BroadcastReceiver receiver;
    final Context context;
    final Handler handler;

    private ReceiverDispatcherCompat(Object dispatcher) throws Exception {
        this.dispatcher = dispatcher;
        receiver = (BroadcastReceiver) ReceiverFieldAccess.read(dispatcher, "mReceiver");
        context = (Context) ReceiverFieldAccess.read(dispatcher, "mContext");
        handler = (Handler) ReceiverFieldAccess.read(dispatcher, "mActivityThread");
        forgottenField = ReceiverFieldAccess.field(dispatcher, "mForgotten");
        if (receiver == null || context == null || handler == null || forgottenField.getType() != boolean.class) {
            throw new IllegalStateException("Incomplete receiver dispatcher");
        }
    }

    boolean isForgotten() throws IllegalAccessException {
        // Retain metadata, not a snapshot: unregisterReceiver changes this flag before delivery.
        return forgottenField.getBoolean(dispatcher);
    }

    static ReceiverDispatcherCompat resolve(IIntentReceiver original) {
        IBinder binder = original.asBinder();
        if (binder == null) throw new IllegalArgumentException("Receiver Binder is missing");
        Exception firstFailure;
        try {
            ReceiverDispatcherCompat resolved = fromReceiver(original, binder);
            Slog.dLazy("InnerReceiverDelegate", () -> "action=resolveReceiverDispatcher source=inner_receiver");
            return resolved;
        } catch (Exception failure) {
            firstFailure = failure;
        }
        // Wrappers can expose the framework Binder without themselves being its InnerReceiver.
        try {
            IInterface local = binder.queryLocalInterface("android.content.IIntentReceiver");
            Object source = local != null ? local : binder;
            if (source != original) {
                ReceiverDispatcherCompat resolved = fromReceiver(source, binder);
                Slog.dLazy("InnerReceiverDelegate", () -> "action=resolveReceiverDispatcher source=local_binder");
                return resolved;
            }
        } catch (Exception failure) {
            firstFailure.addSuppressed(failure);
        }
        try {
            BActivityThread thread = BActivityThread.currentActivityThread();
            Object loadedApk = thread.isInit() ? thread.getPackageInfo() : null;
            if (loadedApk != null) {
                Object receivers = ReceiverFieldAccess.read(loadedApk, "mReceivers");
                if (receivers instanceof Map<?, ?>) {
                    // LoadedApk serializes registration/removal on this exact map.
                    synchronized (receivers) {
                        for (Object registrations : ((Map<?, ?>) receivers).values()) {
                            if (!(registrations instanceof Map<?, ?>)) continue;
                            for (Object dispatcher : ((Map<?, ?>) registrations).values()) {
                                if (matches(dispatcher, binder)) {
                                    ReceiverDispatcherCompat resolved = new ReceiverDispatcherCompat(dispatcher);
                                    Slog.dLazy("InnerReceiverDelegate", () -> "action=resolveReceiverDispatcher source=framework_registry");
                                    return resolved;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception failure) {
            firstFailure.addSuppressed(failure);
        }
        IllegalStateException failure = new IllegalStateException(
                "Receiver dispatcher unavailable for " + original.getClass().getName(), firstFailure);
        Slog.e("InnerReceiverDelegate", "action=registerReceiver stage=resolveDispatcher result=failed"
                + " receiverType=" + original.getClass().getName()
                + " binderType=" + binder.getClass().getName(), failure);
        throw failure;
    }

    private static ReceiverDispatcherCompat fromReceiver(Object source, IBinder binder) throws Exception {
        Object reference = ReceiverFieldAccess.read(source, "mDispatcher");
        Object dispatcher = reference instanceof Reference<?> ? ((Reference<?>) reference).get() : reference;
        if (!matches(dispatcher, binder)) {
            throw new IllegalStateException("Receiver dispatcher does not match its Binder");
        }
        return new ReceiverDispatcherCompat(dispatcher);
    }

    private static boolean matches(Object dispatcher, IBinder binder) throws Exception {
        if (dispatcher == null) return false;
        Object registered = ReceiverFieldAccess.read(dispatcher, "mIIntentReceiver");
        return registered instanceof IIntentReceiver
                && binder.equals(((IIntentReceiver) registered).asBinder());
    }
}
