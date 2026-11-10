package com.nexbox.core.fake.service;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.ScanClass;
import com.nexbox.core.utils.Slog;

/** Preserve real networks, callback registrations and permission failures at the system boundary. */
@ScanClass(VpnCommonProxy.class)
public class IConnectivityManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IConnectivityManagerProxy";

    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(
                BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        String[] types = new String[parameterTypes.length];
        for (int i = 0; i < types.length; i++) types[i] = parameterTypes[i].getName();
        try {
            Object[] mapped = ConnectivityCallPolicy.rewrite(name, types, args,
                    BActivityThread.getAppPackageName(), BActivityThread.getBUid(), BActivityThread.getUserId(),
                    NexBoxCore.getHostPkg(), NexBoxCore.getHostUid(), NexBoxCore.getHostUserId());
            // Keep the existing, narrowly named VPN adapters. Network queries and callback
            // registration call the actual Binder once; they never fabricate Network(1),
            // validated/Wi-Fi capabilities, DNS servers, or a successful null request.
            Object result = isVpnAdapter(name) ? super.invoke(proxy, method, mapped)
                    : method.invoke(getBase(), mapped);
            if (ConnectivityCallPolicy.isRegistration(name)) {
                logRegistrationResult(name, method.getReturnType() == void.class || result != null);
            }
            if ("getActiveNetwork".equals(name) || "getActiveNetworkForUid".equals(name)) {
                logNetworkSelection(name, result);
            }
            return result;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            logFailure(name, types, cause);
            throw cause;
        } catch (RuntimeException failure) {
            logFailure(name, types, failure);
            throw failure;
        }
    }

    private static boolean isVpnAdapter(String name) {
        return "setVpnPackageAuthorization".equals(name) || "prepareVpn".equals(name)
                || "establishVpn".equals(name);
    }

    private static void logNetworkSelection(String method, Object result) {
        try {
            android.net.ConnectivityManager connectivity = NexBoxCore.getContext()
                    .getSystemService(android.net.ConnectivityManager.class);
            android.net.Network bound = connectivity.getBoundNetworkForProcess();
            Slog.d(TAG, "action=" + method + " selectedNetwork=" + result + " boundNetwork=" + bound
                    + " guest=" + BActivityThread.getAppPackageName()
                    + " virtualUser=" + BActivityThread.getUserId()
                    + " transportUid=" + NexBoxCore.getHostUid());
        } catch (Throwable ignored) {
            // Network diagnostics must not alter the query result.
        }
    }

    private static void logRegistrationResult(String method, boolean accepted) {
        try {
            if (accepted) Slog.d(TAG, "action=" + method + " requestAccepted=true");
            else Slog.w(TAG, "action=" + method + " requestAccepted=false reason=null_system_result");
        } catch (Throwable ignored) {
            // Diagnostics must never replace a real registration result.
        }
    }

    private static void logFailure(String method, String[] parameterTypes, Throwable failure) {
        // No URLs, SSIDs, IP addresses, callback payloads or request contents in diagnostics.
        try {
            Slog.w(TAG, "action=" + method + " signature=" + Arrays.toString(parameterTypes)
                    + " guest=" + BActivityThread.getAppPackageName()
                    + " virtualUser=" + BActivityThread.getUserId()
                    + " transportCaller=" + NexBoxCore.getHostPkg()
                    + " transportUid=" + NexBoxCore.getHostUid()
                    + " failed=" + failure.getClass().getSimpleName());
        } catch (Throwable ignored) {
            // Preserve the original Binder/permission exception even if logging is unavailable.
        }
    }
}
