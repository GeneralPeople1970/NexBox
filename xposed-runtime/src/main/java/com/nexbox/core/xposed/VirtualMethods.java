package com.nexbox.core.xposed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reflection's invoke is virtual; invokeSpecial must keep the supplied declaring method. */
final class VirtualMethods {
    private VirtualMethods() {}
    static Method resolve(Method method, Object receiver) {
        int modifiers = method.getModifiers();
        if (receiver == null || Modifier.isStatic(modifiers) || Modifier.isPrivate(modifiers)
                || Modifier.isFinal(modifiers)) return method;
        Class<?> owner = method.getDeclaringClass();
        Method resolved = method;
        java.util.ArrayList<Class<?>> hierarchy = new java.util.ArrayList<>();
        for (Class<?> type = receiver.getClass(); type != null && type != owner; type = type.getSuperclass()) hierarchy.add(type);
        // Follow override visibility from base to derived, including a package-private method
        // promoted to public/protected by an intermediate class in the declaring package.
        java.util.Collections.reverse(hierarchy);
        for (Class<?> type : hierarchy) {
            try {
                Method candidate = type.getDeclaredMethod(method.getName(), method.getParameterTypes());
                int flags = candidate.getModifiers();
                int current = resolved.getModifiers();
                if (Modifier.isStatic(flags) || Modifier.isPrivate(flags)) continue;
                if (!Modifier.isPublic(current) && !Modifier.isProtected(current)
                        && !resolved.getDeclaringClass().getPackageName().equals(type.getPackageName())) continue;
                if (resolved.getReturnType().isAssignableFrom(candidate.getReturnType())) resolved = candidate;
            } catch (NoSuchMethodException ignored) { }
        }
        resolved.setAccessible(true);
        return resolved;
    }
}
