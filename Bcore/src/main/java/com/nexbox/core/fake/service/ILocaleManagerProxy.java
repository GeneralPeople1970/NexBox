package com.nexbox.core.fake.service;

import android.app.LocaleConfig;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.LocaleList;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import black.android.app.BRLocaleManager;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.app.GuestLocaleController;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.fake.hook.BinderInvocationStub;

public final class ILocaleManagerProxy extends BinderInvocationStub {
    private static final String SERVICE = "locale";
    private static volatile ILocaleManagerProxy installed;
    private static final Map<Object, Boolean> MANAGERS = Collections.synchronizedMap(new WeakHashMap<>());
    private final IInterface platform;

    public ILocaleManagerProxy() { this(originalBinder()); }

    private ILocaleManagerProxy(IBinder binder) {
        super(binder);
        platform = asInterface(binder);
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService(SERVICE);
        while (binder instanceof ILocaleManagerProxy) {
            IInterface original = ((ILocaleManagerProxy) binder).platform;
            binder = original == null ? null : original.asBinder();
        }
        return binder;
    }

    private static IInterface asInterface(IBinder binder) {
        if (binder == null) return null;
        try {
            return (IInterface) Class.forName("android.app.ILocaleManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Locale service interface is unavailable", failure);
        }
    }

    @Override protected Object getWho() { return platform; }

    @Override protected void inject(Object base, Object proxy) {
        replaceSystemService(SERVICE);
        installed = this;
        synchronized (MANAGERS) { for (Object manager : MANAGERS.keySet()) replaceManager(manager); }
        fixContext(NexBoxCore.getContext());
        fixContext(BActivityThread.getApplication());
    }

    public static void fixContext(Context context) {
        ILocaleManagerProxy hook = installed;
        if (Build.VERSION.SDK_INT < 33 || context == null || hook == null || hook.getProxyInvocation() == null
                || !NexBoxCore.get().isGuestProcess()) return;
        Object manager = context.getSystemService(SERVICE);
        if (manager == null) return;
        MANAGERS.put(manager, Boolean.TRUE);
        hook.replaceManager(manager);
    }

    private void replaceManager(Object manager) {
        BRLocaleManager.getWithException(manager)._set_mService((IInterface) getProxyInvocation());
    }

    @Override public boolean isBadEnv() {
        if (platform == null) return Build.VERSION.SDK_INT >= 33;
        if (BRServiceManager.get().getService(SERVICE) != this) return true;
        synchronized (MANAGERS) {
            for (Object manager : MANAGERS.keySet()) {
                if (BRLocaleManager.getWithException(manager).mService() != getProxyInvocation()) return true;
            }
        }
        return false;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if ("asBinder".equals(name)) return this;
        if (!NexBoxCore.get().isGuestProcess() || method.getDeclaringClass() == Object.class
                || "getSystemLocales".equals(name)) return invokePlatform(method, args);
        if (args == null || args.length < 2 || !(args[0] instanceof String) || !(args[1] instanceof Integer)) {
            throw new UnsupportedOperationException("Unsupported locale request: " + name);
        }
        String packageName = (String) args[0];
        IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
        if (packages == null) throw new IllegalStateException("Virtual package service is unavailable");
        int userId = BActivityThread.getUserId();
        switch (name) {
            case "getApplicationLocales":
                return LocaleList.forLanguageTags(packages.getApplicationLocaleState(packageName, userId).getString("locales", ""));
            case "setApplicationLocales":
                if (args.length < 3 || !(args[2] instanceof LocaleList)) throw new IllegalArgumentException("Missing locale list");
                packages.setApplicationLocales(packageName, ((LocaleList) args[2]).toLanguageTags(), userId);
                GuestLocaleController.requestRefresh();
                return null;
            case "getOverrideLocaleConfig":
                if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("Locale config requires Android 14");
                Bundle state = packages.getApplicationLocaleState(packageName, userId);
                String tags = state.getString("override");
                return tags == null ? null : new LocaleConfig(LocaleList.forLanguageTags(tags));
            case "setOverrideLocaleConfig":
                if (Build.VERSION.SDK_INT < 34 || args.length != 3) throw new UnsupportedOperationException("Unsupported locale config request");
                LocaleConfig config = (LocaleConfig) args[2];
                if (config != null && (config.getStatus() != LocaleConfig.STATUS_SUCCESS || config.getSupportedLocales() == null)) {
                    throw new IllegalArgumentException("Invalid locale config");
                }
                packages.setOverrideLocaleConfig(packageName, config == null ? null : config.getSupportedLocales().toLanguageTags(), userId);
                return null;
            default:
                throw new UnsupportedOperationException("Unsupported locale request: " + name);
        }
    }

    private Object invokePlatform(Method method, Object[] args) throws Throwable {
        try { return method.invoke(platform, args); }
        catch (InvocationTargetException failure) { throw failure.getCause() == null ? failure : failure.getCause(); }
    }
}
