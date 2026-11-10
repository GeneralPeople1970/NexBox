package com.nexbox.core.fake.service;

import java.lang.reflect.Method;
import java.util.List;

import black.com.android.internal.net.BRVpnConfig;
import black.com.android.internal.net.VpnConfigContext;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.proxy.ProxyVpnService;
import com.nexbox.core.utils.MethodParameterUtils;


public class VpnCommonProxy {
    @ProxyMethod("setVpnPackageAuthorization")
    public static class setVpnPackageAuthorization extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("prepareVpn")
    public static class PrepareVpn extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("establishVpn")
    public static class establishVpn extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            VpnConfigContext vpnConfigContext = BRVpnConfig.get(args[0]);
            vpnConfigContext._set_user(ProxyVpnService.class.getName());

            handlePackage(vpnConfigContext.allowedApplications());
            handlePackage(vpnConfigContext.disallowedApplications());
            return method.invoke(who, args);
        }

        private void handlePackage(List<String> applications) {
            if (applications == null)
                return;
            if (applications.contains(BActivityThread.getAppPackageName())) {
                applications.add(NexBoxCore.getHostPkg());
            }
        }
    }

}
