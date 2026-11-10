package com.nexbox.core.utils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.nexbox.core.xposed.RuntimeLog;

/** Optional class-shape diagnostics. Never dumps field values or bypasses the log switches. */
public final class ReflectionClass {
    private ReflectionClass() { }

    public static void print(String name) {
        if (!RuntimeLog.isEnabled(android.util.Log.DEBUG)) return;
        try {
            Class<?> type = Class.forName(name);
            StringBuilder text = new StringBuilder(type.toGenericString()).append(" {\n");
            for (Constructor<?> constructor : type.getDeclaredConstructors()) text.append("  ").append(constructor.toGenericString()).append('\n');
            for (Method method : type.getDeclaredMethods()) text.append("  ").append(method.toGenericString()).append('\n');
            for (Field field : type.getDeclaredFields()) text.append("  ").append(field.toGenericString()).append('\n');
            text.append('}');
            for (int start = 0; start < text.length(); start += 4000) {
                Slog.d("ReflectionClass", "class=" + name + " part=" + (start / 4000 + 1) + "\n" + text.substring(start, Math.min(start + 4000, text.length())));
            }
            printExtendsChain(type);
        } catch (ClassNotFoundException | RuntimeException | LinkageError error) {
            Slog.w("ReflectionClass", "Could not inspect class=" + name, error);
        }
    }

    public static void printExtendsChain(Class<?> type) {
        if (!RuntimeLog.isEnabled(android.util.Log.DEBUG)) return;
        StringBuilder text = new StringBuilder("Extends chain:");
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            text.append(' ').append(current.getTypeName());
            if (current.getSuperclass() != null) text.append(" ->");
        }
        Slog.d("ReflectionClass", text.toString());
    }
}
