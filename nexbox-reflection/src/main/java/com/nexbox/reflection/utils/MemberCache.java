package com.nexbox.reflection.utils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Unbound member metadata; neither registry keys nor values keep a ClassLoader alive. */
public final class MemberCache {
    private static final Map<Class<?>, WeakReference<MemberCache>> CACHES = new WeakHashMap<>();
    private static volatile long generation;
    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];
    private static final int MAX_ENTRIES = 256;

    private final Class<?> type;
    private final long epoch;
    private final ConcurrentHashMap<String, Lookup<Field>> fields = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Signature, Lookup<Method>> methods = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Signature, Lookup<Constructor<?>>> constructors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Lookup<Method>> declaredMethodsByName = new ConcurrentHashMap<>();

    private MemberCache(Class<?> type) {
        this.type = type;
        epoch = generation;
    }

    public static MemberCache forType(Class<?> type) {
        synchronized (CACHES) {
            WeakReference<MemberCache> reference = CACHES.get(type);
            MemberCache cache = reference == null ? null : reference.get();
            if (cache == null) {
                cache = new MemberCache(type);
                CACHES.put(type, new WeakReference<>(cache));
            }
            return cache;
        }
    }

    static void clear() {
        synchronized (CACHES) {
            CACHES.clear();
            generation++;
        }
    }

    public boolean isCurrentFor(Class<?> candidate) {
        return type == candidate && epoch == generation;
    }

    public Field field(String name) throws NoSuchFieldException {
        Lookup<Field> result = fields.get(name);
        if (result == null) {
            synchronized (this) {
                result = fields.get(name);
                if (result == null) {
                    try {
                        result = Lookup.found(findField(name));
                    } catch (NoSuchFieldException failure) {
                        result = Lookup.missing(failure.getMessage());
                    }
                    if (fields.size() >= MAX_ENTRIES) fields.clear();
                    fields.put(name, result);
                }
            }
        }
        if (result.member == null) throw new NoSuchFieldException(result.missingMessage);
        return result.member;
    }

    public Method method(String name, Class<?>[] parameters) throws NoSuchMethodException {
        Class<?>[] requested = copyParameters(parameters);
        Signature signature = new Signature(name, requested);
        Lookup<Method> result = methods.get(signature);
        if (result == null) {
            synchronized (this) {
                result = methods.get(signature);
                if (result == null) {
                    try {
                        result = Lookup.found(findMethod(name, requested));
                    } catch (NoSuchMethodException failure) {
                        result = Lookup.missing(failure.getMessage());
                    }
                    if (methods.size() >= MAX_ENTRIES) methods.clear();
                    methods.put(signature, result);
                }
            }
        }
        if (result.member == null) throw new NoSuchMethodException(result.missingMessage);
        return result.member;
    }

    public Constructor<?> constructor(Class<?>[] parameters) throws NoSuchMethodException {
        Class<?>[] requested = copyParameters(parameters);
        Signature signature = new Signature("<init>", requested);
        Lookup<Constructor<?>> result = constructors.get(signature);
        if (result == null) {
            synchronized (this) {
                result = constructors.get(signature);
                if (result == null) {
                    try {
                        result = Lookup.found(type.getDeclaredConstructor(requested));
                    } catch (NoSuchMethodException failure) {
                        result = Lookup.missing(failure.getMessage());
                    }
                    if (constructors.size() >= MAX_ENTRIES) constructors.clear();
                    constructors.put(signature, result);
                }
            }
        }
        if (result.member == null) throw new NoSuchMethodException(result.missingMessage);
        return result.member;
    }

    /** Compatibility for the core helper: declared-only lookup, original reflection order. */
    public Method firstDeclaredMethod(String name) {
        if (name == null) return findFirstDeclaredMethod(null);
        Lookup<Method> result = declaredMethodsByName.get(name);
        if (result == null) {
            synchronized (this) {
                result = declaredMethodsByName.get(name);
                if (result == null) {
                    Method method = findFirstDeclaredMethod(name);
                    result = method == null ? Lookup.missing(null) : Lookup.found(method);
                    if (declaredMethodsByName.size() >= MAX_ENTRIES) declaredMethodsByName.clear();
                    declaredMethodsByName.put(name, result);
                }
            }
        }
        return result.member;
    }

    private Method findFirstDeclaredMethod(String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())) return method;
        }
        return null;
    }

    private Field findField(String name) throws NoSuchFieldException {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException failure) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            throw failure;
        }
    }

    private Method findMethod(String name, Class<?>[] parameters) throws NoSuchMethodException {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException failure) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                try {
                    return current.getDeclaredMethod(name, parameters);
                } catch (NoSuchMethodException ignored) {
                }
            }
            throw failure;
        }
    }

    private static final class Lookup<T> {
        final T member;
        final String missingMessage;

        private Lookup(T member, String missingMessage) {
            this.member = member;
            this.missingMessage = missingMessage;
        }

        static <T> Lookup<T> found(T member) {
            return new Lookup<>(member, null);
        }

        static <T> Lookup<T> missing(String message) {
            // Do not retain Throwables (and their backtraces). Access/security failures
            // are deliberately not negative-cached and may be retried after unsealing.
            return new Lookup<>(null, message);
        }
    }

    private static final class Signature {
        final String name;
        final WeakReference<Class<?>>[] parameters;
        final int hash;

        @SuppressWarnings("unchecked")
        Signature(String name, Class<?>[] parameters) {
            this.name = name;
            this.parameters = new WeakReference[parameters.length];
            int parameterHash = 1;
            for (int index = 0; index < parameters.length; index++) {
                Class<?> parameter = parameters[index];
                if (parameter != null) this.parameters[index] = new WeakReference<>(parameter);
                parameterHash = 31 * parameterHash + System.identityHashCode(parameter);
            }
            hash = 31 * Objects.hashCode(name) + parameterHash;
        }

        @Override public int hashCode() {
            return hash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Signature)) return false;
            Signature signature = (Signature) other;
            // Class identity, not a binary-name string, distinguishes loader generations.
            if (!Objects.equals(name, signature.name) || parameters.length != signature.parameters.length) return false;
            for (int index = 0; index < parameters.length; index++) {
                WeakReference<Class<?>> first = parameters[index];
                WeakReference<Class<?>> second = signature.parameters[index];
                if (first == null || second == null) {
                    if (first != second) return false;
                } else {
                    Class<?> parameter = first.get();
                    if (parameter == null || parameter != second.get()) return false;
                }
            }
            return true;
        }
    }

    private static Class<?>[] copyParameters(Class<?>[] parameters) {
        return parameters == null || parameters.length == 0 ? NO_PARAMETERS : parameters.clone();
    }
}
