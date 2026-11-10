package com.nexbox.core.fake.service;

import android.content.pm.PackageManager;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.utils.Slog;

/** A container without platform Safety Center privileges cannot enable this system capability. */
public final class ISafetyCenterManagerProxy extends BinderInvocationStub {
    private static final String SERVICE = "safety_center";

    public ISafetyCenterManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        if (binder == null) return null;
        try {
            return Class.forName("android.safetycenter.ISafetyCenterManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException failure) {
            Slog.w("SafetyCenterProxy", "Safety Center interface unavailable", failure);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        return binder != null && binder != this;
    }

    @ProxyMethod("isSafetyCenterEnabled")
    public static final class IsEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = NexBoxCore.getHostUid();
            if (NexBoxCore.getContext().checkPermission("android.permission.READ_SAFETY_CENTER_STATUS", -1, uid)
                    != PackageManager.PERMISSION_GRANTED
                    && NexBoxCore.getContext().checkPermission("android.permission.SEND_SAFETY_CENTER_UPDATE", -1, uid)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
            try {
                return method.invoke(who, args);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }
}
