package com.nexbox.core.fake.service;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.os.Parcel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.utils.compat.ParcelCompat;


public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Only known caller-package layouts: never rewrite an arbitrary vendor argument.
            Class<?>[] types = method.getParameterTypes();
            Object[] mapped = args;
            if (args != null && args.length == types.length
                    && (types.length == 1 || types.length == 2)
                    && types[0] == String.class
                    && (types.length == 1 || types[1] == String.class)) {
                String guestPackage = BActivityThread.getAppPackageName();
                String hostPackage = NexBoxCore.getHostPkg();
                if (guestPackage != null && !guestPackage.isEmpty()
                        && (guestPackage.equals(args[0]) || hostPackage.equals(args[0]))) {
                    mapped = args.clone();
                    mapped[0] = hostPackage;
                    // A guest attribution tag is not declared by the host transport package.
                    if (mapped.length == 2) mapped[1] = null;
                }
            }
            WifiInfo wifiInfo;
            try {
                wifiInfo = (WifiInfo) method.invoke(who, mapped);
            } catch (InvocationTargetException failure) {
                // Keep real system permission failures catchable by the guest.
                throw failure.getCause() == null ? failure : failure.getCause();
            }
            if (wifiInfo == null || !DeviceSpoofManager.isCurrentProcessEnabled()) return wifiInfo;
            // Do not mutate a framework-cached WifiInfo. Keep the existing masked network
            // identity: BSSID belongs to the access point and is not this device's MAC.
            Parcel parcel = Parcel.obtain();
            try {
                // SDK 37's public stubs omit WifiInfo.CREATOR. Let Parcel resolve the platform
                // creator while preserving the same deep-copy behavior (no cached-object mutation).
                parcel.writeParcelable(wifiInfo, 0);
                parcel.setDataPosition(0);
                WifiInfo copy = ParcelCompat.readParcelable(parcel,
                        WifiInfo.class.getClassLoader(), WifiInfo.class);
                BRWifiInfo.get(copy)._set_mBSSID("ac:62:5a:82:65:c4");
                BRWifiInfo.get(copy)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded("NexBox_Wifi"));
                BRWifiInfo.get(copy)._set_mMacAddress(DeviceSpoofManager.getMacForCurrentUser());
                return copy;
            } finally {
                parcel.recycle();
            }
        }

        public static String intIP2StringIP(int ip) {
            return (ip & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." +
                    ((ip >> 16) & 0xFF) + "." +
                    (ip >> 24 & 0xFF);
        }

        public static int ip2Int(String ipString) {
            
            String[] ipSlices = ipString.split("\\.");
            int rs = 0;
            for (int i = 0; i < ipSlices.length; i++) {
                
                int intSlice = Integer.parseInt(ipSlices[i]) << 8 * i;
                
                rs = rs | intSlice;
            }
            return rs;
        }
    }

    @ProxyMethod("getFactoryMacAddresses")
    public static class GetFactoryMacAddresses extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!DeviceSpoofManager.isCurrentProcessEnabled()) return method.invoke(who, args);
            return new String[]{DeviceSpoofManager.getMacForCurrentUser()};
        }
    }
}
