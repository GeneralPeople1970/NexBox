package com.nexbox.core.app;

import android.content.BroadcastReceiver;
import android.content.Context;

import com.nexbox.core.entity.am.ReceiverData;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BroadcastReceiverCompat;

/** Called on the receiver's original Handler, for both registered and manifest receivers. */
public final class GuestBroadcastDispatcher {
    private GuestBroadcastDispatcher() {}

    public static void dispatch(BroadcastReceiver receiver, Context context, ReceiverData data, String kind) {
        BroadcastReceiver.PendingResult result = null;
        String stage = "createResult";
        try {
            ClassLoader loader = receiver.getClass().getClassLoader();
            data.intent.setExtrasClassLoader(loader);
            if (data.data.mResultExtras != null) data.data.mResultExtras.setClassLoader(loader);
            result = data.data.build();
            stage = "attachResult";
            BroadcastReceiverCompat.attach(receiver, result);
            stage = "callback";
            receiver.onReceive(context, data.intent);
            stage = "finishResult";
            if (BroadcastReceiverCompat.detach(receiver, result)) {
                result.finish();
            } else {
                Slog.dLazy("GuestBroadcast", () -> "action=deliverBroadcast stage=asyncOwnership"
                        + " kind=" + kind + " result=pending");
            }
        } catch (Throwable failure) {
            if (result != null) {
                try {
                    BroadcastReceiverCompat.detach(receiver, result);
                } catch (ReflectiveOperationException | RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            GuestBroadcastCompletion.fail(data.data, result);
            Slog.e("GuestBroadcast", "action=deliverBroadcast stage=" + stage + " kind=" + kind
                    + " result=failed queueReleaseRequested=true", failure);
        }
    }
}
