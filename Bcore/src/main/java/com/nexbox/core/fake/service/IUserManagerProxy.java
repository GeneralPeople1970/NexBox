package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.Bundle;

import java.lang.reflect.Method;
import java.util.ArrayList;

import black.android.content.pm.BRUserInfo;
import black.android.os.BRIUserManagerStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.hook.ProxyMethods;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.core.system.user.BUserInfo;


public class IUserManagerProxy extends BinderInvocationStub {
    public IUserManagerProxy() {
        super(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIUserManagerStub.get().asInterface(BRServiceManager.get().getService(Context.USER_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.USER_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getApplicationRestrictions")
    public static class GetApplicationRestrictions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Privacy-first: never delegate per-app restrictions to the host.
            // Sandboxed apps normally do not have enterprise restrictions, and
            // Chromium-based browsers crash if the framework throws here.
            return new Bundle();
        }
    }

    @ProxyMethod("getApplicationRestrictionsForUser")
    public static class GetApplicationRestrictionsForUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Android 14+/16 paths may call the "*ForUser" variant directly.
            // Return an empty bundle rather than leaking host policy state or
            // surfacing a SecurityException for the sandbox package/uid pair.
            return new Bundle();
        }
    }

    @ProxyMethod("getProfileParent")
    public static class GetProfileParent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object nexBox = BRUserInfo.get()._new(BActivityThread.getUserId(), "NexBox", BRUserInfo.get().FLAG_PRIMARY());
            return nexBox;
        }
    }

    @ProxyMethod("getUserInfo")
    public static class GetUserInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int currentUser = BActivityThread.getUserId();
            if (args == null || args.length != 1 || !(args[0] instanceof Integer)
                    || (Integer) args[0] != currentUser) return null;
            // A virtual group is an independent full user, not a host managed profile.
            // Do not ask Android for privileged host user records or expose other groups.
            BUserInfo user = BUserManager.get().getService().getUserInfo(currentUser);
            if (user == null) return null;
            int flags = BRUserInfo.get().FLAG_PRIMARY();
            Class<?> type = method.getReturnType();
            for (String flag : new String[]{"FLAG_FULL", "FLAG_MAIN"}) {
                try {
                    flags |= type.getField(flag).getInt(null);
                } catch (NoSuchFieldException ignored) {
                    // Older Android releases do not define these user-type flags.
                }
            }
            Object info = BRUserInfo.get()._new(currentUser,
                    user.name == null || user.name.isEmpty() ? "NexBox" : user.name, flags);
            type.getField("creationTime").setLong(info, user.createTime);
            return info;
        }
    }

    @ProxyMethod("getUsers")
    public static class getUsers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return new ArrayList<>();
        }
    }

    @ProxyMethods({"getSeedAccountName", "getSeedAccountType", "getSeedAccountOptions"})
    public static class GetSeedAccount extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) {
            // Container users are not provisioned with a system seed account. The normal
            // account service remains authoritative; never expose the device owner's seed.
            return null;
        }
    }
}
