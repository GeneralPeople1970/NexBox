package com.nexbox.core.fake.delegate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import java.util.HashMap;
import java.util.Map;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.GuestBroadcastCompletion;
import com.nexbox.core.app.GuestBroadcastDispatcher;
import com.nexbox.core.core.system.am.IBroadcastDelivery;
import com.nexbox.core.entity.am.ReceiverData;
import com.nexbox.core.utils.Slog;

/** OS callbacks remain OS callbacks; virtual callbacks carry server-issued completion tokens. */
public final class InnerReceiverDelegate {
    private static final Map<IBinder, InnerReceiverDelegate> RECEIVERS = new HashMap<>();
    private final IIntentReceiver original;
    private final ReceiverDispatcherCompat dispatcher;
    private final BroadcastReceiver receiver;
    private final Context context;
    private final Handler handler;
    private volatile boolean registered;
    private final IBroadcastDelivery delivery = new IBroadcastDelivery.Stub() {
        @Override public void receive(ReceiverData data) {
            if (data == null || data.data == null || data.data.mToken == null || data.intent == null) {
                throw new IllegalArgumentException("Incomplete virtual broadcast delivery");
            }
            if (!GuestBroadcastCompletion.register(data.data)) return;
            if (!handler.post(() -> receiveVirtual(data))) {
                GuestBroadcastCompletion.fail(data.data, null);
                Slog.e("InnerReceiverDelegate", "action=deliverBroadcast stage=handlerPost result=failed");
            }
        }
    };

    private InnerReceiverDelegate(IIntentReceiver original) {
        this.original = original;
        dispatcher = ReceiverDispatcherCompat.resolve(original);
        receiver = dispatcher.receiver;
        context = dispatcher.context;
        handler = dispatcher.handler;
    }

    public static synchronized InnerReceiverDelegate getDelegate(IBinder binder) { return RECEIVERS.get(binder); }

    public static synchronized InnerReceiverDelegate createProxy(IIntentReceiver base) {
        if (base == null || base.asBinder() == null) throw new IllegalArgumentException("Receiver Binder is missing");
        InnerReceiverDelegate delegate = RECEIVERS.get(base.asBinder());
        if (delegate == null) {
            delegate = new InnerReceiverDelegate(base);
            RECEIVERS.put(base.asBinder(), delegate);
        }
        return delegate;
    }

    public void registerVirtual(IntentFilter filter, String permission, int flags) throws RemoteException {
        boolean previous = registered;
        registered = true;
        try {
            NexBoxCore.getBActivityManager().getService().registerBroadcastReceiver(delivery.asBinder(), filter, permission, flags);
            Slog.dLazy("InnerReceiverDelegate", () -> "action=registerReceiver stage=virtualRegistered"
                    + " flags=" + flags + " actions=" + (filter == null ? 0 : filter.countActions()));
        } catch (RemoteException | RuntimeException failure) {
            registered = previous;
            throw failure;
        }
    }

    public void unregisterVirtual() throws RemoteException {
        registered = false;
        try {
            NexBoxCore.getBActivityManager().getService().unregisterBroadcastReceiver(delivery.asBinder());
            Slog.dLazy("InnerReceiverDelegate", () -> "action=unregisterReceiver stage=virtualUnregistered");
        } finally {
            discardIfUnregistered();
        }
    }

    public void discardIfUnregistered() {
        synchronized (InnerReceiverDelegate.class) {
            if (!registered) {
                RECEIVERS.remove(original.asBinder(), this);
            }
        }
    }

    private void receiveVirtual(ReceiverData data) {
        try {
            if (!registered || dispatcher.isForgotten()) {
                GuestBroadcastCompletion.fail(data.data, null);
                Slog.dLazy("InnerReceiverDelegate", () -> "action=deliverBroadcast stage=registrationCheck"
                        + " result=cancelled reason=unregistered");
                return;
            }
            GuestBroadcastDispatcher.dispatch(receiver, context, data, "dynamic");
        } catch (Throwable failure) {
            GuestBroadcastCompletion.fail(data.data, null);
            Slog.e("InnerReceiverDelegate", "action=deliverBroadcast stage=registrationCheck result=failed", failure);
        }
    }

}
