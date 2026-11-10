package com.nexbox.core.fake.service;


import black.android.os.BRServiceManager;
import black.android.view.BRIGraphicsStatsStub;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.service.base.PkgMethodProxy;


public class IFingerprintManagerProxy extends BinderInvocationStub {
    // SDK 37 removed Context.FINGERPRINT_SERVICE; the legacy Binder service name is unchanged.
    private static final String FINGERPRINT_SERVICE = "fingerprint";

    public IFingerprintManagerProxy() {
        super(BRServiceManager.get().getService(FINGERPRINT_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIGraphicsStatsStub.get().asInterface(BRServiceManager.get().getService(FINGERPRINT_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(FINGERPRINT_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("isHardwareDetected"));
        addMethodHook(new PkgMethodProxy("hasEnrolledFingerprints"));
        addMethodHook(new PkgMethodProxy("authenticate"));
        addMethodHook(new PkgMethodProxy("cancelAuthentication"));
        addMethodHook(new PkgMethodProxy("getEnrolledFingerprints"));
        addMethodHook(new PkgMethodProxy("getAuthenticatorId"));
    }
}
