package com.nexbox.core.fake.service;

import java.lang.reflect.Method;

import black.android.app.BRActivityThread;
import black.android.os.BRServiceManager;
import black.android.permission.BRIPermissionManagerStub;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethods;
import com.nexbox.core.fake.service.base.PkgMethodProxy;
import com.nexbox.core.fake.service.base.ValueMethodProxy;


public class IPermissionManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IPermissionManagerProxy";

    private static final String P = "permissionmgr";

    public IPermissionManagerProxy() {
        super(BRServiceManager.get().getService(P));
    }

    @Override
    protected Object getWho() {
        return BRIPermissionManagerStub.get().asInterface(BRServiceManager.get().getService(P));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("permissionmgr");
        BRActivityThread.getWithException()._set_sPermissionManager(proxyInvocation);
        GuestPermissionChecks.configureGuestPermissionCaches();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addPermissionAsync", true));
        addMethodHook(new ValueMethodProxy("addPermission", true));
        addMethodHook(new ValueMethodProxy("performDexOpt", true));
        addMethodHook(new ValueMethodProxy("performDexOptIfNeeded", false));
        addMethodHook(new ValueMethodProxy("performDexOptSecondary", true));
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("checkDeviceIdentifierAccess", false));
        addMethodHook(new PkgMethodProxy("shouldShowRequestPermissionRationale"));
        addMethodHook(new ValueMethodProxy("notifyDexLoad", 0));
        addMethodHook(new ValueMethodProxy("notifyPackageUse", 0));
        addMethodHook(new ValueMethodProxy("setInstantAppCookie", false));
        addMethodHook(new ValueMethodProxy("isInstantApp", false));
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethods({"checkPermission", "checkUidPermission"})
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GuestPermissionChecks.invoke(PermissionCheckPolicy.Backend.PERMISSION, who, method, args);
        }
    }

}
