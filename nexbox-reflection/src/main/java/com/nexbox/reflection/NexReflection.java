package com.nexbox.reflection;

import java.lang.annotation.Annotation;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.nexbox.reflection.annotation.BClass;
import com.nexbox.reflection.annotation.BClassName;
import com.nexbox.reflection.annotation.BClassNameNotProcess;
import com.nexbox.reflection.annotation.BConstructor;
import com.nexbox.reflection.annotation.BConstructorNotProcess;
import com.nexbox.reflection.annotation.BField;
import com.nexbox.reflection.annotation.BFieldCheckNotProcess;
import com.nexbox.reflection.annotation.BFieldNotProcess;
import com.nexbox.reflection.annotation.BFieldSetNotProcess;
import com.nexbox.reflection.annotation.BMethodCheckNotProcess;
import com.nexbox.reflection.annotation.BParamClass;
import com.nexbox.reflection.annotation.BParamClassName;
import com.nexbox.reflection.utils.Reflector;

@SuppressWarnings("unchecked")
public class NexReflection {
    public static boolean DEBUG = false;
    public static boolean CACHE = false;

    // A strong value behind a weak Class key would retain the key through Methods,
    // proxies and target Classes. Only live proxies own their metadata strongly.
    private static final Map<Class<?>, WeakReference<MirrorState>> MIRRORS = new WeakHashMap<>();
    private static volatile Object cacheEpoch = new Object();

    public static <T> T create(Class<T> clazz, Object caller, boolean withException) {
        try {
            return (T) mirror(clazz).proxy(caller, withException);
        } catch (ClassNotFoundException failure) {
            failure.printStackTrace();
            return null;
        }
    }

    /** Hidden-API bootstrap may turn an earlier missing-member probe into a success. */
    public static void clearCaches() {
        synchronized (MIRRORS) {
            Reflector.clearCaches();
            cacheEpoch = new Object();
            MIRRORS.clear();
        }
    }

    private static MirrorState mirror(Class<?> type) throws ClassNotFoundException {
        synchronized (MIRRORS) {
            WeakReference<MirrorState> reference = MIRRORS.get(type);
            MirrorState state = reference == null ? null : reference.get();
            if (state != null) return state;
        }
        // Resolving the target may initialize a class; do not run it under the registry lock.
        MirrorState created = new MirrorState(type, getClassNameByMirrorClass(type));
        synchronized (MIRRORS) {
            WeakReference<MirrorState> reference = MIRRORS.get(type);
            MirrorState state = reference == null ? null : reference.get();
            if (state != null) return state;
            MIRRORS.put(type, new WeakReference<>(created));
            return created;
        }
    }

    private static final class MirrorState {
        private static final int MAX_CALLERS = 256;
        final Class<?> mirror;
        final Class<?> target;
        final ConcurrentHashMap<Method, InvocationPlan> plans = new ConcurrentHashMap<>();
        final ReferenceQueue<Object> retiredCallers = new ReferenceQueue<>();
        final Map<CallerKey, ProxyPair> callers = new LinkedHashMap<>();
        final ProxyPair staticProxies = new ProxyPair();

        MirrorState(Class<?> mirror, Class<?> target) {
            this.mirror = mirror;
            this.target = target;
        }

        Object proxy(Object caller, boolean withException) {
            CallerKey key = caller == null ? null : new CallerKey(caller, retiredCallers);
            synchronized (this) {
                retireCallers();
                ProxyPair pair = key == null ? staticProxies : callers.get(key);
                Object cached = pair == null ? null : pair.get(withException);
                if (cached != null) return cached;
            }
            Object created = Proxy.newProxyInstance(mirror.getClassLoader(), new Class<?>[]{mirror},
                    new MirrorInvocation(this, caller, withException));
            synchronized (this) {
                retireCallers();
                ProxyPair pair = key == null ? staticProxies : callers.get(key);
                if (pair == null) {
                    if (callers.size() >= MAX_CALLERS) callers.remove(callers.keySet().iterator().next());
                    pair = new ProxyPair();
                    callers.put(key, pair);
                }
                Object cached = pair.get(withException);
                if (cached != null) return cached;
                pair.put(withException, created);
                return created;
            }
        }

        private void retireCallers() {
            CallerKey key;
            while ((key = (CallerKey) retiredCallers.poll()) != null) callers.remove(key);
        }

        InvocationPlan plan(Method method) throws Throwable {
            Object epoch = cacheEpoch;
            InvocationPlan plan = plans.get(method);
            if (plan != null && plan.epoch == epoch) return plan;
            InvocationPlan created = new InvocationPlan(target, method, epoch);
            plans.put(method, created);
            return created;
        }
    }

    private static final class CallerKey extends WeakReference<Object> {
        final int hash;

        CallerKey(Object caller, ReferenceQueue<Object> queue) {
            super(caller, queue);
            hash = System.identityHashCode(caller);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof CallerKey)) return false;
            Object caller = get();
            return caller != null && caller == ((CallerKey) other).get();
        }
    }

    private static final class ProxyPair {
        WeakReference<Object> quiet;
        WeakReference<Object> strict;

        Object get(boolean withException) {
            WeakReference<Object> reference = withException ? strict : quiet;
            return reference == null ? null : reference.get();
        }

        void put(boolean withException, Object proxy) {
            if (withException) strict = new WeakReference<>(proxy);
            else quiet = new WeakReference<>(proxy);
        }
    }

    private static final class MirrorInvocation implements InvocationHandler {
        final MirrorState mirror;
        // A live bound proxy owns its target. Only the registry's keys/values are weak:
        // otherwise a retained proxy can silently stop invoking after the caller is collected.
        final Object caller;
        final boolean withException;

        MirrorInvocation(MirrorState mirror, Object caller, boolean withException) {
            this.mirror = mirror;
            this.caller = caller;
            this.withException = withException;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                InvocationPlan plan = mirror.plan(method);
                return plan.invoke(caller == null, caller, args);
            } catch (Throwable failure) {
                if (DEBUG) {
                    if (failure.getCause() != null) failure.getCause().printStackTrace();
                    else failure.printStackTrace();
                }
                if (failure instanceof ReflectionNullPointerException) {
                    throw new NullPointerException(failure.getMessage());
                }
                if (withException) throw failure;
            }
            return generateNullValue(method.getReturnType());
        }
    }

    private enum Operation { GET, SET, CHECK_FIELD, CHECK_METHOD, CONSTRUCT, CALL }

    private static final class InvocationPlan {
        final Object epoch;
        final Class<?> target;
        final Class<?> returnType;
        final String name;
        final Class<?>[] parameters;
        final Operation operation;
        private volatile Reflector resolved;
        private volatile boolean missingProbe;

        InvocationPlan(Class<?> target, Method method, Object epoch) throws Throwable {
            this.epoch = epoch;
            this.target = target;
            returnType = method.getReturnType();
            String methodName = method.getName();
            if (method.isAnnotationPresent(BField.class) || method.isAnnotationPresent(BFieldNotProcess.class)) {
                operation = Operation.GET;
                name = methodName;
                parameters = null;
            } else if (method.isAnnotationPresent(BFieldSetNotProcess.class)) {
                operation = Operation.SET;
                name = methodName.substring("_set_".length());
                parameters = null;
            } else if (method.isAnnotationPresent(BFieldCheckNotProcess.class)) {
                operation = Operation.CHECK_FIELD;
                name = methodName.substring("_check_".length());
                parameters = null;
            } else {
                parameters = getParamClass(method);
                if (method.isAnnotationPresent(BMethodCheckNotProcess.class)) {
                    operation = Operation.CHECK_METHOD;
                    name = methodName.substring("_check_".length());
                } else {
                    operation = method.isAnnotationPresent(BConstructor.class)
                            || method.isAnnotationPresent(BConstructorNotProcess.class)
                            ? Operation.CONSTRUCT : Operation.CALL;
                    name = methodName;
                }
            }
        }

        private Reflector resolve() throws Throwable {
            Reflector current = resolved;
            if (current != null || missingProbe) return current;
            synchronized (this) {
                current = resolved;
                if (current != null || missingProbe) return current;
                try {
                    current = Reflector.on(target);
                    switch (operation) {
                        case GET:
                        case SET:
                        case CHECK_FIELD:
                            current.field(name);
                            break;
                        case CONSTRUCT:
                            current.constructor(parameters);
                            break;
                        default:
                            current.method(name, parameters);
                            break;
                    }
                    resolved = current;
                    return current;
                } catch (Throwable failure) {
                    if (!isProbe()) throw failure;
                    Throwable cause = failure.getCause();
                    missingProbe = cause instanceof NoSuchFieldException || cause instanceof NoSuchMethodException;
                    return null;
                }
            }
        }

        private boolean isProbe() {
            return operation == Operation.CHECK_FIELD || operation == Operation.CHECK_METHOD;
        }

        Object invoke(boolean isStatic, Object caller, Object[] args) throws Throwable {
            Reflector member = resolve();
            if (isProbe()) {
                if (member == null) return null;
                try {
                    AccessibleObject checked = operation == Operation.CHECK_FIELD
                            ? member.getField() : member.getMethod();
                    makeAccessible(checked);
                    return checked;
                } catch (Throwable ignored) {
                    return null;
                }
            }
            if (operation == Operation.CONSTRUCT) return member.newInstance(args);
            if (!isStatic && caller == null) {
                return operation == Operation.SET ? 0 : generateNullValue(returnType);
            }
            switch (operation) {
                case GET:
                    return member.get(caller);
                case SET:
                    member.set(caller, args[0]);
                    return 0;
                default:
                    return member.callByCaller(caller, args);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void makeAccessible(AccessibleObject member) {
        if (!member.isAccessible()) member.setAccessible(true);
    }

    private static Class<?>[] getParamClass(Method method) throws Throwable {
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Class<?>[] parameterTypes = method.getParameterTypes();
        Class<?>[] param = new Class<?>[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            param[index] = parameterTypes[index];
            for (Annotation annotation : parameterAnnotations[index]) {
                if (annotation instanceof BParamClassName) {
                    param[index] = Class.forName(((BParamClassName) annotation).value());
                    break;
                } else if (annotation instanceof BParamClass) {
                    param[index] = ((BParamClass) annotation).value();
                    break;
                }
            }
        }
        return param;
    }

    private static Class<?> getClassNameByMirrorClass(Class<?> clazz) throws ClassNotFoundException {
        BClass bClass = clazz.getAnnotation(BClass.class);
        BClassName bClassName = clazz.getAnnotation(BClassName.class);
        BClassNameNotProcess bClassNameNotProcess = clazz.getAnnotation(BClassNameNotProcess.class);
        if (bClass == null && bClassName == null && bClassNameNotProcess == null) {
            throw new RuntimeException("Not found @MirrorClass or @MirrorNamedClass");
        }
        if (bClass != null) return bClass.value();
        if (bClassName != null) return Class.forName(bClassName.value());
        return Class.forName(bClassNameNotProcess.value());
    }

    private static Object generateNullValue(Class<?> returnType) {
        if (returnType == void.class) return 0;
        if (returnType.isPrimitive()) throw new ReflectionNullPointerException("value is null!");
        return null;
    }
}
