package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.IBinder;
import java.lang.reflect.Method;
import black.android.app.job.BRIJobSchedulerStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;

public class IJobServiceProxy extends BinderInvocationStub {
    public IJobServiceProxy() { super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE)); }
    @Override protected Object getWho() {
        return BRIJobSchedulerStub.get().asInterface(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }
    @Override protected void inject(Object baseInvocation, Object proxyInvocation) { replaceSystemService(Context.JOB_SCHEDULER_SERVICE); }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            switch (method.getName()) {
                case "toString": return "VirtualJobScheduler";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return args != null && args.length == 1 && proxy == args[0];
            }
        }
        if ("asBinder".equals(method.getName())) return this;
        // The server also installs this hook. Its own scheduler must retain the OS
        // binder rather than re-entering the guest service after clearCallingIdentity.
        if (!com.nexbox.core.NexBoxCore.get().isGuestProcess()) {
            try { return method.invoke(getBase(), args); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }
        return GoogleJobCompat.invoke(method, args);
    }
    @Override public boolean isBadEnv() { return false; }
    @Override public boolean transact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) {
        throw new SecurityException("Raw guest JobScheduler IPC is unsupported");
    }
}
