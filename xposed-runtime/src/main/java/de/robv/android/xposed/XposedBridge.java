/* NexBox implementation, API design derived from Xposed (Apache-2.0) and XYNXposed (MIT).
 * Unlike the reference backend, original invocation never changes shared callbacks.
 */
package de.robv.android.xposed;

import com.nexbox.core.xposed.RuntimeLog;

import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import com.nexbox.core.xposed.NativeBridge;

public final class XposedBridge {
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();
    public static final String TAG = "NexBox-Xposed";
    @Deprecated public static int XPOSED_BRIDGE_VERSION = 93;
    private static final Object[] EMPTY_CALLBACKS = new Object[0];
    private static final ConcurrentHashMap<Member, HookRecord> HOOKS = new ConcurrentHashMap<>();
    private static final ThreadLocal<java.util.ArrayDeque<List<XC_MethodHook.Unhook>>> LOAD_TRANSACTIONS =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final CopyOnWriteSortedSet<XC_LoadPackage> LOAD_CALLBACKS = new CopyOnWriteSortedSet<>();
    private XposedBridge() {}

    public static int getXposedVersion() { return 93; }
    public static void log(String text) { RuntimeLog.i(TAG, String.valueOf(text)); }
    public static void log(Throwable error) { RuntimeLog.e(TAG, "Xposed callback failure", error); }

    public static XC_MethodHook.Unhook hookMethod(Member target, XC_MethodHook callback) {
        Objects.requireNonNull(callback, "callback");
        if (!(target instanceof Method) && !(target instanceof Constructor<?>))
            throw new IllegalArgumentException("Only methods and constructors may be hooked");
        if (Modifier.isAbstract(target.getModifiers()))
            throw new IllegalArgumentException("Abstract methods have no implementation to hook");
        NativeBridge.requireAvailable();
        ((AccessibleObject) target).setAccessible(true);
        HookRecord record = HOOKS.computeIfAbsent(target, HookRecord::new);
        synchronized (record) {
            if (record.backup == null) {
                try {
                    record.backup = NativeBridge.hook(target, record,
                            HookRecord.class.getDeclaredMethod("callback", Object[].class));
                } catch (Throwable failure) {
                    record.failure = failure;
                    HOOKS.remove(target, record);
                    throw new IllegalStateException("Unable to install " + target, failure);
                } finally {
                    record.installed.countDown();
                }
            }
            record.callbacks.add(callback);
        }
        XC_MethodHook.Unhook unhook = callback.new Unhook(target);
        List<XC_MethodHook.Unhook> transaction = LOAD_TRANSACTIONS.get().peek();
        if (transaction != null) transaction.add(unhook);
        return unhook;
    }

    @Deprecated public static void unhookMethod(Member target, XC_MethodHook callback) {
        removeCallback(target, callback);
    }

    /** Internal removal shared by modern handles and legacy unhook tokens. */
    public static void removeCallback(Member target, XC_MethodHook callback) {
        HookRecord record = HOOKS.get(target);
        if (record != null) record.callbacks.remove(callback);
        // Retain the LSPlant backup/trampoline until process exit. Native UnHook would invalidate
        // backups already in use by other threads. An empty snapshot directly invokes the original.
    }

    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type, String name, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (Method method : type.getDeclaredMethods())
            if (method.getName().equals(name)) result.add(hookMethod(method, callback));
        return result;
    }

    public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> type, XC_MethodHook callback) {
        Set<XC_MethodHook.Unhook> result = new HashSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors())
            result.add(hookMethod(constructor, callback));
        return result;
    }

    public static Object invokeOriginalMethod(Member target, Object receiver, Object[] args)
            throws IllegalAccessException, InvocationTargetException {
        Object[] actualArgs = args == null ? new Object[0] : args;
        HookRecord record = HOOKS.get(target);
        if (record != null) return record.backupMethod().invoke(receiver, actualArgs);
        if (target instanceof Method method) {
            method.setAccessible(true);
            return method.invoke(receiver, actualArgs);
        }
        if (target instanceof Constructor<?> constructor && receiver == null) {
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(actualArgs);
            } catch (InstantiationException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        throw new IllegalArgumentException("An existing constructor receiver requires an installed hook");
    }

    public static boolean deoptimizeMethod(Member target) { return NativeBridge.deoptimize(target); }
    public static void hookLoadPackage(XC_LoadPackage callback) { LOAD_CALLBACKS.add(callback); }
    public static void hookInitPackageResources(XC_InitPackageResources callback) {
        throw new UnsupportedOperationException("NexBox supports Java hooks, not resource hooks");
    }

    /** Internal transaction: a module failing during initialization cannot leave partial callbacks. */
    public static void beginModuleLoad() { LOAD_TRANSACTIONS.get().push(new ArrayList<>()); }
    public static void finishModuleLoad(boolean success) {
        finishModuleLoadAndCollect(success);
    }
    public static List<XC_MethodHook.Unhook> finishModuleLoadAndCollect(boolean success) {
        java.util.ArrayDeque<List<XC_MethodHook.Unhook>> stack = LOAD_TRANSACTIONS.get();
        List<XC_MethodHook.Unhook> transaction = stack.poll();
        if (stack.isEmpty()) LOAD_TRANSACTIONS.remove();
        if (!success && transaction != null)
            for (XC_MethodHook.Unhook hook : transaction) hook.unhook();
        return success && transaction != null ? transaction : java.util.Collections.emptyList();
    }

    public static void dispatchLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        for (Object callback : LOAD_CALLBACKS.getSnapshot()) {
            try { ((XC_LoadPackage) callback).handleLoadPackage(param); }
            catch (Throwable failure) { log(failure); }
        }
    }

    /** Internal bridge shared by the legacy and modern adapters. */
    @FunctionalInterface public interface OriginalCall {
        Object invoke(Object receiver, Object[] args) throws Throwable;
    }

    public abstract static class Interceptor extends XC_MethodHook {
        protected Interceptor(int priority) { super(priority); }
        public abstract Object intercept(Member target, Object receiver, Object[] args,
                                         OriginalCall next) throws Throwable;
    }

    public static boolean replaceCallback(Member target, XC_MethodHook oldHook, XC_MethodHook newHook) {
        HookRecord record = HOOKS.get(target);
        return record != null && record.callbacks.replace(oldHook, newHook);
    }

    /** Invoke a snapshot of the shared chain, optionally skipping higher-priority hooks. */
    public static Object invokeChain(Member target, Object receiver, Object[] args, int maxPriority)
            throws Throwable {
        return invokeChain(target, receiver, args, maxPriority, false);
    }

    public static Object invokeOriginalSpecial(Member target, Object receiver, Object[] args, boolean special)
            throws IllegalAccessException, InvocationTargetException {
        if (special && target instanceof Method method && !HOOKS.containsKey(target)) {
            try { return NativeBridge.invokeSpecial(method, receiver, args); }
            catch (Throwable failure) { throw new InvocationTargetException(failure); }
        }
        return invokeOriginalMethod(target, receiver, args);
    }

    public static Object invokeChain(Member target, Object receiver, Object[] args, int maxPriority, boolean special)
            throws Throwable {
        HookRecord record = HOOKS.get(target);
        Object[] snapshot = record == null ? EMPTY_CALLBACKS : record.callbacks.getSnapshot();
        int first = 0;
        // The published snapshot is sorted by descending priority. Keep it intact so a
        // replacement/unhook cannot alter this invocation or any of its continuations.
        while (first < snapshot.length && ((XC_MethodHook) snapshot[first]).priority > maxPriority) first++;
        return dispatch(target, receiver, args, snapshot, first, (self, actual) -> {
            try { return invokeOriginalSpecial(target, self, actual, special); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
    }

    /** Constructors need a backup to initialize an already allocated receiver. */
    public static void ensureConstructorHook(Constructor<?> target) {
        XC_MethodHook.Unhook hook = hookMethod(target, new XC_MethodHook() {});
        hook.unhook();
    }

    /** One fresh param and immutable callback snapshot per invocation, including recursive calls. */
    static Object dispatch(Member target, Object receiver, Object[] args,
                           Object[] snapshot, OriginalCall original) throws Throwable {
        return dispatch(target, receiver, args, snapshot, 0, original);
    }

    private static Object dispatch(Member target, Object receiver, Object[] args,
                                   Object[] snapshot, int first, OriginalCall original) throws Throwable {
        if (first == snapshot.length) return original.invoke(receiver, args);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = target;
        param.thisObject = receiver;
        param.args = args;
        int entered = first;
        for (int index = first; index < snapshot.length; index++) {
            XC_MethodHook callback = (XC_MethodHook) snapshot[index];
            if (callback instanceof Interceptor interceptor) {
                int nextIndex = index + 1;
                try {
                    param.result = interceptor.intercept(target, param.thisObject, param.args,
                            (self, actual) -> dispatch(target, self, actual, snapshot, nextIndex, original));
                } catch (Throwable failure) { param.throwable = failure; }
                param.returnEarly = true;
                break;
            }
            entered++;
            try { callback.beforeHookedMethod(param); }
            catch (Throwable failure) {
                log(failure);
                param.result = null;
                param.throwable = null;
                param.returnEarly = false;
            }
            if (param.returnEarly) break;
        }
        if (!param.returnEarly) {
            try { param.result = original.invoke(param.thisObject, param.args); }
            catch (Throwable failure) { param.throwable = failure; }
        }
        for (int i = entered - 1; i >= first; i--) {
            Object result = param.result;
            Throwable throwable = param.throwable;
            boolean early = param.returnEarly;
            try { ((XC_MethodHook) snapshot[i]).afterHookedMethod(param); }
            catch (Throwable failure) {
                log(failure);
                param.result = result;
                param.throwable = throwable;
                param.returnEarly = early;
            }
        }
        return param.getResultOrThrowable();
    }

    public static final class HookRecord {
        final Member target;
        final CopyOnWriteSortedSet<XC_MethodHook> callbacks = new CopyOnWriteSortedSet<>();
        final CountDownLatch installed = new CountDownLatch(1);
        volatile Method backup;
        volatile Throwable failure;
        HookRecord(Member target) { this.target = target; }

        Method backupMethod() {
            boolean interrupted = false;
            while (true) {
                try { installed.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (backup == null) throw new IllegalStateException("Hook installation failed", failure);
            return backup;
        }

        /** Signature required by LSPlant; pinned by consumer ProGuard rules. */
        public Object callback(Object[] rawArguments) throws Throwable {
            Method original = backupMethod();
            boolean isStatic = Modifier.isStatic(target.getModifiers());
            Object receiver = isStatic ? null : rawArguments[0];
            Object[] args = isStatic ? rawArguments.clone()
                    : Arrays.copyOfRange(rawArguments, 1, rawArguments.length);
            return dispatch(target, receiver, args, callbacks.getSnapshot(), (self, actual) -> {
                try { return original.invoke(self, actual); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
        }
    }

    public static final class CopyOnWriteSortedSet<E> {
        private volatile Object[] elements = EMPTY_CALLBACKS;
        public synchronized boolean add(E value) {
            Objects.requireNonNull(value);
            for (Object existing : elements) if (existing == value) return false;
            Object[] next = Arrays.copyOf(elements, elements.length + 1);
            next[elements.length] = value;
            Arrays.sort(next);
            elements = next;
            return true;
        }
        public synchronized boolean remove(E value) {
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] != value) continue;
                Object[] next = new Object[elements.length - 1];
                System.arraycopy(elements, 0, next, 0, i);
                System.arraycopy(elements, i + 1, next, i, next.length - i);
                elements = next;
                return true;
            }
            return false;
        }
        public synchronized boolean replace(E previous, E replacement) {
            Objects.requireNonNull(replacement);
            for (int i = 0; i < elements.length; i++) {
                if (elements[i] != previous) continue;
                if (previous instanceof de.robv.android.xposed.callbacks.XCallback oldCallback
                        && replacement instanceof de.robv.android.xposed.callbacks.XCallback newCallback)
                    newCallback.inheritRegistrationOrder(oldCallback);
                Object[] next = elements.clone();
                next[i] = replacement;
                Arrays.sort(next);
                elements = next;
                return true;
            }
            return false;
        }
        public Object[] getSnapshot() { return elements; }
    }
}
