package com.nexbox.core.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.BRIAlarmManagerStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;


public class IAlarmManagerProxy extends BinderInvocationStub {

    public IAlarmManagerProxy() {
        super(BRServiceManager.get().getService(Context.ALARM_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIAlarmManagerStub.get().asInterface(BRServiceManager.get().getService(Context.ALARM_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ALARM_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) return this;
        Object[] request = args == null ? new Object[0] : args.clone();
        if (NexBoxCore.get().isGuestProcess()) {
            String name = method.getName();
            if ("setTime".equals(name) || "setTimeZone".equals(name) || "removeAll".equals(name)) {
                throw new SecurityException("Guest cannot modify host alarm state globally");
            }
            String pkg = BActivityThread.getAppPackageName();
            for (int i = 0; i < request.length; i++) {
                if (("set".equals(name) || "remove".equals(name)) && request[i] instanceof android.app.PendingIntent) {
                    android.os.Bundle check = new android.os.Bundle();
                    check.putParcelable("pending", (android.app.PendingIntent) request[i]);
                    if (!NexBoxCore.getBActivityManager().getService().pendingIntentOperation("validate", check).getBoolean("valid"))
                        throw new SecurityException("Invalid virtual alarm token");
                }
                if (pkg != null && pkg.equals(request[i])) request[i] = NexBoxCore.getHostPkg();
                if (request[i] instanceof android.os.WorkSource) request[i] = null;
            }
            // Direct listener alarms retain the real result and cancellation token.
            if ("set".equals(name) && request.length > 0 && request[0] instanceof String &&
                    !NexBoxCore.getHostPkg().equals(request[0])) throw new SecurityException("Alarm caller mismatch");
        }
        try { return method.invoke(getBase(), request); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
            throws android.os.RemoteException {
        if (NexBoxCore.get().isGuestProcess()) throw new SecurityException("Raw guest AlarmManager IPC is unsupported");
        return super.transact(code, data, reply, flags);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
