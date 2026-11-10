package com.nexbox.core.utils.compat;

import android.app.PendingIntent;
import android.os.IInterface;

import java.lang.reflect.Field;

public final class PendingIntentCompat {
    private PendingIntentCompat() {}

    /** Resolve before creating a system token: a failed capability probe has no creation side effect. */
    public static void requireSenderAccess(Class<?> returnType) throws ReflectiveOperationException {
        Field field = FrameworkMemberAccess.Member.INTENT_SENDER.resolve();
        if (!IInterface.class.isAssignableFrom(returnType) || !returnType.isAssignableFrom(field.getType())) {
            throw new IllegalStateException("Unexpected getIntentSender return type");
        }
    }

    public static IInterface getSender(PendingIntent pending) throws ReflectiveOperationException {
        Object target = FrameworkMemberAccess.Member.INTENT_SENDER.read(pending);
        if (!(target instanceof IInterface) || ((IInterface) target).asBinder() == null) {
            throw new IllegalStateException("PendingIntent has no framework sender Binder");
        }
        return (IInterface) target;
    }
}
