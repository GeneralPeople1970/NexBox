package com.nexbox.core.fake.service;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.lang.reflect.Proxy;

import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.IInjectHook;
import com.nexbox.core.utils.Slog;

/** Older StatsManager versions dereference a missing privileged service without checking null. */
public final class StatsManagerAvailabilityHook implements IInjectHook {
    private static final String SERVICE = "statsmanager";
    private IBinder unavailable;

    @Override
    public void injectHook() {
        if (BRServiceManager.get().getService(SERVICE) != null) return;
        try {
            Class<?> serviceType = Class.forName("android.os.IStatsManagerService");
            Binder binder = new Binder();
            IInterface endpoint = (IInterface) Proxy.newProxyInstance(serviceType.getClassLoader(),
                    new Class<?>[]{serviceType}, (proxy, method, args) -> {
                        if (method.getName().equals("asBinder")) return binder;
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "toString": return "UnavailableStatsManagerService";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == args[0];
                            }
                        }
                        // StatsManager translates RemoteException to StatsUnavailableException.
                        // No configuration, metrics, or successful writes are fabricated.
                        throw new RemoteException("StatsManager service is unavailable to the container");
                    });
            binder.attachInterface(endpoint, "android.os.IStatsManagerService");
            unavailable = binder;
            BRServiceManager.get().sCache().put(SERVICE, binder);
            Slog.i("StatsManagerAvailability", "capability=unavailable reason=serviceMissing failureMode=StatsUnavailableException");
        } catch (ReflectiveOperationException failure) {
            Slog.w("StatsManagerAvailability", "StatsManager interface unavailable", failure);
        }
    }

    @Override
    public boolean isBadEnv() {
        return unavailable != null && BRServiceManager.get().sCache().get(SERVICE) != unavailable;
    }
}
