package com.nexbox.core.xposed;


import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;

/** API 101/102 adapter for an embedded, guest-only framework (no privileged daemon). */
final class ModernXposedInterface implements XposedInterface {
    private final ApplicationInfo moduleInfo;
    private final AtomicBoolean remotePreferencesReported = new AtomicBoolean();
    final ModernHooks hooks;

    ModernXposedInterface(ApplicationInfo moduleInfo, ModuleMetadata metadata) {
        this.moduleInfo = new ApplicationInfo(moduleInfo);
        hooks = new ModernHooks(metadata.protective);
    }

    @Override public String getFrameworkName() { return "NexBox"; }
    @Override public String getFrameworkVersion() { return "102.0.0-embedded"; }
    @Override public long getFrameworkVersionCode() { return 102000; }
    @Override public long getFrameworkProperties() { return 0; }
    @Override public HookBuilder hook(Executable origin) { return hooks.builder(origin); }
    @Override public HookBuilder hookClassInitializer(Class<?> origin) {
        return hook(NativeBridge.classInitializer(Objects.requireNonNull(origin)));
    }
    @Override public boolean deoptimize(Executable executable) { return NativeBridge.deoptimize(executable); }
    @Override public ApplicationInfo getModuleApplicationInfo() { return new ApplicationInfo(moduleInfo); }
    @Override public void log(int priority, String tag, String msg) { log(priority, tag, msg, null); }
    @Override public void log(int priority, String tag, String msg, Throwable tr) {
        RuntimeLog.write(priority, tag == null ? "NexBox-Xposed" : tag,
                "[" + moduleInfo.packageName + "] " + msg, tr);
    }
    @Override public SharedPreferences getRemotePreferences(String group) {
        Objects.requireNonNull(group, "group");
        if (remotePreferencesReported.compareAndSet(false, true)) {
            RuntimeLog.w("NexBox-Xposed", "[" + moduleInfo.packageName
                    + "] Remote preferences are unavailable; using read-only defaults (PROP_CAP_REMOTE is unset)");
        }
        return EmptyRemotePreferences.INSTANCE;
    }
    @Override public String[] listRemoteFiles() { throw noRemote(); }
    @Override public ParcelFileDescriptor openRemoteFile(String name) { throw noRemote(); }
    private static UnsupportedOperationException noRemote() {
        return new UnsupportedOperationException("NexBox is embedded: no libxposed remote preferences/files service (PROP_CAP_REMOTE is unset)");
    }

    @Override public Invoker<?, Method> getInvoker(Method method) { return new MethodInvoker(method); }
    @Override public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) { return new ConstructorInvoker<>(constructor); }

    private abstract static class BaseInvoker<T extends Invoker<T, U>, U extends Executable> implements Invoker<T, U> {
        final U target;
        Type type = Type.Chain.FULL;
        BaseInvoker(U target) { this.target = Objects.requireNonNull(target); target.setAccessible(true); }
        @SuppressWarnings("unchecked")
        @Override public T setType(Type value) { type = Objects.requireNonNull(value); return (T) this; }
        @Override public Object invoke(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
            return call(receiver, args, false);
        }
        @Override public Object invokeSpecial(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
            Objects.requireNonNull(receiver);
            if (Modifier.isStatic(target.getModifiers())) throw new IllegalArgumentException("A static method is not a special invocation");
            return call(receiver, args, true);
        }
        Object call(Object receiver, Object[] args, boolean special) throws InvocationTargetException, IllegalAccessException {
            Object[] actual = args == null ? new Object[0] : args.clone();
            validateArguments(target, receiver, actual);
            Executable dispatchTarget = !special && target instanceof Method method
                    ? VirtualMethods.resolve(method, receiver) : target;
            try {
                if (target instanceof Constructor<?> constructor) XposedBridge.ensureConstructorHook(constructor);
                Object result = type instanceof Type.Origin
                        ? XposedBridge.invokeOriginalSpecial(dispatchTarget, receiver, actual, special)
                        : XposedBridge.invokeChain(dispatchTarget, receiver, actual, ((Type.Chain) type).maxPriority(), special);
                return target instanceof Constructor<?> || target instanceof Method method && method.getReturnType() == void.class
                        ? null : result;
            } catch (InvocationTargetException | IllegalAccessException e) { throw e; }
            catch (Throwable failure) { throw new InvocationTargetException(failure); }
        }
    }
    private static final class MethodInvoker extends BaseInvoker<MethodInvoker, Method> {
        MethodInvoker(Method method) { super(method); }
    }
    private static final class ConstructorInvoker<T> extends BaseInvoker<CtorInvoker<T>, Constructor<T>> implements CtorInvoker<T> {
        ConstructorInvoker(Constructor<T> constructor) { super(constructor); }
        @Override public T newInstance(Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException {
            return newInstanceSpecial(target.getDeclaringClass(), args);
        }
        @Override public <U> U newInstanceSpecial(Class<U> subclass, Object... args)
                throws InvocationTargetException, IllegalAccessException, InstantiationException {
            if (!target.getDeclaringClass().isAssignableFrom(subclass)) throw new IllegalArgumentException("Not a subclass of the constructor owner");
            if (Modifier.isAbstract(subclass.getModifiers()) || subclass.isInterface()) throw new InstantiationException(subclass.getName());
            U instance = subclass.cast(NativeBridge.allocateInstance(subclass));
            invokeSpecial(instance, args);
            return instance;
        }
    }

    /** Match reflection's primitive widening rules before dispatching any hooks/JNI call. */
    static void validateArguments(Executable target, Object receiver, Object[] args) {
        if (!Modifier.isStatic(target.getModifiers()) && !target.getDeclaringClass().isInstance(receiver))
            throw new IllegalArgumentException("Missing or incompatible receiver");
        Class<?>[] types = target.getParameterTypes();
        if (types.length != args.length) throw new IllegalArgumentException("Wrong argument count");
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            Object value = args[i];
            if (!type.isPrimitive()) {
                if (value != null && !type.isInstance(value)) throw new IllegalArgumentException("Wrong argument type at " + i);
                continue;
            }
            if (type == boolean.class && value instanceof Boolean || type == char.class && value instanceof Character) continue;
            String allowed = type == byte.class ? "B" : type == short.class ? "BS" : type == int.class ? "BSCI"
                    : type == long.class ? "BSCIJ" : type == float.class ? "BSCIJF" : type == double.class ? "BSCIJFD" : "";
            char actual = value instanceof Byte ? 'B' : value instanceof Short ? 'S' : value instanceof Character ? 'C'
                    : value instanceof Integer ? 'I' : value instanceof Long ? 'J' : value instanceof Float ? 'F'
                    : value instanceof Double ? 'D' : '?';
            if (allowed.indexOf(actual) < 0) throw new IllegalArgumentException("Wrong primitive argument at " + i);
        }
    }
}
