package com.nexbox.core.fake.service;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.view.BRIWindowManagerStub;
import black.android.view.BRWindowManagerGlobal;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.TextInputDiagnostics;
import com.nexbox.core.utils.TextInputDiagnostics.Component;


public class IWindowManagerProxy extends BinderInvocationStub {
    public static final String TAG = "WindowManagerStub";
    private final IBinder platformBinder;
    private IInterface platformService;

    public IWindowManagerProxy() {
        this(originalBinder());
    }

    private IWindowManagerProxy(IBinder binder) {
        super(binder);
        platformBinder = binder;
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService("window");
        while (binder instanceof IWindowManagerProxy) binder = ((IWindowManagerProxy) binder).platformBinder;
        return binder;
    }

    @Override
    protected Object getWho() {
        if (platformService == null) {
            platformService = BRIWindowManagerStub.getWithException().asInterface(platformBinder);
            if (platformService == null) throw new IllegalStateException("Window service is unavailable");
        }
        return platformService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("window");
        // A host window may have opened the shared session before guest hooks were installed.
        // Keep that session and its callbacks; only adapt its outgoing system calls.
        try {
            synchronized (Class.forName("android.view.WindowManagerGlobal")) {
                BRWindowManagerGlobal.getWithException()._set_sWindowManagerService((IInterface) proxyInvocation);
                if (BRWindowManagerGlobal.get()._check_sWindowSession() != null) {
                    IInterface session = BRWindowManagerGlobal.getWithException().sWindowSession();
                    if (session != null) {
                        BRWindowManagerGlobal.getWithException()._set_sWindowSession(IWindowSessionProxy.wrap(session));
                    }
                }
            }
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Window manager cache is unavailable", failure);
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!"openSession".equals(method.getName())) return super.invoke(proxy, method, args);
        // This dispatch must already work while inject() publishes the global service cache.
        return TextInputDiagnostics.call(Component.WINDOW, method.getName(),
                () -> IWindowSessionProxy.wrap((IInterface) super.invoke(proxy, method, args)));
    }
}
