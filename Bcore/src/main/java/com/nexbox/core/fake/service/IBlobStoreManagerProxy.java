package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import black.android.app.blob.BRBlobStoreManager;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.Slog;

/** Map only the outgoing system-service caller; keep guest Context identities unchanged. */
public final class IBlobStoreManagerProxy extends BinderInvocationStub {
    private static final String TAG = "IBlobStoreManagerProxy";
    private static volatile IBlobStoreManagerProxy installed;
    private static final Map<Object, Boolean> CACHED_MANAGERS = Collections.synchronizedMap(new WeakHashMap<>());
    private final IBinder platformBinder;
    private IInterface platformService;

    public IBlobStoreManagerProxy() {
        this(originalBinder());
    }

    private IBlobStoreManagerProxy(IBinder binder) {
        super(binder);
        platformBinder = binder;
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService(Context.BLOB_STORE_SERVICE);
        // Reinitialization must retain the platform Binder, not recursively wrap the old hook.
        while (binder instanceof IBlobStoreManagerProxy) {
            binder = ((IBlobStoreManagerProxy) binder).platformBinder;
        }
        return binder;
    }

    private static IInterface asInterface(IBinder binder) {
        if (binder == null) return null;
        try {
            return (IInterface) Class.forName("android.app.blob.IBlobStoreManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("BlobStore service interface is unavailable", failure);
        }
    }

    @Override
    protected Object getWho() {
        // Resolve optional framework interfaces inside HookManager's guarded injection phase.
        if (platformService == null) platformService = asInterface(platformBinder);
        return platformService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.BLOB_STORE_SERVICE);
        installed = this;
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) replaceManagerService(manager);
        }
        fixContext(NexBoxCore.getContext());
        fixContext(BActivityThread.getApplication());
    }

    public static void fixContext(Context context) {
        IBlobStoreManagerProxy hook = installed;
        if (Build.VERSION.SDK_INT < 30 || context == null || hook == null || hook.getProxyInvocation() == null
                || !NexBoxCore.get().isGuestProcess()) return;
        Object manager = context.getSystemService(Context.BLOB_STORE_SERVICE);
        if (manager == null) return;
        CACHED_MANAGERS.put(manager, Boolean.TRUE);
        hook.replaceManagerService(manager);
    }

    private void replaceManagerService(Object manager) {
        BRBlobStoreManager.getWithException(manager)._set_mService((IInterface) getProxyInvocation());
    }

    @Override
    public boolean isBadEnv() {
        IBinder binder = BRServiceManager.get().getService(Context.BLOB_STORE_SERVICE);
        if (platformService == null) return binder != null;
        if (getProxyInvocation() == null || binder != this) return true;
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) {
                if (BRBlobStoreManager.getWithException(manager).mService() != getProxyInvocation()) return true;
            }
        }
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) return this;
        if (!NexBoxCore.get().isGuestProcess() || method.getDeclaringClass() == Object.class) {
            return invokePlatform(method, args);
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        String[] types = new String[parameterTypes.length];
        for (int i = 0; i < types.length; i++) types[i] = parameterTypes[i].getName();
        String guestPackage = BActivityThread.getAppPackageName();
        Object[] mapped = BlobStoreCallPolicy.rewrite(method.getName(), types, args, guestPackage,
                NexBoxCore.getHostPkg(), IBlobStoreManagerProxy::resolveGuestDescription);
        if (mapped != args && Slog.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, "stage=identityMapped route=SYSTEM method=" + method.getName()
                    + " virtualUser=" + BActivityThread.getUserId() + " callerBefore=" + guestPackage
                    + " callerAfter=" + NexBoxCore.getHostPkg() + " uid=" + NexBoxCore.getHostUid());
        }
        long identity = Binder.clearCallingIdentity();
        try {
            // Preserve the real result and system checks, including SecurityException and quotas.
            return invokePlatform(method, mapped);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static CharSequence resolveGuestDescription(int resourceId) {
        Context application = BActivityThread.getApplication();
        if (application == null) throw new IllegalStateException("Guest resources are unavailable");
        return application.getString(resourceId);
    }

    private Object invokePlatform(Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(platformService, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }
}
