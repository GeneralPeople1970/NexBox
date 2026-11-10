package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import black.android.content.BRClipboardManager;
import black.android.content.BRIClipboardStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.TextInputDiagnostics;
import com.nexbox.core.utils.TextInputDiagnostics.Component;

/** Keep guest Context identities intact, and translate only calls crossing into system_server. */
public final class IClipboardStub extends BinderInvocationStub {
    private static volatile IClipboardStub sInstalled;
    private static final Map<Object, Boolean> CACHED_MANAGERS = Collections.synchronizedMap(new WeakHashMap<>());
    private final IBinder mPlatformBinder;
    private IInterface mClipboard;

    public IClipboardStub() {
        this(findOriginalBinder());
    }

    private IClipboardStub(IBinder binder) {
        super(binder);
        mPlatformBinder = binder;
    }

    private static IBinder findOriginalBinder() {
        IBinder binder = BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE);
        // HookManager can be initialized again. Do not make a second proxy delegate
        // to the first one instead of retaining the real Binder service.
        while (binder instanceof IClipboardStub) {
            binder = ((IClipboardStub) binder).mPlatformBinder;
        }
        return binder;
    }

    @Override
    protected Object getWho() {
        // Retain the real service. A re-injection must not wrap our own cached Binder again.
        if (mClipboard == null) {
            mClipboard = BRIClipboardStub.getWithException().asInterface(mPlatformBinder);
            if (mClipboard == null) throw new IllegalStateException("Clipboard service is unavailable");
        }
        return mClipboard;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CLIPBOARD_SERVICE);
        sInstalled = this;
        // Pre-O/vendor frameworks may cache a static service, while current Android caches
        // it on each ClipboardManager instance. Replace both, including managers created early.
        if (BRClipboardManager.get()._check_sService() != null) {
            BRClipboardManager.get()._set_sService((IInterface) proxyInvocation);
        }
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) replaceManagerService(manager);
        }
        fixContext(NexBoxCore.getContext());
        fixContext(BActivityThread.getApplication());
        Slog.dLazy(TextInputDiagnostics.TAG, () -> "component=CLIPBOARD stage=installed route=SYSTEM");
    }

    public static void fixContext(Context context) {
        IClipboardStub hook = sInstalled;
        if (context == null || hook == null || hook.getProxyInvocation() == null) return;
        Object manager = context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return;
        CACHED_MANAGERS.put(manager, Boolean.TRUE);
        hook.replaceManagerService(manager);
    }

    private void replaceManagerService(Object manager) {
        if (BRClipboardManager.get(manager)._check_mService() != null) {
            BRClipboardManager.getWithException(manager)._set_mService((IInterface) getProxyInvocation());
            if (BRClipboardManager.getWithException(manager).mService() != getProxyInvocation()) {
                throw new IllegalStateException("Clipboard manager still holds an unadapted service");
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        if (BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE) != this) return true;
        if (BRClipboardManager.get()._check_sService() != null
                && BRClipboardManager.get().sService() != getProxyInvocation()) return true;
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) {
                if (BRClipboardManager.get(manager)._check_mService() != null
                        && BRClipboardManager.get(manager).mService() != getProxyInvocation()) return true;
            }
        }
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Keep a real, parcelable Binder identity; the Java cache adapter is not an OS Binder.
        if ("asBinder".equals(method.getName())) return mPlatformBinder;
        if (!NexBoxCore.get().isGuestProcess() || BActivityThread.getAppConfig() == null
                || method.getDeclaringClass() == Object.class) return invokePlatform(method, args);
        // Keep Android's clipboard data, listener delivery, focus checks and permission
        // failures. No empty-clipboard fallback or swallowed SecurityException.
        Object[] mapped = args == null ? null : args.clone();
        boolean identityMapped = ClipboardCallPolicy.rewrite(method, mapped,
                NexBoxCore.getHostPkg(), NexBoxCore.getHostUserId());
        String contract = Slog.isLoggable(TextInputDiagnostics.TAG, android.util.Log.ERROR)
                ? "signature=" + TextInputDiagnostics.signature(method)
                        + " identity=" + (identityMapped ? "HOST" : "UNCHANGED") : "";
        return TextInputDiagnostics.call(Component.CLIPBOARD, method.getName(), contract, () -> {
            long identity = Binder.clearCallingIdentity();
            try {
                return invokePlatform(method, mapped);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        });
    }

    private Object invokePlatform(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(mClipboard, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }
}
