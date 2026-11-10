package com.nexbox.core.xposed;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;

/** One registry per module package. IDs cannot replace another module's hooks. */
final class ModernHooks {
    interface Backend {
        void add(Executable target, XposedBridge.Interceptor callback);
        boolean replace(Executable target, XposedBridge.Interceptor previous, XposedBridge.Interceptor next);
        void remove(Executable target, XposedBridge.Interceptor callback);
    }
    private static final Backend NATIVE = new Backend() {
        @Override public void add(Executable target, XposedBridge.Interceptor callback) { XposedBridge.hookMethod(target, callback); }
        @Override public boolean replace(Executable target, XposedBridge.Interceptor previous, XposedBridge.Interceptor next) {
            return XposedBridge.replaceCallback(target, previous, next);
        }
        @Override public void remove(Executable target, XposedBridge.Interceptor callback) { XposedBridge.removeCallback(target, callback); }
    };
    private final boolean defaultProtective;
    private final Backend backend;
    private final Map<Executable, Map<String, Handle>> identified = new HashMap<>();
    private final Set<Handle> handles = new HashSet<>();
    private boolean retired;

    ModernHooks(boolean defaultProtective) { this(defaultProtective, NATIVE); }
    ModernHooks(boolean defaultProtective, Backend backend) {
        this.defaultProtective = defaultProtective;
        this.backend = Objects.requireNonNull(backend);
    }

    XposedInterface.HookBuilder builder(Executable target) {
        Objects.requireNonNull(target);
        String owner = target.getDeclaringClass().getName();
        if (owner.startsWith("com.nexbox.core.") || owner.startsWith("de.robv.android.xposed.")
                || owner.startsWith("io.github.libxposed.") || owner.equals("java.lang.reflect.Constructor"))
            throw new IllegalArgumentException("Framework internals cannot be hooked");
        return new XposedInterface.HookBuilder() {
            int priority = XposedInterface.PRIORITY_DEFAULT;
            boolean protective = defaultProtective;
            String id;
            @Override public XposedInterface.HookBuilder setPriority(int value) { priority = value; return this; }
            @Override public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode mode) {
                protective = switch (Objects.requireNonNull(mode)) {
                    case DEFAULT -> defaultProtective;
                    case PROTECTIVE -> true;
                    case PASSTHROUGH -> false;
                };
                return this;
            }
            @Override public XposedInterface.HookBuilder setId(String value) { id = value; return this; }
            @Override public XposedInterface.HookHandle intercept(XposedInterface.Hooker hooker) {
                return install(new Handle(target, priority, protective, id, Objects.requireNonNull(hooker)), null);
            }
        };
    }

    private synchronized Handle install(Handle next, Handle expected) {
        if (retired) throw new IllegalStateException("The module generation has failed or retired");
        Handle previous = expected;
        if (previous == null && next.id != null)
            previous = identified.getOrDefault(next.target, Map.of()).get(next.id);
        if (expected != null && !handles.contains(expected)) throw new IllegalStateException("Hook handle is no longer valid");
        try {
            if (previous == null) backend.add(next.target, next.callback);
            else if (!backend.replace(next.target, previous.callback, next.callback))
                throw new IllegalStateException("Hook handle is no longer valid");
        } catch (IllegalArgumentException | IllegalStateException failure) { throw failure; }
        catch (Throwable failure) { throw new HookFailedError(failure); }
        if (previous != null) handles.remove(previous);
        handles.add(next);
        if (next.id != null) identified.computeIfAbsent(next.target, ignored -> new HashMap<>()).put(next.id, next);
        return next;
    }

    synchronized void clear() {
        for (Handle handle : handles.toArray(new Handle[0])) handle.unhook();
    }

    synchronized void retire() { retired = true; clear(); }

    private final class Handle implements XposedInterface.HookHandle {
        final Executable target;
        final int priority;
        final boolean protective;
        final String id;
        final XposedBridge.Interceptor callback;
        Handle(Executable target, int priority, boolean protective, String id, XposedInterface.Hooker hooker) {
            this.target = target; this.priority = priority; this.protective = protective; this.id = id;
            callback = new XposedBridge.Interceptor(priority) {
                @Override public Object intercept(Member member, Object receiver, Object[] args,
                                                  XposedBridge.OriginalCall next) throws Throwable {
                    return new ModernHookChain(target, receiver, args, next).invoke(hooker, protective);
                }
            };
        }
        @Override public Executable getExecutable() { return target; }
        @Override public String getId() { return id; }
        @Override public void unhook() {
            synchronized (ModernHooks.this) {
                if (!handles.remove(this)) return; // A stale handle cannot unhook its replacement.
                backend.remove(target, callback);
                if (id != null) {
                    Map<String, Handle> byId = identified.get(target);
                    byId.remove(id, this);
                    if (byId.isEmpty()) identified.remove(target);
                }
            }
        }
        @Override public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker hooker) {
            return install(new Handle(target, priority, protective, id, Objects.requireNonNull(hooker)), this);
        }
    }
}
