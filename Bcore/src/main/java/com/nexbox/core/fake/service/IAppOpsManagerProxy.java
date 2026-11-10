package com.nexbox.core.fake.service;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.fake.hook.BinderInvocationStub;



import com.nexbox.core.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) NexBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                Slog.e("IAppOpsManagerProxy", "Container operation failed", e);
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] mapped = args == null ? null : args.clone();
        String name = method.getName();
        if ("checkPackage".equals(name) && mapped != null && mapped.length == 2
                && mapped[0] instanceof Integer && mapped[1] instanceof String
                && (Integer) mapped[0] != NexBoxCore.getHostUid()) {
            // A guest provider validates its virtual caller, not a Linux package owned by
            // the host UID. Consult the installed record; never blanket-allow the pair.
            com.nexbox.core.core.system.pm.IBPackageManagerService packages =
                    NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            android.content.pm.PackageInfo info = packages.getPackageInfo((String) mapped[1], 0,
                    com.nexbox.core.app.BActivityThread.getUserId());
            return info != null && info.applicationInfo != null
                    && info.applicationInfo.uid == (Integer) mapped[0]
                    ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_ERRORED;
        }
        int uidIndex = -1;
        int packageIndex = -1;
        int attributionIndex = -1;
        switch (name) {
            case "checkPackage":
                uidIndex = 0; packageIndex = 1; break;
            case "checkOperation":
            case "checkOperationRaw":
            case "checkOperationForDevice":
            case "checkOperationRawForDevice":
            case "noteOperation":
            case "noteOperationForDevice":
                uidIndex = 1; packageIndex = 2; attributionIndex = 3; break;
            case "startOperation":
            case "startOperationForDevice":
            case "finishOperation":
            case "finishOperationForDevice":
                uidIndex = 2; packageIndex = 3; attributionIndex = 4; break;
        }
        Class<?>[] types = method.getParameterTypes();
        if (mapped != null && mapped.length == types.length && uidIndex >= 0
                && packageIndex < types.length && types[uidIndex] == int.class
                && types[packageIndex] == String.class && mapped[uidIndex] instanceof Integer) {
            String guest = com.nexbox.core.app.BActivityThread.getAppPackageName();
            String host = NexBoxCore.getHostPkg();
            int uid = (Integer) mapped[uidIndex];
            if (guest != null && !guest.isEmpty()
                    && (guest.equals(mapped[packageIndex]) || host.equals(mapped[packageIndex]))
                    && (uid == com.nexbox.core.app.BActivityThread.getBUid() || uid == NexBoxCore.getHostUid())) {
                mapped[uidIndex] = NexBoxCore.getHostUid();
                mapped[packageIndex] = host;
                if (attributionIndex >= 0 && attributionIndex < types.length
                        && types[attributionIndex] == String.class) mapped[attributionIndex] = null;
            }
        }
        try {
            // Preserve the actual mode/SyncNotedAppOp and pair starts with real finishes.
            // Permission denial must not become a fabricated MODE_ALLOWED or a wrong return type.
            return method.invoke(getBase(), mapped);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
