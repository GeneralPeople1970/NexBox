package com.nexbox.core.fake.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.app.ContextImpl;
import black.android.content.pm.BRPackageManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.env.AppSystemEnv;
import com.nexbox.core.core.env.SamsungHealthCompat;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.fake.FakeCore;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.hook.ProxyMethods;
import com.nexbox.core.fake.service.base.PkgMethodProxy;
import com.nexbox.core.fake.service.base.ValueMethodProxy;
import com.nexbox.core.media.NexBoxMediaContract;
import com.nexbox.core.utils.MethodParameterUtils;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.TextInputDiagnostics;
import com.nexbox.core.utils.TextInputDiagnostics.Component;
import com.nexbox.core.utils.compat.BuildCompat;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;


public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";
    private static final String CLONE_INSTALLER_PACKAGE = "com.android.vending";

    private static Object invokeOriginal(Object who, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(who, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }

    private static Object querySystemTextUi(Object who, Method method, Object[] args) throws Throwable {
        String packageName = args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : null;
        String route = SystemTextMenuPolicy.EDITOR_PACKAGE.equals(packageName) ? "ROM_EDITOR" : "ROM_CLIPBOARD_UI";
        int userIndex = SystemTextMenuPolicy.androidUserIndex(method);
        if (args == null || args.length != method.getParameterTypes().length
                || userIndex < 0 || NexBoxCore.getHostUserId() < 0
                || !SystemTextMenuPolicy.isTextUiPackage(packageName)) {
            TextInputDiagnostics.state(Component.NATIVE_MENU, method.getName(),
                    "route=" + route + " available=false reason=unsupportedSignature");
            return null;
        }
        return TextInputDiagnostics.call(Component.NATIVE_MENU, method.getName(), "route=" + route, () -> {
            Object[] request = args.clone();
            request[userIndex] = NexBoxCore.getHostUserId();
            Object result;
            long identity = Binder.clearCallingIdentity();
            try {
                result = invokeOriginal(who, method, request);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            ApplicationInfo info = result instanceof ApplicationInfo ? (ApplicationInfo) result
                    : result instanceof PackageInfo ? ((PackageInfo) result).applicationInfo : null;
            boolean allowed = info != null && packageName.equals(info.packageName)
                    && SystemTextMenuPolicy.isSystemTextUi(info.packageName, info.flags);
            TextInputDiagnostics.state(Component.NATIVE_MENU, method.getName(),
                    "route=" + route + " available=" + allowed + " reason="
                            + (info == null ? "packageMissing" : allowed ? "systemPackage" : "notSystemPackage"));
            // MIUI loads FloatingActionModeHelper from com.miuix.editor, and creates a
            // com.miui.phrase context for its clipboard dialog. Preserve their real
            // code/resources; component routing and system permission checks stay intact.
            return allowed ? result : null;
        });
    }

    @ProxyMethod("getPackageInstaller")
    public static class GetPackageInstaller extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GuestPackageInstallerProxy.create(method.getReturnType());
        }
    }

    private static final String PKG_SAMSUNG_HEALTH = "com.sec.android.app.shealth";
    private static final String PKG_SAMSUNG_ACCOUNT = "com.osp.app.signin";

    private static boolean shouldHideHostSamsungAccountForSamsungHealth(String queriedPackageName) {
        if (!PKG_SAMSUNG_ACCOUNT.equals(queriedPackageName)) {
            return false;
        }
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                return false;
            }
            int userId = BActivityThread.getUserId();
            return !SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean shouldHideHostSamsungAccountForSamsungHealth(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String targetPackage = intent.getPackage();
            if (targetPackage == null) {
                ComponentName cn = intent.getComponent();
                if (cn != null) {
                    targetPackage = cn.getPackageName();
                }
            }
            return shouldHideHostSamsungAccountForSamsungHealth(targetPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean shouldHideHostSamsungAccountForSamsungHealthCaller() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                return false;
            }
            int userId = BActivityThread.getUserId();
            return !SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<ResolveInfo> filterOutSamsungAccountActivities(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && PKG_SAMSUNG_ACCOUNT.equals(ai.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(NexBoxCore.mainThread()).getSystemContext();
        BRContextImpl.get(systemContext).getPackageManager();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            BRPackageManager.get().disableApplicationInfoCache();
            try {
                Reflector.on("android.app.ApplicationPackageManager")
                        .field("mPM")
                        .set(packageManager, proxyInvocation);
            } catch (Exception e) {
                Slog.e("IPackageManagerProxy", "Container operation failed", e);
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new ShouldShowRequestPermissionRationale());
        addMethodHook(new RequestPermissions());
        addMethodHook(new DisableIconLoading());
        addMethodHook(new SetSplashScreenTheme());
        addMethodHook(new XiaomiSecurityBypass());
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];

            // Privacy-first Samsung Health behavior: when host Samsung Account fallback is OFF,
            // ensure SaSDK cannot resolve intents into the host Samsung Account app.
            if (shouldHideHostSamsungAccountForSamsungHealth(intent)) {
                return null;
            }

            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ResolveInfo resolveInfo = packages.resolveIntent(intent, resolvedType, flags, NexBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            ResolveInfo host = (ResolveInfo) invokeOriginal(who, method, args);
            return com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intent, host == null ? null : host.activityInfo)
                    ? null : host;
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ResolveInfo resolveInfo = packages.resolveService(intent, flags, resolvedType, NexBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            ResolveInfo host = (ResolveInfo) invokeOriginal(who, method, args);
            return com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intent, host == null ? null : host.serviceInfo)
                    ? null : host;
        }
    }

    @ProxyMethod("getInstallSourceInfo")
    public static class GetInstallSourceInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = args != null && args.length > 0 ? (String) args[0] : null;
            if (packageName == null) {
                return method.invoke(who, args);
            }

            int userId = NexBoxCore.getUserId();
            if (NexBoxCore.get().isInstalled(packageName, userId)) {
                Object info = createCloneInstallSourceInfo();
                if (info != null) {
                    return info;
                }
                // Last resort: behave like "not installed" rather than crashing the caller with NPE.
                throw new PackageManager.NameNotFoundException(packageName);
            }

            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }

            throw new PackageManager.NameNotFoundException(packageName);
        }

        private Object createCloneInstallSourceInfo() {
            // Local imports and host clones share one virtual installer. Do not copy the
            // host installer, and do not invent an initiating signature or update owner.
            try {
                Class<?> cls = Class.forName("android.content.pm.InstallSourceInfo");
                Class<?> signingInfo = Class.forName("android.content.pm.SigningInfo");
                Class<?>[][] signatures = {
                        {String.class, signingInfo, String.class, String.class, String.class, int.class},
                        {String.class, signingInfo, String.class, String.class, int.class},
                        {String.class, signingInfo, String.class, String.class}
                };
                Object[][] arguments = {
                        {CLONE_INSTALLER_PACKAGE, null, null, CLONE_INSTALLER_PACKAGE, null,
                                android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE},
                        {CLONE_INSTALLER_PACKAGE, null, null, CLONE_INSTALLER_PACKAGE,
                                android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE},
                        {CLONE_INSTALLER_PACKAGE, null, null, CLONE_INSTALLER_PACKAGE}
                };
                // Prefer the newest shape so Android 13+ also reports PACKAGE_SOURCE_STORE.
                for (int index = 0; index < signatures.length; index++) {
                    try {
                        Constructor<?> ctor = cls.getDeclaredConstructor(signatures[index]);
                        ctor.setAccessible(true);
                        return ctor.newInstance(arguments[index]);
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable error) {
                Slog.w(TAG, "action=getInstallSourceInfo success=false", error);
            }
            Slog.w(TAG, "action=getInstallSourceInfo reason=unsupported_framework_constructor");
            return null;
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName != null ? componentName.getPackageName() : null;
            if (packageName != null && NexBoxCore.get().isInstalled(packageName, NexBoxCore.getUserId())) {
                return 0;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, 3);
                return method.invoke(who, args);
            }
            return 0;
        }
    }

    @ProxyMethod("setApplicationEnabledSetting")
    public static class SetApplicationEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            if (packageName != null && NexBoxCore.get().isInstalled(packageName, NexBoxCore.getUserId())) {
                return null;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, 3);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getApplicationEnabledSetting")
    public static class GetApplicationEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            if (packageName != null && NexBoxCore.get().isInstalled(packageName, NexBoxCore.getUserId())) {
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class GetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName != null ? componentName.getPackageName() : null;
            if (packageName != null && NexBoxCore.get().isInstalled(packageName, NexBoxCore.getUserId())) {
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (packageName != null && AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);

            // Samsung Health SaSDK should not detect the host Samsung Account package unless the
            // explicit per-app fallback toggle is enabled.
            if (shouldHideHostSamsungAccountForSamsungHealth(packageName)) {
                return null;
            }
            
            
            // An installed guest (including Play Store querying itself) must see its own
            // version, UID, signatures and declared permissions, not the legacy fake entry.
            // An unavailable virtual service is not proof that the package is absent.
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            PackageInfo packageInfo = packages.getPackageInfo(packageName, flags, NexBoxCore.getUserId());
            if (packageInfo != null) {
                // Preserve the core's declared-manifest / real-host-grant intersection.
                return packageInfo;
            }

            if (com.nexbox.core.core.GmsCompat.isCorePackage(packageName)) return null;
            if (SystemTextMenuPolicy.isTextUiPackage(packageName)) {
                return querySystemTextUi(who, method, args);
            }

            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }

    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            if (shouldHideHostSamsungAccountForSamsungHealth(packageName)) return -1;
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0, BActivityThread.getUserId());
            if (info != null) return info.uid;
            if (com.nexbox.core.core.GmsCompat.isCorePackage(packageName)
                    || !AppSystemEnv.isOpenPackage(packageName)) return -1;
            Object[] request = args.clone();
            MethodParameterUtils.replaceUserIdIfNeeded(request, request.length - 1);
            try {
                return method.invoke(who, request);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ProviderInfo providerInfo = NexBoxCore.getBPackageManager().getProviderInfo(componentName, flags, NexBoxCore.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo receiverInfo = NexBoxCore.getBPackageManager().getReceiverInfo(componentName, flags, NexBoxCore.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo activityInfo = NexBoxCore.getBPackageManager().getActivityInfo(componentName, flags, NexBoxCore.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo serviceInfo = NexBoxCore.getBPackageManager().getServiceInfo(componentName, flags, NexBoxCore.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<ApplicationInfo> installedApplications = NexBoxCore.getBPackageManager().getInstalledApplications(flags, NexBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<PackageInfo> installedPackages = NexBoxCore.getBPackageManager().getInstalledPackages(flags, NexBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];

            int flags = MethodParameterUtils.toInt(args[1]);

            if (shouldHideHostSamsungAccountForSamsungHealth(packageName)) {
                return null;
            }



            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ApplicationInfo applicationInfo = packages.getApplicationInfo(packageName, flags, NexBoxCore.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (com.nexbox.core.core.GmsCompat.isCorePackage(packageName)) return null;
            if (SystemTextMenuPolicy.isTextUiPackage(packageName)) {
                return querySystemTextUi(who, method, args);
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[2]);
            List<ProviderInfo> providers = NexBoxCore.getBPackageManager().
                    queryContentProviders(NexBoxCore.getAppProcessName(), NexBoxCore.getBUid(), flags, NexBoxCore.getUserId());
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentActivities")
    public static class QueryIntentActivities extends MethodHook {
        @Override
        @SuppressWarnings("unchecked")
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);

            List<ResolveInfo> resolves = NexBoxCore.getBPackageManager()
                    .queryIntentActivities(intent, flags, type, BActivityThread.getUserId());

            if (resolves != null && !resolves.isEmpty()) {
                if (shouldHideHostSamsungAccountForSamsungHealthCaller()) {
                    resolves = filterOutSamsungAccountActivities(resolves);
                }
                return ParceledListSliceCompat.create(resolves);
            }

            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);

            if (!shouldHideHostSamsungAccountForSamsungHealthCaller()) {
                return hostOut;
            }

            List<ResolveInfo> hostList = null;
            if (hostOut instanceof List) {
                hostList = (List<ResolveInfo>) hostOut;
            } else if (ParceledListSliceCompat.isParceledListSlice(hostOut)) {
                try {
                    hostList = Reflector.with(hostOut).method("getList").call();
                } catch (Throwable ignored) {
                }
            }

            hostList = filterOutSamsungAccountActivities(hostList);
            if (hostList == null) {
                return hostOut;
            }
            return ParceledListSliceCompat.create(hostList);
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> resolves = NexBoxCore.getBPackageManager().queryBroadcastReceivers(intent, flags, type, BActivityThread.getUserId());
            Slog.dLazy(TAG, () -> "queryIntentReceivers count=" + (resolves == null ? 0 : resolves.size()));

            
            return ParceledListSliceCompat.create(resolves);
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            if (NexBoxMediaContract.PUBLIC_AUTHORITY.equals(authority)) {
                ProviderInfo providerInfo = NexBoxCore.getPackageManager()
                        .resolveContentProvider(NexBoxCore.getHostPkg() + ".nexbox.MediaProvider", flags);
                if (providerInfo != null) {
                    providerInfo = new ProviderInfo(providerInfo);
                    providerInfo.authority = NexBoxMediaContract.PUBLIC_AUTHORITY;
                    return providerInfo;
                }
            }
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ProviderInfo providerInfo = packages.resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                providerInfo = NexBoxCore.getPackageManager().resolveContentProvider(authority, flags);
                if (providerInfo != null && com.nexbox.core.core.GmsCompat.isCorePackage(providerInfo.packageName)) return null;
                if (providerInfo != null && AppSystemEnv.isOpenPackage(providerInfo.packageName)) {
                    return providerInfo;
                }
                return method.invoke(who, args);
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = (Integer) args[0];
            if (uid == NexBoxCore.getHostUid()) {
                args[0] = BActivityThread.getBUid();
                uid = (int) args[0];
            }
            String[] packagesForUid = NexBoxCore.getBPackageManager().getPackagesForUid(uid);
            Slog.dLazy(TAG, () -> args[0] + " , " + BActivityThread.getAppProcessName() + " GetPackagesForUid: " + Arrays.toString(packagesForUid));
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return CLONE_INSTALLER_PACKAGE;
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class GetComponentEnabledSettingV2 extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName.getPackageName();
            ApplicationInfo applicationInfo = NexBoxCore.getBPackageManager().getApplicationInfo(packageName,0, BActivityThread.getUserId());





            if(applicationInfo != null){
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            throw new IllegalArgumentException();
        }
    }





    @ProxyMethods({"checkPermission", "checkUidPermission"})
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GuestPermissionChecks.invoke(PermissionCheckPolicy.Backend.PACKAGE, who, method, args);
        }
    }

    @ProxyMethod("shouldShowRequestPermissionRationale")
    public static class ShouldShowRequestPermissionRationale extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];

            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);
                return false;
            }

            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for storage/media permission: " + permission);
                return false;
            }

            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for notification/Xiaomi permission: " + permission);
                return false;
            }

            return method.invoke(who, args);
        }
    }

    @ProxyMethod("requestPermissions")
    public static class RequestPermissions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] permissions = (String[]) args[0];
            String packageName = (String) args[1];
            
            
            
            if (permissions != null) {
                Slog.d(TAG, "RequestPermissions: Allowing permission request flow for: " + java.util.Arrays.toString(permissions));
            }
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDrawable")
    public static class DisableIconLoading extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "Blocking icon loading to prevent resource errors");
            return null; 
        }
    }

    
    private static boolean isStorageOrMediaPermission(String permission) {
        if (permission == null) return false;
        
        if (permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        }
        
        if (permission.equals(android.Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.READ_MEDIA_AURAL")
                || permission.equals(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return true;
        }
        
        if (permission.equals("android.permission.READ_MEDIA_AUDIO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VIDEO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_IMAGES_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_AURAL_USER_SELECTED")) {
            return true;
        }
        return false;
    }

    
    private static boolean isAudioPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals("android.permission.FOREGROUND_SERVICE_LOCATION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_HEALTH")
                || permission.equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                || permission.equals("android.permission.FOREGROUND_SERVICE_PHONE_CALL")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
    }
    
    
    private static boolean isNotificationOrXiaomiPermission(String permission) {
        if (permission == null) return false;
        
        
        if (permission.equals("android.permission.POST_NOTIFICATIONS")) {
            return true;
        }
        
        
        if (permission.equals("miui.permission.USE_INTERNAL_GENERAL_API") ||
            permission.equals("miui.permission.OPTIMIZE_POWER") ||
            permission.equals("miui.permission.RUN_IN_BACKGROUND") ||
            permission.equals("miui.permission.POST_NOTIFICATIONS") ||
            permission.equals("miui.permission.AUTO_START") ||
            permission.equals("miui.permission.BACKGROUND_POPUP_WINDOW") ||
            permission.equals("miui.permission.SHOW_WHEN_LOCKED") ||
            permission.equals("miui.permission.TURN_SCREEN_ON")) {
            return true;
        }
        
        return false;
    }

    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            
            String packageName = args.length > 0 ? (String) args[0] : "unknown";
            Slog.d(TAG, "SetSplashScreenTheme: Bypassing UID check for package: " + packageName);
            
            
            boolean isXiaomi = BuildCompat.isMIUI() || 
                              Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                              Build.BRAND.toLowerCase().contains("xiaomi") ||
                              Build.DISPLAY.toLowerCase().contains("hyperos");
                              
            if (isXiaomi) {
                Slog.d(TAG, "SetSplashScreenTheme: Detected Xiaomi/HyperOS, using enhanced bypass");
                
                return null;
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetSplashScreenTheme: SecurityException caught, bypassing: " + e.getMessage(), e);
                return null;
            } catch (Exception e) {
                
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "SetSplashScreenTheme: SecurityException (wrapped) caught, bypassing: " + e.getCause().getMessage());
                    return null;
                }
                throw e; 
            }
        }
    }

    
    public static class XiaomiSecurityBypass extends MethodHook {
        private static final String[] XIAOMI_SECURITY_METHODS = {
            "setApplicationEnabledSetting",
            "setComponentEnabledSetting", 
            "setInstallLocation",
            "setInstallerPackageName",
            "setPackageStoppedState",
            "setSystemAppState",
            "setApplicationCategoryHint",
            "setApplicationHiddenSettingAsUser",
            "setBlockUninstallForUser",
            "setDefaultBrowserPackageNameAsUser",
            "setDistractingPackageRestrictionsAsUser",
            "setPackagesSuspendedAsUser",
            "setUpdateAvailable",
            "setRequiredForSystemUser",
            "setSystemAppHiddenUntilInstalled",
            "setHarmfulAppWarningEnabled",
            "setKeepUninstalledPackages",
            "verifyIntentFilter",
            "verifyPendingInstall",
            "extendVerificationTimeout",
            "setDefaultHomeActivity",
            "resetApplicationPreferences",
            "clearApplicationProfileData",
            "clearApplicationUserData",
            "deleteApplicationCacheFiles",
            "deleteApplicationCacheFilesAsUser",
            "freeStorageAndNotify",
            "freeStorage",
            "movePackage",
            "movePackageToSd",
            "movePrimaryStorage"
        };

        @Override
        public boolean isEnable() {
            
            return BuildCompat.isMIUI() || 
                   Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                   Build.BRAND.toLowerCase().contains("xiaomi") ||
                   Build.DISPLAY.toLowerCase().contains("hyperos");
        }

        @Override
        public String getMethodName() {
            return null; 
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            
            for (String securityMethod : XIAOMI_SECURITY_METHODS) {
                if (securityMethod.equals(methodName)) {
                    Slog.d(TAG, "XiaomiSecurityBypass: Intercepting " + methodName + " on Xiaomi/HyperOS");
                    
                    
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    
                    else if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    
                    else if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    
                    else {
                        return null;
                    }
                }
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "XiaomiSecurityBypass: SecurityException in " + methodName + ", bypassing: " + e.getMessage(), e);
                
                if (method.getReturnType() == boolean.class) {
                    return false;
                } else if (method.getReturnType() == int.class) {
                    return -1;
                } else {
                    return null;
                }
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "XiaomiSecurityBypass: SecurityException (wrapped) in " + methodName + ", bypassing: " + e.getCause().getMessage());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    } else if (method.getReturnType() == int.class) {
                        return -1;
                    } else {
                        return null;
                    }
                }
                throw e;
            }
        }
    }
}
