package com.nexbox.core.core;


import android.os.Binder;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.Keep;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import dalvik.system.DexFile;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BActivityManager;
import com.nexbox.core.utils.LocalProviderIdentity;
import com.nexbox.core.utils.GuestProviderIdentity;
import com.nexbox.core.utils.compat.DexFileCompat;

import com.nexbox.core.core.system.JarManager;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.xposed.RuntimeLog;
import java.nio.charset.StandardCharsets;


public class NativeCore {
    public static final String TAG = "NativeCore";
    private static final ThreadLocal<Boolean> RESOLVING_CALLER = new ThreadLocal<>();
    private static final AtomicLong LAST_CALLER_LOOKUP_WARNING = new AtomicLong(-10000L);

    static {

        System.loadLibrary("nexbox");
        RuntimeLog.addConfigurationListener(NativeCore::setLoggingEnabled);
    }

    private static native void setLoggingEnabled(boolean enabled);

    /** Read-only diagnostic self-test, invoked only by the debug instrumentation service. */
    @Keep
    private static native void diagnosticLogProbe(String marker);

    @Keep
    public static void emitNativeLog(int priority, String tag, byte[] message) {
        RuntimeLog.write(priority, tag, new String(message, StandardCharsets.UTF_8), null);
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    /** Atomically publish a synced same-directory copy, without replacing an existing file. */
    public static native void publishMediaFile(String temporaryPath, String targetPath) throws IOException;
    /** Returns a private leased descriptor, or negative errno. Closing it releases the lease. */
    public static native int acquireMediaFileLease(String path);
    public static native boolean isMediaFileLeaseHeld(int fd);

    public static native void hideXposed();

    public static native boolean disableHiddenApi();
    
    public static native boolean disableResourceLoading();

    public static native void setSimSpoof(String[] names, String[] values);

    public static native void setDeviceSpoof(
            String manufacturer,
            String brand,
            String model,
            String device,
            String product,
            String fingerprint,
            String serial
    );


    @Keep
    public static int getCallingUid(int origCallingUid) {
        try {
            // System services and non-guest container processes keep their real Binder caller.
            // Do not reject multi-user host UIDs by comparing the full UID with an app-id range.
            if (origCallingUid != NexBoxCore.getHostUid() || !NexBoxCore.get().isGuestProcess()) {
                return origCallingUid;
            }
            AppConfig config = BActivityThread.getAppConfig();
            if (config == null || config.buid < Process.FIRST_APPLICATION_UID) return origCallingUid;
            int callerPid = Binder.getCallingPid();
            int asyncUid = GuestProviderIdentity.getUid();
            // Only a receiver-authenticated oneway boundary may supply a UID without a PID.
            // Clearing Binder identity or making a local/outgoing hop must not inherit it.
            if (callerPid == 0 && asyncUid >= Process.FIRST_APPLICATION_UID) return asyncUid;
            return CallingUidPolicy.resolve(origCallingUid, NexBoxCore.getHostUid(),
                    callerPid, Process.myPid(), config.buid,
                    LocalProviderIdentity.isSystemProvider(), NativeCore::resolveGuestCallerUid);
        } catch (RuntimeException failure) {
            // Never replace an unknown caller with SYSTEM_UID, this app, or the process launcher.
            logCallerLookupFailure(-1, failure.getClass().getSimpleName());
            return origCallingUid;
        }
    }

    private static int resolveGuestCallerUid(int callerPid) {
        if (Boolean.TRUE.equals(RESOLVING_CALLER.get())) return -1;
        RESOLVING_CALLER.set(true);
        try {
            IBActivityManagerService service = BActivityManager.get().getService();
            if (service == null) {
                logCallerLookupFailure(callerPid, "service_unavailable");
                return -1;
            }
            // Read the live registry for each call: caching a PID after process death can map a
            // new caller to the old guest. The server never takes a process-start lock here.
            return service.resolveCallingUid(callerPid);
        } catch (android.os.RemoteException | RuntimeException failure) {
            logCallerLookupFailure(callerPid, failure.getClass().getSimpleName());
            return -1;
        } finally {
            RESOLVING_CALLER.remove();
        }
    }

    private static void logCallerLookupFailure(int callerPid, String error) {
        try {
            long now = SystemClock.uptimeMillis();
            long previous = LAST_CALLER_LOOKUP_WARNING.get();
            if (now - previous < 10000L || !LAST_CALLER_LOOKUP_WARNING.compareAndSet(previous, now)) return;
            Slog.w(TAG, "stage=resolveCallingUid callerPid=" + callerPid + " fallback=realBinderUid error=" + error);
        } catch (RuntimeException ignored) { }
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            File emptyJar = JarManager.getInstance().getEmptyJar();
            // Native cookie probing needs this isolated DexFile, not a guest class-loading path.
            // Standard ClassLoader APIs do not expose that handle.
            @SuppressWarnings("deprecation")
            DexFile dexFile = new DexFile(emptyJar);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            Slog.d(TAG, "Successfully loaded empty DEX with " + cookies.size() + " cookies");
            return longs;
        } catch (Exception e) {
            Slog.e(TAG, "Failed to load empty DEX", e);
        }
        return new long[]{};
    }
}
