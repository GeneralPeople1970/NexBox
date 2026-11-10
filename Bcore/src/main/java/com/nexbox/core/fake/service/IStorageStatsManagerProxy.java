package com.nexbox.core.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.usage.BRIStorageStatsManagerStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.MethodParameterUtils;

public class IStorageStatsManagerProxy extends BinderInvocationStub {

    public IStorageStatsManagerProxy() {
        super(BRServiceManager.get().getService(Context.STORAGE_STATS_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIStorageStatsManagerStub.get().asInterface(BRServiceManager.get().getService(Context.STORAGE_STATS_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.STORAGE_STATS_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        MethodParameterUtils.replaceFirstAppPkg(args);
        return super.invoke(proxy, method, args);
    }
}
