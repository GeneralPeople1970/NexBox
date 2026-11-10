package com.nexbox.core.fake.delegate;

import com.nexbox.core.utils.Slog;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import black.android.app.BRIServiceConnectionO;
import com.nexbox.core.fake.service.OaidServiceBinder;


public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;
    private final Set<Integer> mVirtualUsers = new HashSet<>();
    // Guarded by ServiceConnectionDelegate.class; failed provisional binds do not own the cache.
    private int mPendingBindings;
    private boolean mRetained;
    private IBinder.DeathRecipient mDeathRecipient;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static synchronized ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static ServiceConnectionDelegate getDelegate(IServiceConnection connection) {
        return connection instanceof ServiceConnectionDelegate ? (ServiceConnectionDelegate) connection
                : getDelegate(connection.asBinder());
    }

    public IBinder getOriginalBinder() {
        return mConn.asBinder();
    }

    public synchronized int[] getVirtualUsers() {
        int[] users = new int[mVirtualUsers.size()];
        int index = 0;
        for (int userId : mVirtualUsers) users[index++] = userId;
        return users;
    }

    public synchronized void clearVirtualUsers() {
        mVirtualUsers.clear();
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent, int virtualUserId) {
        ServiceConnectionDelegate delegate = (ServiceConnectionDelegate) createProxy(base, intent);
        synchronized (delegate) {
            delegate.mVirtualUsers.add(virtualUserId);
        }
        return delegate;
    }

    public static synchronized IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        ServiceConnectionDelegate delegate = findOrCreate(base, intent);
        delegate.mRetained = true;
        return delegate;
    }

    public static synchronized BindingLease acquireBinding(IServiceConnection base, Intent intent) {
        ServiceConnectionDelegate delegate = findOrCreate(base, intent);
        delegate.mPendingBindings++;
        return new BindingLease(delegate);
    }

    private static ServiceConnectionDelegate findOrCreate(IServiceConnection base, Intent intent) {
        if (base instanceof ServiceConnectionDelegate) return (ServiceConnectionDelegate) base;
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            ServiceConnectionDelegate created = delegate;
            IBinder.DeathRecipient death = () -> {
                synchronized (ServiceConnectionDelegate.class) {
                    sServiceConnectDelegate.remove(iBinder, created);
                }
                created.unlinkDeath();
            };
            try {
                iBinder.linkToDeath(death, 0);
                delegate.mDeathRecipient = death;
            } catch (RemoteException e) {
                try {
                    Slog.e("ServiceConnectionDelegate", "Container operation failed", e);
                } catch (Throwable ignored) {
                    // Death-recipient diagnostics cannot change the binding operation.
                }
            }
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    private void unlinkDeath() {
        IBinder.DeathRecipient death;
        synchronized (ServiceConnectionDelegate.class) {
            death = mDeathRecipient;
            mDeathRecipient = null;
        }
        if (death != null) {
            try {
                mConn.asBinder().unlinkToDeath(death, 0);
            } catch (RuntimeException failure) {
                Slog.w("ServiceConnectionDelegate", "action=bindService stage=unlinkDeath result=failed"
                        + " error=" + failure.getClass().getSimpleName());
            }
        }
    }

    public static final class BindingLease {
        private final ServiceConnectionDelegate delegate;
        private String outcome;

        private BindingLease(ServiceConnectionDelegate delegate) {
            this.delegate = delegate;
        }

        public IServiceConnection connection() { return delegate; }

        public String finish(boolean accepted) {
            boolean removed;
            synchronized (ServiceConnectionDelegate.class) {
                if (outcome != null) return outcome;
                if (accepted) delegate.mRetained = true;
                delegate.mPendingBindings--;
                removed = !delegate.mRetained && delegate.mPendingBindings == 0
                        && sServiceConnectDelegate.remove(delegate.mConn.asBinder(), delegate);
                outcome = removed ? "rolledBack" : "retained";
            }
            if (removed) delegate.unlinkDeath();
            return outcome;
        }
    }

    private void noteConnection(IBinder service, boolean dead) {
        if (service != null && !dead) {
            synchronized (ServiceConnectionDelegate.class) { mRetained = true; }
        }
    }

    @Override
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }

    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        noteConnection(service, dead);
        ComponentName component = name != null ? name : mComponentName;
        BRIServiceConnectionO.get(mConn).connected(component, OaidServiceBinder.wrap(component, service), dead);
    }

    // Android 16+ signature (added to framework after this project was originally compiled).
    // Intentionally NOT annotated with @Override to keep compatibility with older compile SDKs.
    public void connected(ComponentName name, IBinder service, android.app.IBinderSession session, boolean dead)
            throws RemoteException {
        noteConnection(service, dead);
        ComponentName component = name != null ? name : mComponentName;
        service = OaidServiceBinder.wrap(component, service);
        try {
            Method method = mConn.getClass().getMethod(
                    "connected",
                    ComponentName.class,
                    IBinder.class,
                    android.app.IBinderSession.class,
                    boolean.class
            );
            method.invoke(mConn, component, service, session, dead);
            return;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RemoteException) {
                throw (RemoteException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
        } catch (Throwable ignored) {
            // fall through
        }

        connected(name, service, dead);
    }
}
