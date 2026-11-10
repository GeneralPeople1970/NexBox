package com.nexbox.core.fake.service;

import java.lang.reflect.Method;

import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethods;

/** Shared by ITelephony and both cached/service-manager IPhoneSubInfo paths. */
public final class TelephonyIdentifierHooks {
    private TelephonyIdentifierHooks() {}

    @ProxyMethods({"getDeviceId", "getDeviceIdWithFeature", "getDeviceIdForPhone",
            "getImeiForSlot", "getImei", "getImeiForSubscriber", "getPrimaryImei"})
    public static class Imei extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!DeviceSpoofManager.isCurrentProcessEnabled()) return method.invoke(who, args);
            String name = method.getName();
            // Subscriber IDs are NOT slot indices. Only these APIs take a slot/phone index.
            if ("getImeiForSlot".equals(name) || "getDeviceIdForPhone".equals(name)) {
                if (args != null && args.length > 0 && args[0] instanceof Integer) {
                    return DeviceSpoofManager.getImeiForCurrentSlot((Integer) args[0]);
                }
            }
            return DeviceSpoofManager.getImeiForCurrentUser();
        }
    }

    @ProxyMethods({"getMeidForSlot", "getMeid"})
    public static class Meid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!DeviceSpoofManager.isCurrentProcessEnabled()) return method.invoke(who, args);
            // This profile describes an IMEI device; an IMEI/MD5 is not a CDMA MEID.
            return null;
        }
    }
}
