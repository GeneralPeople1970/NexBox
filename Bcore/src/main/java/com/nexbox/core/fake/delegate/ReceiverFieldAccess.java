package com.nexbox.core.fake.delegate;

import android.content.Context;

import com.nexbox.core.utils.compat.FrameworkMemberAccess;
import com.nexbox.reflection.utils.MemberCache;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Restricted-field fallback for framework receiver metadata, not process-wide exemptions. */
final class ReceiverFieldAccess {
    private ReceiverFieldAccess() {
    }

    static Object read(Object target, String name) throws ReflectiveOperationException {
        return field(target, name).get(target);
    }

    static Field field(Object target, String name) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        if (isFrameworkReceiverType(type)) {
            return FrameworkMemberAccess.receiverField(type, name);
        }
        // App-defined wrappers can still expose ordinary fields, but never enter ART access.
        return accessibleInstanceField(MemberCache.forType(type).field(name));
    }

    static boolean isFrameworkReceiverType(Class<?> type) {
        String name = type.getName();
        return ("android.app.LoadedApk".equals(name)
                || "android.app.LoadedApk$ReceiverDispatcher".equals(name)
                || "android.app.LoadedApk$ReceiverDispatcher$InnerReceiver".equals(name))
                && type.getClassLoader() == Context.class.getClassLoader();
    }

    private static Field accessibleInstanceField(Field field) {
        if (Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException("Receiver field must be an instance member: " + field.getName());
        }
        field.setAccessible(true);
        return field;
    }

}
