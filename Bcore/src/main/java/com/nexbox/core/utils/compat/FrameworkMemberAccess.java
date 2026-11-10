package com.nexbox.core.utils.compat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;

import com.nexbox.core.utils.Slog;
import com.nexbox.reflection.utils.MemberCache;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit boot-class members only; never changes the process's hidden-API policy. */
public final class FrameworkMemberAccess {
    public enum Member {
        RECEIVERS("android.app.LoadedApk", "mReceivers", "java.util.Map"),
        RECEIVER("android.app.LoadedApk$ReceiverDispatcher", "mReceiver", "android.content.BroadcastReceiver"),
        RECEIVER_CONTEXT("android.app.LoadedApk$ReceiverDispatcher", "mContext", "android.content.Context"),
        RECEIVER_HANDLER("android.app.LoadedApk$ReceiverDispatcher", "mActivityThread", "android.os.Handler"),
        RECEIVER_FORGOTTEN("android.app.LoadedApk$ReceiverDispatcher", "mForgotten", "boolean"),
        RECEIVER_BINDER("android.app.LoadedApk$ReceiverDispatcher", "mIIntentReceiver", "android.content.IIntentReceiver"),
        RECEIVER_DISPATCHER("android.app.LoadedApk$ReceiverDispatcher$InnerReceiver", "mDispatcher", "receiver_reference"),
        INTENT_SENDER("android.app.PendingIntent", "mTarget", "android.content.IIntentSender"),
        RECEIVER_RESULT("android.content.BroadcastReceiver", "mPendingResult", "android.content.BroadcastReceiver$PendingResult", true),
        RESULT_TYPE("android.content.BroadcastReceiver$PendingResult", "mType", "int"),
        RESULT_ORDERED("android.content.BroadcastReceiver$PendingResult", "mOrderedHint", "boolean"),
        RESULT_STICKY("android.content.BroadcastReceiver$PendingResult", "mInitialStickyHint", "boolean"),
        RESULT_TOKEN("android.content.BroadcastReceiver$PendingResult", "mToken", "android.os.IBinder"),
        RESULT_USER("android.content.BroadcastReceiver$PendingResult", "mSendingUser", "int"),
        RESULT_FLAGS("android.content.BroadcastReceiver$PendingResult", "mFlags", "int"),
        RESULT_FINISHED("android.content.BroadcastReceiver$PendingResult", "mFinished", "boolean"),
        PROCESS_PACKAGE_DEPENDENCIES("android.app.ActivityManager$RunningAppProcessInfo", "pkgDeps", "[Ljava.lang.String;", true),
        PROCESS_REASON_IMPORTANCE("android.app.ActivityManager$RunningAppProcessInfo", "importanceReasonImportance", "int", true),
        ACTIVITY_TASK("android.app.Activity", "mTaskDescription", "android.app.ActivityManager$TaskDescription"),
        TASK_LABEL("android.app.ActivityManager$TaskDescription", "mLabel", "java.lang.String", true),
        TASK_ICON("android.app.ActivityManager$TaskDescription", "mIcon", "android.graphics.drawable.Icon", true),
        TASK_ICON_FILE("android.app.ActivityManager$TaskDescription", "mIconFilename", "java.lang.String");

        private final String owner;
        private final String name;
        private final String type;
        private final boolean writable;
        private volatile Field resolved;
        private volatile boolean confirmedAbsent;

        Member(String owner, String name, String type) {
            this(owner, name, type, false);
        }

        Member(String owner, String name, String type, boolean writable) {
            this.owner = owner;
            this.name = name;
            this.type = type;
            this.writable = writable;
        }

        public Field resolve() throws ReflectiveOperationException {
            Field field = resolved;
            if (field != null) return field;
            synchronized (this) {
                if (resolved != null) return resolved;
                Class<?> declaring = bootClass(owner);
                try {
                    field = checked(MemberCache.forType(declaring).field(name), declaring);
                } catch (NoSuchFieldException | SecurityException ordinaryFailure) {
                    try {
                        field = checked(restrictedFields(declaring).get(name), declaring);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError restrictedFailure) {
                        ReflectiveOperationException failure = new ReflectiveOperationException(
                                "Framework member inaccessible: " + owner + "." + name, ordinaryFailure);
                        failure.addSuppressed(restrictedFailure);
                        throw failure;
                    }
                }
                resolved = field;
                return field;
            }
        }

        public Object read(Object target) throws ReflectiveOperationException {
            Field field = resolve();
            requireTarget(field, target);
            return field.get(target);
        }

        public int readInt(Object target) throws ReflectiveOperationException {
            return (Integer) read(target);
        }

        public boolean readBoolean(Object target) throws ReflectiveOperationException {
            return (Boolean) read(target);
        }

        public void write(Object target, Object value) throws ReflectiveOperationException {
            if (!writable) throw new IllegalArgumentException("Framework member is read-only: " + name);
            Field field = resolve();
            requireTarget(field, target);
            field.set(target, value);
        }

        public void writeIfPresent(Object target, Object value) throws ReflectiveOperationException {
            if (!writable) throw new IllegalArgumentException("Framework member is read-only: " + name);
            if (confirmedAbsent) return;
            try {
                write(target, value);
                if (!java.util.Objects.equals(read(target), value)) {
                    throw new IllegalStateException("Framework member write was not retained: " + name);
                }
            } catch (ReflectiveOperationException failure) {
                // Hidden-API filtering is not evidence that a field was removed. Confirm
                // absence against the framework's actual instance-field inventory first.
                if (restrictedFields(bootClass(owner)).containsKey(name)) throw failure;
                confirmedAbsent = true;
            }
        }

        private Field checked(Field field, Class<?> declaring) throws ReflectiveOperationException {
            if (field == null) throw new NoSuchFieldException(owner + "." + name);
            if (field.getDeclaringClass() != declaring || Modifier.isStatic(field.getModifiers())) {
                throw new IllegalStateException("Framework member owner or access mismatch: " + owner + "." + name);
            }
            if (!matchesType(field.getType())) {
                throw new IllegalStateException("Framework member type mismatch: " + owner + "." + name
                        + " expectedType=" + type + " actualType=" + field.getType().getName()
                        + " access=" + (writable ? "read_write" : "read_only"));
            }
            field.setAccessible(true);
            return field;
        }

        private boolean matchesType(Class<?> actual) throws ClassNotFoundException {
            if ("receiver_reference".equals(type)) {
                return Reference.class.isAssignableFrom(actual)
                        || "android.app.LoadedApk$ReceiverDispatcher".equals(actual.getName());
            }
            if (type.equals(actual.getName())) return true;
            if (writable || actual.isPrimitive() || "int".equals(type) || "boolean".equals(type)) return false;
            // Read contracts allow a narrower declaration (for example IIntentReceiver.Stub).
            // Writes must keep the exact type: the caller may supply any value of that type.
            return bootClass(type).isAssignableFrom(actual);
        }
    }

    private static final Map<Class<?>, Map<String, Field>> RESTRICTED_FIELDS = new ConcurrentHashMap<>();
    private static volatile Constructor<?> pendingResultConstructor;

    private FrameworkMemberAccess() {}

    public static Field receiverField(Class<?> owner, String name) throws ReflectiveOperationException {
        if (owner.getClassLoader() != Context.class.getClassLoader()) {
            throw new IllegalArgumentException("Receiver owner is not a framework class");
        }
        for (Member member : Member.values()) {
            if (member.name.equals(name) && member.owner.equals(owner.getName())
                    && member.owner.startsWith("android.app.LoadedApk")) return member.resolve();
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    static Constructor<?> pendingResultConstructor() throws ReflectiveOperationException {
        Constructor<?> constructor = pendingResultConstructor;
        if (constructor != null) return constructor;
        synchronized (FrameworkMemberAccess.class) {
            if (pendingResultConstructor != null) return pendingResultConstructor;
            Class<?> owner = bootClass("android.content.BroadcastReceiver$PendingResult");
            Class<?>[] legacy = {int.class, String.class, Bundle.class, int.class, boolean.class,
                    boolean.class, IBinder.class, int.class, int.class};
            try {
                constructor = constructor(owner, legacy);
            } catch (ReflectiveOperationException legacyFailure) {
                Class<?>[] modern = {int.class, String.class, Bundle.class, int.class, boolean.class,
                        boolean.class, boolean.class, IBinder.class, int.class, int.class,
                        int.class, String.class};
                try {
                    constructor = constructor(owner, modern);
                } catch (ReflectiveOperationException modernFailure) {
                    ReflectiveOperationException failure = new ReflectiveOperationException(
                            "PendingResult constructor signatures unavailable", legacyFailure);
                    failure.addSuppressed(modernFailure);
                    throw failure;
                }
            }
            pendingResultConstructor = constructor;
            return constructor;
        }
    }

    private static Constructor<?> constructor(Class<?> owner, Class<?>[] parameters)
            throws ReflectiveOperationException {
        Constructor<?> constructor;
        try {
            constructor = MemberCache.forType(owner).constructor(parameters);
            constructor.setAccessible(true);
        } catch (NoSuchMethodException | SecurityException ordinaryFailure) {
            try {
                constructor = HiddenApiBypass.getDeclaredConstructor(owner, parameters);
                constructor.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError restrictedFailure) {
                ReflectiveOperationException failure = new ReflectiveOperationException(
                        "PendingResult constructor inaccessible: parameterCount=" + parameters.length,
                        ordinaryFailure);
                failure.addSuppressed(restrictedFailure);
                throw failure;
            }
        }
        if (constructor.getDeclaringClass() != BroadcastReceiver.PendingResult.class) {
            throw new IllegalStateException("Unexpected PendingResult constructor owner");
        }
        return constructor;
    }

    private static Class<?> bootClass(String name) throws ClassNotFoundException {
        Class<?> owner = Class.forName(name, false, Context.class.getClassLoader());
        if (owner.getClassLoader() != Context.class.getClassLoader()) {
            throw new IllegalArgumentException("Not a framework class: " + name);
        }
        return owner;
    }

    private static Map<String, Field> restrictedFields(Class<?> owner) {
        return RESTRICTED_FIELDS.computeIfAbsent(owner, type -> {
            Map<String, Field> fields = new HashMap<>();
            for (Field field : HiddenApiBypass.getInstanceFields(type)) {
                if (field.getDeclaringClass() != type || Modifier.isStatic(field.getModifiers())
                        || fields.put(field.getName(), field) != null) {
                    throw new IllegalStateException("Invalid framework field metadata: " + type.getName());
                }
            }
            if (fields.isEmpty()) throw new IllegalStateException("Framework fields unavailable: " + type.getName());
            Slog.dLazy("PlatformAccess", () -> "action=resolveMembers source=hidden_api"
                    + " result=available owner=" + type.getName());
            return Collections.unmodifiableMap(fields);
        });
    }

    private static void requireTarget(Field field, Object target) {
        if (target == null || !field.getDeclaringClass().isInstance(target)) {
            throw new IllegalArgumentException("Invalid target for framework member " + field.getName());
        }
    }
}
