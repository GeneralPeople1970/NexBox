package com.nexbox.core.fake.service;

import android.os.IInterface;
import android.os.storage.StorageVolume;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.os.storage.BRIStorageManagerStub;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;


public class IStorageManagerProxy extends BinderInvocationStub {

    public IStorageManagerProxy() {
        super(BRServiceManager.get().getService("mount"));
    }

    @Override
    protected Object getWho() {
        IInterface mount;
        mount = BRIStorageManagerStub.get().asInterface(BRServiceManager.get().getService("mount"));
        return mount;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("mount");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getVolumeList")
    public static class GetVolumeList extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = 0;
            if (args != null && args.length != 0) {
                if ((args.length != 3 && args.length != 4) || !(args[0] instanceof Integer)
                        || (args[1] != null && !(args[1] instanceof String)) || !(args[2] instanceof Integer)
                        || (args.length == 4 && !(args[3] instanceof Integer))) {
                    throw new UnsupportedOperationException("Unsupported virtual storage volume query");
                }
                flags = (Integer) args[2];
            }
            StorageVolume[] volumes = NexBoxCore.getBStorageManager().getVolumeList(
                    BActivityThread.getBUid(), BActivityThread.getAppPackageName(), flags, BActivityThread.getUserId());
            if (volumes == null) throw new IllegalStateException("Virtual storage returned no volume receipt");
            // Never turn a denied/failed virtual query into a view of real phone storage.
            return volumes;
        }
    }

    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
