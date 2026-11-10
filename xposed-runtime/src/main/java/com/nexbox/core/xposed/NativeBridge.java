package com.nexbox.core.xposed;


import android.os.Build;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

/** JNI boundary. No native initialization occurs merely by discovering module metadata. */
public final class NativeBridge {
    private static final String TAG = "NexBox-Xposed";
    private static boolean attempted;
    private static boolean available;
    private static String error = "Runtime has not been initialized in a guest process";
    private static String nativeInitializationDetail = "";

    private NativeBridge() {}

    static synchronized boolean initializeGuest() {
        if (attempted) return available;
        attempted = true;
        if (!RuntimeCapabilities.supportsAndroid(Build.VERSION.SDK_INT)) {
            error = "Unsupported Android API " + Build.VERSION.SDK_INT;
            RuntimeLog.w(TAG, "NativeBridge init refused: " + error);
            return false;
        }
        if (!RuntimeCapabilities.supportsDeviceAbi(Build.SUPPORTED_ABIS)) {
            error = "NexBox ART hooks require a native ARM device" + abiDiagnostic();
            RuntimeLog.w(TAG, "NativeBridge init refused: " + error);
            return false;
        }
        try {
            System.loadLibrary("nexbox_xposed");
            available = nativeReady();
            error = available ? "" : (nativeInitializationDetail.isEmpty()
                    ? "LSPlant could not initialize ART hooks" : nativeInitializationDetail) + abiDiagnostic();
        } catch (Throwable failure) {
            error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            RuntimeLog.e(TAG, "Native library initialization failed" + abiDiagnostic(), failure);
        }
        if (available) RuntimeLog.i(TAG, "NativeBridge ready: LSPlant ART hooks initialized");
        else RuntimeLog.e(TAG, "NativeBridge init failed: " + error);
        return available;
    }

    /**
     * LSPlant/Dobby 做的是原生 ART 内联 hook。若进程实际跑在 x86 设备上、经 ARM 二进制
     * 翻译（模拟器 Houdini 等）执行 arm 库，就无法对真正的 x86 ART 打桩，`lsplant::Init`
     * 静默返回 false。把设备 ABI 附到错误里，避免每次都要翻 logcat 才知道是环境问题。
     */
    private static String abiDiagnostic() {
        String[] abis = Build.SUPPORTED_ABIS;
        String primary = (abis != null && abis.length > 0) ? abis[0] : "unknown";
        String detail = " (device ABIs=" + Arrays.toString(abis) + ")";
        if (primary.startsWith("x86")) {
            detail += " — NexBox ships arm64/armeabi only; on an x86 device the guest runs under"
                    + " ARM binary translation, where inline ART hooking cannot initialize."
                    + " Verify on a physical arm64 device.";
        }
        return detail;
    }

    public static synchronized String getError() { return error; }

    /** JNI reports through the same gated/redacted sink, never directly to Logcat. */
    @SuppressWarnings("unused")
    private static void reportInitializationFailure(String detail, Throwable cause) {
        nativeInitializationDetail = detail;
        RuntimeLog.e(TAG, detail + abiDiagnostic(), cause);
    }

    public static synchronized void requireAvailable() {
        if (!available) throw new IllegalStateException(error);
    }

    public static Method hook(Member target, Object dispatcher, Method callback) {
        requireAvailable();
        Method backup = nativeHook(target, dispatcher, callback);
        if (backup == null) throw new IllegalStateException("LSPlant rejected hook: " + target);
        backup.setAccessible(true);
        return backup;
    }

    public static boolean deoptimize(Member target) {
        requireAvailable();
        return nativeDeoptimize(target);
    }

    public static Object allocateInstance(Class<?> type) throws InstantiationException {
        requireAvailable();
        return nativeAllocateInstance(Objects.requireNonNull(type));
    }

    public static Method classInitializer(Class<?> type) {
        requireAvailable();
        Method method = nativeClassInitializer(Objects.requireNonNull(type));
        if (method == null) throw new IllegalArgumentException("Class has no static initializer: " + type.getName());
        return method;
    }

    public static Object invokeSpecial(Method target, Object receiver, Object[] args) {
        requireAvailable();
        // Keep the validated objects stable while JNI unboxes primitive arguments.
        Object[] actual = Objects.requireNonNull(args).clone();
        ModernXposedInterface.validateArguments(target, receiver, actual);
        return nativeInvokeSpecial(target, target.getDeclaringClass(), receiver, actual,
                target.getParameterTypes(), target.getReturnType());
    }

    private static native boolean nativeReady();
    private static native Method nativeHook(Member target, Object dispatcher, Method callback);
    private static native boolean nativeDeoptimize(Member target);
    private static native Object nativeAllocateInstance(Class<?> type) throws InstantiationException;
    private static native Method nativeClassInitializer(Class<?> type);
    private static native Object nativeInvokeSpecial(Method target, Class<?> owner, Object receiver,
                                                     Object[] args, Class<?>[] types, Class<?> returnType);
}
