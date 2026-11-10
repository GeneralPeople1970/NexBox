package com.nexbox.core.fake.service;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.utils.Slog;


public class ISensorPrivacyManagerProxy extends BinderInvocationStub {
    public static final String TAG = "SensorPrivacyProxy";
    private final IBinder platformBinder;
    private IInterface platformService;

    public ISensorPrivacyManagerProxy() {
        this(originalBinder());
    }

    private ISensorPrivacyManagerProxy(IBinder binder) {
        super(binder);
        platformBinder = binder;
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService("sensor_privacy");
        while (binder instanceof ISensorPrivacyManagerProxy) {
            binder = ((ISensorPrivacyManagerProxy) binder).platformBinder;
        }
        return binder;
    }

    @Override
    protected Object getWho() {
        if (platformBinder == null) {
            Slog.w(TAG, "stage=unavailable reason=serviceMissing");
            return null;
        }
        if (platformService == null) {
            try {
                platformService = (IInterface) Class.forName("android.hardware.ISensorPrivacyManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, platformBinder);
                if (platformService == null) throw new IllegalStateException("Sensor privacy interface is null");
                Slog.d(TAG, "stage=resolved interface=ISensorPrivacyManager");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Sensor privacy interface is unavailable", failure);
            }
        }
        return platformService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("sensor_privacy");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    @ProxyMethod("isSensorPrivacyEnabled")
    public static class IsSensorPrivacyEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabled returning false");
            return false;
        }
    }

    
    @ProxyMethod("isSensorPrivacyEnabledForUser")
    public static class IsSensorPrivacyEnabledForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabledForUser returning false");
            return false;
        }
    }

    
    @ProxyMethod("isSensorPrivacyEnabledForProfile")
    public static class IsSensorPrivacyEnabledForProfile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: isSensorPrivacyEnabledForProfile returning false");
            return false;
        }
    }

    
    @ProxyMethod("setSensorPrivacy")
    public static class SetSensorPrivacy extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: setSensorPrivacy allowing");
            return method.invoke(who, args);
        }
    }

    
    @ProxyMethod("setSensorPrivacyForProfile")
    public static class SetSensorPrivacyForProfile extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "SensorPrivacy: setSensorPrivacyForProfile allowing");
            return method.invoke(who, args);
        }
    }
}

