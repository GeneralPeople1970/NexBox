package com.nexbox.core.utils.compat;

import android.content.BroadcastReceiver;
import android.os.Process;

import com.nexbox.core.entity.am.PendingResultData;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public final class BroadcastReceiverCompat {
    private BroadcastReceiverCompat() {}

    public static BroadcastReceiver.PendingResult createResult(PendingResultData data) {
        if (data == null || data.mToken == null || data.mFinished || data.mType < 0 || data.mType > 2) {
            throw new IllegalArgumentException("Invalid virtual PendingResult state");
        }
        Constructor<?> constructor;
        try {
            constructor = FrameworkMemberAccess.pendingResultConstructor();
            FrameworkMemberAccess.Member.RECEIVER_RESULT.resolve();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PendingResult member access unavailable", failure);
        }
        Object[] arguments = constructor.getParameterTypes().length == 9
                ? new Object[]{data.mResultCode, data.mResultData, data.mResultExtras, data.mType,
                    data.mOrderedHint, data.mInitialStickyHint, data.mToken, data.mSendingUser, data.mFlags}
                : new Object[]{data.mResultCode, data.mResultData, data.mResultExtras, data.mType,
                    data.mOrderedHint, data.mInitialStickyHint,
                    data.mType != 0 && (!data.mOrderedHint || data.mType == 2), data.mToken,
                    data.mSendingUser, data.mFlags, Process.INVALID_UID, null};
        try {
            BroadcastReceiver.PendingResult result = (BroadcastReceiver.PendingResult) constructor.newInstance(arguments);
            if (data.mAbortBroadcast && data.mOrderedHint) result.abortBroadcast();
            return result;
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("PendingResult construction failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PendingResult construction failed", failure);
        }
    }

    public static void attach(BroadcastReceiver receiver, BroadcastReceiver.PendingResult result)
            throws ReflectiveOperationException {
        if (result == null) throw new IllegalArgumentException("Missing broadcast result");
        FrameworkMemberAccess.Member.RECEIVER_RESULT.write(receiver, result);
    }

    public static BroadcastReceiver.PendingResult current(BroadcastReceiver receiver)
            throws ReflectiveOperationException {
        return (BroadcastReceiver.PendingResult) FrameworkMemberAccess.Member.RECEIVER_RESULT.read(receiver);
    }

    /** A null field means goAsync() transferred ownership; never finish another delivery's result. */
    public static boolean detach(BroadcastReceiver receiver, BroadcastReceiver.PendingResult expected)
            throws ReflectiveOperationException {
        BroadcastReceiver.PendingResult current = current(receiver);
        if (current == null) return false;
        if (current != expected) throw new IllegalStateException("Broadcast result ownership changed");
        FrameworkMemberAccess.Member.RECEIVER_RESULT.write(receiver, null);
        return true;
    }
}
