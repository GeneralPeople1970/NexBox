package com.nexbox.core.fake.service;

import java.lang.reflect.Method;
import java.lang.reflect.Array;
import java.util.Collections;
import java.util.List;

import android.os.Bundle;
import android.text.TextUtils;

import black.android.telephony.BRTelephonyManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.ClassInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.hook.ScanClass;
import com.nexbox.core.utils.MethodParameterUtils;
import com.nexbox.core.utils.Slog;


@ScanClass(TelephonyIdentifierHooks.class)
public class IPhoneSubInfoProxy extends ClassInvocationStub {
    public static final String TAG = "IPhoneSubInfoProxy";

    public IPhoneSubInfoProxy() {
        if (BRTelephonyManager.get()._check_sServiceHandleCacheEnabled() != null) {
            BRTelephonyManager.get()._set_sServiceHandleCacheEnabled(true);
        }
        if (BRTelephonyManager.get()._check_getSubscriberInfoService() != null) {
            BRTelephonyManager.get().getSubscriberInfoService();
        }
    }

    @Override
    protected Object getWho() {
        return BRTelephonyManager.get().sIPhoneSubInfo();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRTelephonyManager.get()._set_sIPhoneSubInfo(proxyInvocation);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object simResult = SimServiceHooks.handle(false, method, args);
        if (simResult != SimServiceHooks.UNHANDLED) return simResult;
        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            rewriteCallingPackage(args);
            return super.invoke(proxy, method, args);
        } catch (Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SecurityException || cause instanceof IllegalArgumentException) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("does not belong to")
                        || msg.contains("callingPackage")
                        || msg.contains("Calling uid")
                        || msg.contains("Uid "))) {
                    Slog.w(TAG, "UID/package enforcement in IPhoneSubInfo call " + method.getName() + ", returning safe default: " + msg);
                    return safeReturnValue(method);
                }
            }
            throw e;
        }
    }

    private static void rewriteCallingPackage(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }

        String vPkg;
        try {
            vPkg = BActivityThread.getAppPackageName();
        } catch (Throwable ignored) {
            return;
        }

        String hostPkg = NexBoxCore.getHostPkg();
        if (TextUtils.isEmpty(vPkg) || TextUtils.isEmpty(hostPkg) || TextUtils.equals(vPkg, hostPkg)) {
            return;
        }

        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String && TextUtils.equals((String) args[i], vPkg)) {
                args[i] = hostPkg;
            }
        }
    }

    private static Object safeReturnValue(Method method) {
        Class<?> rt = method.getReturnType();
        if (rt == null || rt == Void.TYPE) {
            return null;
        }
        if (rt == Boolean.TYPE) {
            return false;
        }
        if (rt == Integer.TYPE) {
            return 0;
        }
        if (rt == Long.TYPE) {
            return 0L;
        }
        if (rt == Float.TYPE) {
            return 0f;
        }
        if (rt == Double.TYPE) {
            return 0d;
        }
        if (rt == String.class) {
            return "";
        }
        if (List.class.isAssignableFrom(rt)) {
            return Collections.emptyList();
        }
        if (rt == Bundle.class) {
            return new Bundle();
        }
        if (rt.isArray()) {
            return Array.newInstance(rt.getComponentType(), 0);
        }
        return null;
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }


    @ProxyMethod("getLine1NumberForSubscriber")
    public static class getLine1NumberForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }
}
