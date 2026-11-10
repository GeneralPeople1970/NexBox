package com.nexbox.core.xposed;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;

/** Invocation-local chain; no result/exception state is shared across calls or threads. */
final class ModernHookChain implements XposedInterface.Chain {
    private final Executable executable;
    private final Object receiver;
    private final Object[] args;
    private final XposedBridge.OriginalCall next;
    private final Thread owner = Thread.currentThread();
    private boolean active = true, proceeded;
    private Object result;
    private Throwable failure;

    ModernHookChain(Executable executable, Object receiver, Object[] args, XposedBridge.OriginalCall next) {
        this.executable = executable;
        this.receiver = receiver;
        this.args = args.clone();
        this.next = next;
    }

    Object invoke(XposedInterface.Hooker hooker, boolean protective) throws Throwable {
        try { return checkedResult(hooker.intercept(this)); }
        catch (Throwable thrown) {
            if (!protective || thrown == failure) throw thrown;
            XposedBridge.log(thrown);
            if (!proceeded) return proceed();
            if (failure != null) throw failure;
            return result;
        } finally { active = false; }
    }

    private Object checkedResult(Object value) {
        if (!(executable instanceof Method method) || method.getReturnType() == void.class) return null;
        Class<?> type = method.getReturnType();
        if (type.isPrimitive()) {
            type = type == boolean.class ? Boolean.class : type == byte.class ? Byte.class
                    : type == short.class ? Short.class : type == char.class ? Character.class
                    : type == int.class ? Integer.class : type == long.class ? Long.class
                    : type == float.class ? Float.class : Double.class;
            if (value == null) throw new ClassCastException("Null hook result for " + method.getReturnType());
        }
        if (value != null && !type.isInstance(value)) throw new ClassCastException("Wrong hook result type for " + method);
        return value;
    }

    private void checkActive() {
        if (!active || owner != Thread.currentThread())
            throw new IllegalStateException("A hook chain is only valid inside its interceptor on the calling thread");
    }

    @Override public Executable getExecutable() { checkActive(); return executable; }
    @Override public Object getThisObject() { checkActive(); return receiver; }
    @Override public List<Object> getArgs() {
        checkActive();
        return Collections.unmodifiableList(Arrays.asList(args.clone()));
    }
    @Override public Object getArg(int index) { checkActive(); return args[index]; }
    @Override public Object proceed() throws Throwable { return proceed(args); }
    @Override public Object proceed(Object[] actual) throws Throwable { return call(receiver, actual); }
    @Override public Object proceedWith(Object self) throws Throwable { return proceedWith(self, args); }
    @Override public Object proceedWith(Object self, Object[] actual) throws Throwable {
        checkActive();
        if (Modifier.isStatic(executable.getModifiers()))
            throw new IllegalArgumentException("Static hooks have no receiver");
        if (!executable.getDeclaringClass().isInstance(Objects.requireNonNull(self)))
            throw new IllegalArgumentException("Receiver has the wrong type");
        return call(self, actual);
    }

    private Object call(Object self, Object[] actual) throws Throwable {
        checkActive();
        Objects.requireNonNull(actual);
        if (actual.length != executable.getParameterCount()) throw new IllegalArgumentException("Wrong argument count");
        proceeded = true;
        result = null;
        failure = null;
        try { result = next.invoke(self, actual.clone()); return result; }
        catch (Throwable thrown) { failure = thrown; throw thrown; }
    }
}
