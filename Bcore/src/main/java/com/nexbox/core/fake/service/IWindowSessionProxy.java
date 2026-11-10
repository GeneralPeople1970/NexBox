package com.nexbox.core.fake.service;

import android.os.IInterface;
import android.view.WindowManager;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.TextInputDiagnostics;
import com.nexbox.core.utils.TextInputDiagnostics.Component;



public class IWindowSessionProxy extends BinderInvocationStub {
    public static final String TAG = "WindowSessionStub";

    private final IInterface mSession;

    public IWindowSessionProxy(IInterface session) {
        super(session.asBinder());
        mSession = session;
    }

    public static IInterface wrap(IInterface session) {
        if (session == null || (Proxy.isProxyClass(session.getClass())
                && Proxy.getInvocationHandler(session) instanceof IWindowSessionProxy)) return session;
        IWindowSessionProxy hook = new IWindowSessionProxy(session);
        hook.injectHook();
        return (IInterface) hook.getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mSession;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object getProxyInvocation() {
        return super.getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (!NexBoxCore.get().isGuestProcess() || !TextInputCallPolicy.windowLayout(method.getName())) {
            return super.invoke(proxy, method, args);
        }
        return TextInputDiagnostics.call(Component.WINDOW, method.getName(), () -> {
            Object[] mapped = TextInputCallPolicy.windowUser(method.getName(),
                    TextInputCallPolicy.parameterNames(method), args, NexBoxCore.getHostUserId());
            if (mapped != null) {
                if (mapped == args) mapped = args.clone();
                for (int index = 0; index < mapped.length; index++) {
                    if (!(mapped[index] instanceof WindowManager.LayoutParams)) continue;
                    WindowManager.LayoutParams layout = new WindowManager.LayoutParams();
                    layout.copyFrom((WindowManager.LayoutParams) mapped[index]);
                    layout.packageName = NexBoxCore.getHostPkg();
                    if (NexBoxCore.get().isDisableFlagSecure()) {
                        layout.flags &= ~WindowManager.LayoutParams.FLAG_SECURE;
                    }
                    // Preserve the real parent token, type, focus/touch flags and geometry.
                    mapped[index] = layout;
                }
            }
            Object result = super.invoke(proxy, method, mapped);
            if (result instanceof Integer && (Integer) result < 0) {
                TextInputDiagnostics.rejected(Component.WINDOW, method.getName(), (Integer) result);
            }
            return result;
        });
    }
}
