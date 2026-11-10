package com.nexbox.core.fake.frameworks;

import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.ParameterizedType;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.Slog;

public abstract class CoreServiceManager<Service extends IInterface> {
    public static final String TAG = "CoreServiceManager";
    private volatile Service mService;

    protected abstract String getServiceName();

    public Service getService() {
        NexBoxCore core = NexBoxCore.get();
        IBinder binder = core.getService(getServiceName());
        if (binder == null) return null;
        Service cached = mService;
        if (cached != null && cached.asBinder() == binder) return cached;

        synchronized (this) {
            cached = mService;
            if (cached != null && cached.asBinder() == binder) return cached;
            if (!core.isCachedService(getServiceName(), binder)) return null;
            try {
                String stubClassName = getTClass().getName() + "$Stub";
                Service connected = Reflector.on(stubClassName)
                        .method("asInterface", IBinder.class).call(binder);
                if (connected != null && core.isCachedService(getServiceName(), binder)) {
                    mService = connected;
                    return connected;
                }
            } catch (Exception failure) {
                Slog.e(TAG, "Cannot create service interface: " + getServiceName(), failure);
            }
            return null;
        }
    }

    public void clearServiceCache() {
        Service old;
        synchronized (this) {
            old = mService;
            mService = null;
        }
        NexBoxCore.get().invalidateService(getServiceName(), old == null ? null : old.asBinder());
    }

    public boolean isServiceHealthy() {
        Service cached = mService;
        return cached != null && NexBoxCore.get().isCachedService(getServiceName(), cached.asBinder());
    }

    @SuppressWarnings("unchecked")
    private Class<Service> getTClass() {
        return (Class<Service>) ((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0];
    }
}
