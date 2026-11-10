package com.nexbox.core.fake.service;

import android.app.ActivityManager;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IInterface;
import android.text.TextUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Objects;

import black.android.app.BRActivityManagerOreo;
import black.android.content.BRContentProviderHolderOreo;
import black.android.content.BRContentProviderNative;
import black.android.content.pm.BRUserInfo;
import black.android.util.BRSingleton;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.env.AppSystemEnv;
import com.nexbox.core.core.env.SamsungHealthCompat;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.am.RunningAppProcessInfo;
import com.nexbox.core.entity.am.RunningServiceInfo;
import com.nexbox.core.fake.delegate.ContentProviderDelegate;
import com.nexbox.core.fake.delegate.ProviderRoutePolicy;
import com.nexbox.core.fake.delegate.InnerReceiverDelegate;
import com.nexbox.core.fake.delegate.ServiceConnectionDelegate;
import com.nexbox.core.fake.frameworks.BActivityManager;
import com.nexbox.core.fake.frameworks.BPackageManager;
import com.nexbox.core.fake.hook.ClassInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.hook.ProxyMethods;
import com.nexbox.core.fake.hook.ScanClass;
import com.nexbox.core.fake.service.base.PkgMethodProxy;
import com.nexbox.core.media.NexBoxMediaContract;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.proxy.record.ProxyPendingRecord;
import com.nexbox.core.utils.MethodParameterUtils;
import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.compat.ActivityManagerCompat;
import com.nexbox.core.utils.compat.BuildCompat;
import com.nexbox.core.utils.compat.BundleCompat;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;
import com.nexbox.core.utils.compat.TaskDescriptionCompat;
import com.nexbox.core.utils.Slog;

import static android.content.Context.RECEIVER_EXPORTED;
import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;


@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityManagerProxy extends ClassInvocationStub {
    public static final String TAG = "ActivityManagerStub";

    @Override
    protected Object getWho() {
        Object iActivityManager = null;
        iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        return BRSingleton.get(iActivityManager).get();
    }

    @Override
    protected void inject(Object base, Object proxy) {
        Object iActivityManager = null;
        iActivityManager = BRActivityManagerOreo.get().IActivityManagerSingleton();
        BRSingleton.get(iActivityManager)._set_mInstance(proxy);
    }

    @Override
    public boolean isBadEnv() {
        return getProxyInvocation() != getWho();
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new PkgMethodProxy("getAppStartMode"));
        addMethodHook(new PkgMethodProxy("setAppLockedVerifying"));
        addMethodHook(new PkgMethodProxy("reportJunkFromApp"));
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Provider, connection and permission operations preserve failures and execute once.
        // A default result or replay can hide a denial or register a second service binding.
        String name = method.getName();
        if (NexBoxCore.get().isGuestProcess() && "getInfoForIntentSender".equals(name)) {
            Bundle request = new Bundle(); request.putBinder("target", ((IInterface) args[0]).asBinder());
            Bundle info = NexBoxCore.getBActivityManager().getService().pendingIntentOperation("info", request);
            return Class.forName("android.app.ActivityManager$PendingIntentInfo")
                    .getConstructor(String.class, int.class, boolean.class, int.class)
                    .newInstance(info.getString("package"), info.getInt("uid"), info.getBoolean("immutable"), info.getInt("type"));
        }
        if (NexBoxCore.get().isGuestProcess() && IUriGrantsManagerProxy.handles(name)) return IUriGrantsManagerProxy.handle(name, args);
        // Binder failure may follow a completed side effect. Never retry the intercepted
        // operation or turn a permission denial into a guessed, possibly wrong-typed result.
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getUidProcessState")
    public static class GetUidProcessState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object[] mapped = args == null ? null : args.clone();
            if (mapped != null && mapped.length == 2 && mapped[0] instanceof Integer
                    && mapped[1] instanceof String) {
                if ((Integer) mapped[0] == BActivityThread.getBUid()) mapped[0] = NexBoxCore.getHostUid();
                if (BActivityThread.getAppPackageName().equals(mapped[1])) mapped[1] = NexBoxCore.getHostPkg();
            }
            try {
                return method.invoke(who, mapped);
            } catch (java.lang.reflect.InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    @ProxyMethod("getContentProvider")
    public static class GetContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int authIndex = getAuthIndex(method);
            if (authIndex < 0) {
                return invokeProvider(who, method, args, "", "UNCLASSIFIED");
            }
            int userIndex = authIndex + 1;
            if (args == null || args.length != method.getParameterTypes().length
                    || !(args[authIndex] instanceof String)) {
                throw new IllegalArgumentException("Invalid content provider request");
            }
            String authority = (String) args[authIndex];
            String callerPackage = BActivityThread.getAppPackageName();
            if (TextUtils.isEmpty(callerPackage)) callerPackage = authIndex == 2 && args[1] instanceof String
                    ? (String) args[1] : NexBoxCore.getHostPkg();
            int userId = BActivityThread.getUserId();
            Object[] hostRequest = args.clone();
            if (authIndex == 2) hostRequest[1] = NexBoxCore.getHostPkg();
            hostRequest[userIndex] = NexBoxCore.getHostUserId();

            if (ProviderRoutePolicy.isContainerAuthority(authority, NexBoxCore.getHostPkg(), ProxyManifest.FREE_COUNT)) {
                return acquireSystem(who, method, hostRequest, authority, callerPackage, userId);
            }
            if (NexBoxMediaContract.PUBLIC_AUTHORITY.equals(authority)) {
                hostRequest[authIndex] = ProxyManifest.getMediaProviderAuthority();
                Object content = acquireSystem(who, method, hostRequest, authority, callerPackage, userId);
                if (content != null) {
                    ProviderInfo info = BRContentProviderHolderOreo.get(content).info();
                    if (info != null) {
                        ProviderInfo publicInfo = new ProviderInfo(info);
                        publicInfo.authority = NexBoxMediaContract.PUBLIC_AUTHORITY;
                        Reflector.with(content).field("info").set(publicInfo);
                    }
                }
                return content;
            }
            if (ProviderRoutePolicy.isReservedSystemAuthority(authority)) {
                return acquireSystem(who, method, hostRequest, authority, callerPackage, userId);
            }

            // Read the checked virtual service first, including Google/GMS/GSF authorities.
            // Binder failure is not proof of absence and must not trigger a host fallback.
            IBPackageManagerService packages = BPackageManager.get().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ProviderInfo guest = packages.resolveContentProvider(authority, GET_META_DATA, userId);
            if (guest != null) {
                logRoute(authority, "GUEST", callerPackage, userId, guest.packageName);
                return acquireGuest(who, method, hostRequest, authority, callerPackage, userId, guest);
            }

            ProviderInfo host = NexBoxCore.getPackageManager().resolveContentProvider(authority, GET_META_DATA);
            if (host != null && com.nexbox.core.core.GmsCompat.isCorePackage(host.packageName)) {
                logRoute(authority, "UNAVAILABLE", callerPackage, userId, host.packageName);
                return null;
            }
            boolean open = host != null && AppSystemEnv.isOpenPackage(host.packageName);
            boolean allowed = !open || !ProviderRoutePolicy.requiresSamsungAccountOptIn(callerPackage, authority, host.packageName)
                    || SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
            if (ProviderRoutePolicy.choose(authority, false, open, allowed) != ProviderRoutePolicy.Route.SYSTEM) {
                logRoute(authority, "UNAVAILABLE", callerPackage, userId, host == null ? "" : host.packageName);
                return null;
            }
            return acquireSystem(who, method, hostRequest, authority, callerPackage, userId);
        }

        private Object acquireSystem(Object who, Method method, Object[] args, String authority,
                String callerPackage, int userId) throws Throwable {
            logRoute(authority, "SYSTEM", callerPackage, userId, "");
            Object holder = invokeProvider(who, method, args, authority, "SYSTEM");
            ContentProviderDelegate.updateSystem(holder, authority);
            return holder;
        }

        private Object acquireGuest(Object who, Method method, Object[] hostRequest, String authority,
                String callerPackage, int userId, ProviderInfo providerInfo) throws Throwable {
            if (BActivityThread.getAppPid() < 0 || TextUtils.isEmpty(providerInfo.packageName)) return null;
            IBActivityManagerService activities = BActivityManager.get().getService();
            if (activities == null) throw new IllegalStateException("Virtual activity service unavailable");
            AppConfig config = activities.initProcess(providerInfo.packageName, providerInfo.processName, userId);
            if (config == null || config.userId != userId || !providerInfo.packageName.equals(config.packageName)
                    || config.bpid < 0 || config.bpid >= ProxyManifest.FREE_COUNT) return null;
            if (config.bpid == BActivityThread.getAppPid()) {
                Object local = ContentProviderDelegate.findLocalGuestHolder(authority, providerInfo);
                ContentProviderDelegate.updateGuest(local, authority, callerPackage);
                return local;
            }
            IBinder binder = activities.acquireContentProviderClient(providerInfo);
            if (binder == null) return null;
            IInterface guest = BRContentProviderNative.get().asInterface(binder);
            if (guest == null) return null;
            hostRequest[getAuthIndex(method)] = ProxyManifest.getProxyAuthorities(config.bpid);
            Object holder = invokeProvider(who, method, hostRequest, authority, "GUEST_TRANSPORT");
            if (holder == null) return null;
            Reflector.with(holder).field("info").set(new ProviderInfo(providerInfo));
            BRContentProviderHolderOreo.get(holder)._set_provider(guest);
            ContentProviderDelegate.updateGuest(holder, authority, callerPackage);
            return holder;
        }

        private static Object invokeProvider(Object who, Method method, Object[] args,
                String authority, String route) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                try {
                    Slog.w(TAG, "action=acquireProvider authority=" + authority + " route=" + route
                            + " signature=" + method.toGenericString() + " error=" + cause.getClass().getSimpleName());
                } catch (Throwable ignored) {
                    // Preserve the provider's failure even if the diagnostic sink is unavailable.
                }
                throw cause;
            }
        }

        private static void logRoute(String authority, String route, String callerPackage, int userId,
                String providerPackage) {
            try {
                if (!Slog.isLoggable(TAG, android.util.Log.DEBUG)) return;
                Slog.d(TAG, "action=acquireProvider authority=" + authority + " route=" + route
                        + " caller=" + callerPackage + " virtualUser=" + userId
                        + " provider=" + providerPackage + " transportCaller=" + NexBoxCore.getHostPkg()
                        + " transportUid=" + NexBoxCore.getHostUid());
            } catch (Throwable ignored) {
                // Logging is best-effort, not part of acquiring a provider.
            }
        }

        protected int getAuthIndex(Method method) {
            Class<?>[] types = method.getParameterTypes();
            // Android 8-9 omit callingPackage; newer platforms include it before authority.
            int authority = types.length == 4 ? 1 : types.length == 5 ? 2 : -1;
            if (authority < 0 || !"android.app.IApplicationThread".equals(types[0].getName())
                    || types[authority] != String.class || types[authority + 1] != int.class
                    || types[authority + 2] != boolean.class || (authority == 2 && types[1] != String.class)) return -1;
            return authority;
        }
    }

    @ProxyMethod("isBackgroundRestricted")
    public static class IsBackgroundRestricted extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (args[i] instanceof String) {
                            String pkg = (String) args[i];
                            String hostPkg = NexBoxCore.getHostPkg();
                            String vPkg = BActivityThread.getAppPackageName();
                            if (!TextUtils.isEmpty(pkg)
                                    && !TextUtils.isEmpty(hostPkg)
                                    && Objects.equals(pkg, vPkg)
                                    && !Objects.equals(pkg, hostPkg)) {
                                args[i] = hostPkg;
                            }
                            break;
                        }
                    }
                }
                return method.invoke(who, args);
            } catch (Throwable e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof IllegalArgumentException) {
                    String msg = cause.getMessage();
                    if (msg != null && msg.contains("cannot query restriction state")) {
                        Slog.w(TAG, "UID/package mismatch in isBackgroundRestricted, returning false: " + msg);
                        return false;
                    }
                }
                throw e;
            }
        }
    }

    @ProxyMethod("startService")
    public static class StartService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 4 || !(args[1] instanceof Intent)
                    || !(args[3] instanceof Boolean)) {
                throw new IllegalArgumentException("Unsupported startService arguments");
            }
            Intent intent = (Intent) args[1];
            String resolvedType = (String) args[2];
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
            ResolveInfo resolveInfo = packages.resolveService(intent, 0, resolvedType, BActivityThread.getUserId());
            if (resolveInfo == null) {
                ResolveInfo host = NexBoxCore.getPackageManager().resolveService(intent, GET_META_DATA);
                if (com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intent, host == null ? null : host.serviceInfo)) return null;
                String targetPackage = intent.getComponent() == null ? intent.getPackage()
                        : intent.getComponent().getPackageName();
                if (targetPackage != null && (com.nexbox.core.core.GmsCompat.isCorePackage(targetPackage)
                        || packages.isInstalled(targetPackage, BActivityThread.getUserId()))) return null;
                Object[] request = args.clone();
                MethodParameterUtils.replaceFirstAppPkg(request);
                MethodParameterUtils.replaceUserIdIfNeeded(request, request.length - 1);
                return invokeServiceMethod(who, method, request);
            }

            int requireForegroundIndex = getRequireForeground();
            boolean requireForeground = false;
            if (requireForegroundIndex != -1) {
                requireForeground = (boolean) args[requireForegroundIndex];
            }
            return NexBoxCore.getBActivityManager().startService(intent, resolvedType, requireForeground, BActivityThread.getUserId());
        }

        public int getRequireForeground() {
            return 3;
        }
    }

    @ProxyMethod("stopService")
    public static class StopService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Intent intent = (Intent) args[1];
                String resolvedType = (String) args[2];
                
                                        
                        if (intent != null && intent.getComponent() != null) {
                            String servicePackage = intent.getComponent().getPackageName();
                            String currentPackage = BActivityThread.getAppPackageName();

                            
                            if (!servicePackage.equals(currentPackage)) {
                                Slog.w(TAG, "StopService: Attempting to stop service from different package: " +
                                        servicePackage + " (current: " + currentPackage + "), returning false");
                                return 0;
                            }
                        }
                
                return NexBoxCore.getBActivityManager().stopService(intent, resolvedType, BActivityThread.getUserId());
            } catch (SecurityException e) {
                throw e;
            } catch (Exception e) {
                Slog.e(TAG, "StopService: Error stopping service", e);
                throw e;
            }
        }
    }

    @ProxyMethod("stopServiceToken")
    public static class StopServiceToken extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length != 3 || !(args[0] instanceof ComponentName)
                    || !(args[1] instanceof IBinder) || !(args[2] instanceof Integer)) return false;
            ComponentName componentName = (ComponentName) args[0];
            if (!componentName.getPackageName().equals(BActivityThread.getAppPackageName())) return false;
            IBActivityManagerService activities = BActivityManager.get().getService();
            if (activities == null) throw new IllegalStateException("Virtual activity service unavailable");
            return activities.stopServiceTokenIfCurrent(componentName, (IBinder) args[1],
                    (Integer) args[2], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("setActivityLocusContext")
    public static class SetActivityLocusContext extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                                        
                        if (args != null && args.length >= 2) {
                            String targetPackage = (String) args[1];
                            String currentPackage = BActivityThread.getAppPackageName();

                            
                            if (targetPackage != null && !targetPackage.equals(currentPackage)) {
                                Slog.w(TAG, "SetActivityLocusContext: Attempting to set locus context for different package: " +
                                        targetPackage + " (current: " + currentPackage + "), returning success");
                                return null; 
                            }
                        }
                
                
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetActivityLocusContext: SecurityException caught, returning success", e);
                return null; 
            } catch (Exception e) {
                Slog.e(TAG, "SetActivityLocusContext: Error setting locus context", e);
                return null; 
            }
        }
    }

    public static Object BindServiceCommon(Object who, Method method, Object[] args) throws Throwable {
        ServiceBindingPolicy.Layout layout = ServiceBindingPolicy.layout(method.getName(), method.getParameterTypes());
        int userId = BActivityThread.getUserId();
        if (layout == null || args == null || args.length != method.getParameterTypes().length) {
            // An OEM signature must not be rewritten using guessed argument positions.
            ServiceBindingDiagnostics.report(method.toGenericString(), "signature", "UNCLASSIFIED", "",
                    "", "", userId, 0, 0, null, null, null, null);
            return invokeServiceMethod(who, method, args);
        }

        Object[] request = args.clone();
        String callerBefore = (String) request[layout.callingPackage];
        String callerAfter = NexBoxCore.getHostPkg();
        request[layout.callingPackage] = callerAfter;
        request[layout.userId] = NexBoxCore.getHostUserId();
        long flagsBefore = ServiceBindingPolicy.readFlags(request[5]);
        long flagsAfter = flagsBefore;
        String instanceBefore = layout.instanceName < 0 ? null : (String) request[layout.instanceName];
        String instanceAfter = instanceBefore;
        String route = "SYSTEM";
        String stage = "resolve";
        String component = "";
        OaidServiceBinder.RequestInfo oaid = null;
        ServiceConnectionDelegate.BindingLease binding = null;
        String connectionState = "unchanged";
        try {
            Intent suppliedIntent = (Intent) request[2];
            Intent intent = suppliedIntent == null ? null : new Intent(suppliedIntent);
            request[2] = intent;
            String resolvedType = (String) request[3];
            IServiceConnection connection = (IServiceConnection) request[4];
            if (intent != null) {
                ComponentName target = intent.getComponent();
                component = target == null ? "" : target.flattenToShortString();
                String accountUserKey = NexBoxIpcKeys.readKey(intent.hasExtra(NexBoxIpcKeys.ACCOUNT_USER_ID),
                        NexBoxIpcKeys.ACCOUNT_USER_ID, NexBoxIpcKeys.LEGACY_ACCOUNT_USER_ID);
                int requestedUser = intent.getIntExtra(accountUserKey, -1);
                if (requestedUser != -1) {
                    if (!NexBoxCore.get().isServerProcess() && requestedUser != userId) {
                        throw new SecurityException("A guest cannot bind a service in another profile");
                    }
                    userId = requestedUser;
                }
                NexBoxIpcKeys.removeExtras(intent, NexBoxIpcKeys.ACCOUNT_USER_ID, NexBoxIpcKeys.LEGACY_ACCOUNT_USER_ID);
                // A failed virtual lookup is not evidence that the service lives on the host.
                IBPackageManagerService packages = BPackageManager.get().getService();
                if (packages == null) throw new IllegalStateException("Virtual package service unavailable");
                ResolveInfo resolved = packages.resolveService(intent, GET_META_DATA, resolvedType, userId);
                if (resolved != null) {
                    route = "GUEST_PROXY";
                    if (resolved.serviceInfo == null || TextUtils.isEmpty(resolved.serviceInfo.packageName)
                            || TextUtils.isEmpty(resolved.serviceInfo.name)) {
                        throw new IllegalStateException("Invalid virtual service metadata");
                    }
                    if (target == null) {
                        target = new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name);
                        intent.setComponent(target);
                        component = target.flattenToShortString();
                    }
                    stage = "virtualBind";
                    IBActivityManagerService activities = BActivityManager.get().getService();
                    if (activities == null) throw new IllegalStateException("Virtual activity service unavailable");
                    ServiceConnectionDelegate existing = connection == null ? null
                            : ServiceConnectionDelegate.getDelegate(connection);
                    IBinder connectionBinder = connection == null ? null
                            : existing == null ? connection.asBinder() : existing.getOriginalBinder();
                    Intent proxyIntent = activities.bindService(intent,
                            connectionBinder, resolvedType, userId);
                    if (proxyIntent == null && com.nexbox.core.core.GmsCompat.isCorePackage(
                            resolved.serviceInfo.packageName)) {
                        // The Google crash circuit may reject a bind. Do not fall through to
                        // the host's GMS or convert temporary unavailability into a guest crash.
                        return 0;
                    }
                    ComponentName proxyComponent = proxyIntent == null ? null : proxyIntent.getComponent();
                    if (proxyComponent == null || !ServiceBindingPolicy.isOrdinaryProxy(
                            proxyComponent.getPackageName(), proxyComponent.getClassName(), callerAfter,
                            ProxyManifest.FREE_COUNT)) {
                        throw new IllegalStateException("Virtual binding did not return a container service");
                    }
                    request[2] = proxyIntent;
                    flagsAfter = ServiceBindingPolicy.flagsForOrdinaryProxy(flagsBefore);
                    request[5] = ServiceBindingPolicy.flagsArgument(flagsAfter, layout.longFlags);
                    instanceAfter = ServiceBindingPolicy.instanceForRoute(instanceBefore, true);
                    if (layout.instanceName >= 0) request[layout.instanceName] = instanceAfter;
                    if (connection != null) {
                        request[4] = ServiceConnectionDelegate.createProxy(connection, intent, userId);
                    }
                } else {
                    ResolveInfo host = NexBoxCore.getPackageManager().resolveService(intent, GET_META_DATA);
                    if (com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intent, host == null ? null : host.serviceInfo)) {
                        return 0;
                    }
                    if (connection != null && OaidServiceBinder.matches(intent)) {
                        route = "SYSTEM_OAID";
                        oaid = OaidServiceBinder.describe(intent, host);
                        binding = ServiceConnectionDelegate.acquireBinding(connection, intent);
                        request[4] = binding.connection();
                    }
                }
            }
            // System/external bindings keep their instance, full flags and framework dispatcher.
            // A Binder IServiceConnection adapter belongs only in the request, never mConnection.
            stage = "ams";
            Object result = invokeServiceMethod(who, method, request);
            if (binding != null) {
                try {
                    connectionState = binding.finish(result instanceof Number && ((Number) result).intValue() > 0);
                } catch (RuntimeException cleanupFailure) {
                    connectionState = "cleanupFailed";
                    Slog.w("ServiceBinding", "action=bindService stage=connectionCleanup result=failed"
                            + " error=" + cleanupFailure.getClass().getSimpleName());
                }
            }
            ServiceBindingDiagnostics.report(method.getName(), stage, route, component, callerBefore,
                    callerAfter, userId, flagsBefore, flagsAfter, instanceBefore, instanceAfter, result, null, oaid, connectionState);
            return result;
        } catch (Throwable failure) {
            if (binding != null) {
                try {
                    connectionState = binding.finish(false);
                } catch (RuntimeException cleanupFailure) {
                    if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                    connectionState = "cleanupFailed";
                }
            }
            ServiceBindingDiagnostics.report(method.getName(), stage, route, component, callerBefore,
                    callerAfter, userId, flagsBefore, flagsAfter, instanceBefore, instanceAfter, null, failure, oaid, connectionState);
            throw failure;
        }
    }

    private static Object invokeServiceMethod(Object who, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(who, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }
    @ProxyMethod("bindService")
    public static class BindService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BindServiceCommon(who, method, args);
        }

        @Override
        protected boolean isEnable() {
            return NexBoxCore.get().isGuestProcess() || NexBoxCore.get().isServerProcess();
        }
    }

    
    @ProxyMethod("bindServiceInstance")
    public static class bindServiceInstance extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BindServiceCommon(who, method, args);
        }

        @Override
        protected boolean isEnable() {
            return NexBoxCore.get().isGuestProcess() || NexBoxCore.get().isServerProcess();
        }
    }

    
    @ProxyMethod("bindIsolatedService")
    public static class BindIsolatedService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BindServiceCommon(who, method, args);
        }

        @Override
        protected boolean isEnable() {
            return NexBoxCore.get().isGuestProcess() || NexBoxCore.get().isServerProcess();
        }
    }

    @ProxyMethod("unbindService")
    public static class UnbindService extends MethodHook {

        @Override
        protected boolean isEnable() {
            return NexBoxCore.get().isGuestProcess() || NexBoxCore.get().isServerProcess();
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IServiceConnection connection = args != null && args.length == 1
                    && args[0] instanceof IServiceConnection ? (IServiceConnection) args[0] : null;
            ServiceConnectionDelegate delegate = connection == null ? null
                    : ServiceConnectionDelegate.getDelegate(connection);
            if (delegate == null) return invokeServiceMethod(who, method, args);
            Object[] request = args.clone();
            request[0] = delegate;
            // External/OAID bindings do not own a virtual service registration.
            // Update virtual counts before AMS can dispatch ProxyService.onUnbind.
            Throwable cleanupFailure = null;
            int[] virtualUsers = delegate.getVirtualUsers();
            try {
                if (virtualUsers.length > 0) {
                    IBActivityManagerService activities = BActivityManager.get().getService();
                    if (activities == null) throw new IllegalStateException("Virtual activity service unavailable");
                    for (int userId : virtualUsers) {
                        activities.unbindService(delegate.getOriginalBinder(), userId);
                    }
                    delegate.clearVirtualUsers();
                }
            } catch (Throwable failure) {
                cleanupFailure = failure;
            }
            Object result;
            try {
                // Even failed virtual cleanup must not leave the real connection bound.
                result = invokeServiceMethod(who, method, request);
            } catch (Throwable failure) {
                if (cleanupFailure != null && cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                throw failure;
            }
            if (cleanupFailure != null) throw cleanupFailure;
            return result;
        }
    }

    @ProxyMethod("getRunningAppProcesses")
    public static class GetRunningAppProcesses extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            RunningAppProcessInfo runningAppProcesses = BActivityManager.get().getRunningAppProcesses(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            return runningAppProcesses.mAppProcessInfoList;
        }
    }

    @ProxyMethod("getMyMemoryState")
    public static class GetMyMemoryState extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length != 1 || !(args[0] instanceof ActivityManager.RunningAppProcessInfo)) {
                throw new IllegalArgumentException("Unsupported process memory state query");
            }
            AppConfig caller = BActivityThread.getAppConfig();
            if (caller == null || caller.buid < android.os.Process.FIRST_APPLICATION_UID) {
                throw new IllegalStateException("Guest process identity unavailable");
            }
            Object result = invokeServiceMethod(who, method, args);
            ((ActivityManager.RunningAppProcessInfo) args[0]).uid = caller.buid;
            return result;
        }
    }

    @ProxyMethod("getServices")
    public static class GetServices extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args == null || args.length < 1 || !(args[0] instanceof Integer)) {
                throw new IllegalArgumentException("Missing running service limit");
            }
            int maxNum = (Integer) args[0];
            RunningServiceInfo runningServices = BActivityManager.get().getRunningServices(BActivityThread.getAppPackageName(), BActivityThread.getUserId());
            if (maxNum <= 0) return new ArrayList<>();
            if (runningServices.mRunningServiceInfoList.size() > maxNum) {
                return new ArrayList<>(runningServices.mRunningServiceInfoList.subList(0, maxNum));
            }
            return runningServices.mRunningServiceInfoList;
        }
    }

    @ProxyMethod("getIntentSender")
    public static class GetIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!NexBoxCore.get().isGuestProcess()) return method.invoke(who, args);
            com.nexbox.core.utils.compat.PendingIntentCompat.requireSenderAccess(method.getReturnType());
            int index = getIntentsIndex(args);
            Intent[] intents = (Intent[]) args[index];
            Intent[] copies = new Intent[intents.length];
            for (int i = 0; i < copies.length; i++) {
                copies[i] = new Intent(intents[i]);
                com.nexbox.core.utils.IntentSanitizer.sanitizeClassExtrasForIpc(copies[i]);
            }
            android.os.Bundle request = new android.os.Bundle();
            request.putInt("type", (Integer) args[0]);
            request.putInt("requestCode", (Integer) args[index - 1]);
            request.putParcelableArray("intents", copies);
            request.putStringArray("types", (String[]) args[index + 1]);
            request.putInt("flags", (Integer) args[index + 2]);
            IBActivityManagerService activities = NexBoxCore.getBActivityManager().getService();
            if (activities == null) throw new IllegalStateException("Virtual activity service unavailable");
            android.os.Bundle response = activities.pendingIntentOperation("create", request);
            if (response == null) throw new IllegalStateException("Virtual PendingIntent service returned no result");
            android.app.PendingIntent pending = BundleCompat.getParcelable(
                    response, "pending", android.app.PendingIntent.class);
            if (pending == null) {
                if (((Integer) args[index + 2] & android.app.PendingIntent.FLAG_NO_CREATE) != 0) return null;
                throw new IllegalStateException("Virtual PendingIntent creation returned no token");
            }
            IInterface sender = com.nexbox.core.utils.compat.PendingIntentCompat.getSender(pending);
            NexBoxCore.getBActivityManager().getIntentSender(sender.asBinder(), BActivityThread.getAppPackageName(), BActivityThread.getBUid());
            return sender;
        }

        private int getIntentsIndex(Object[] args) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    return i;
                }
            }
            return 6;
        }
    }

    @ProxyMethod("getPackageForIntentSender")
    public static class getPackageForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return NexBoxCore.getBActivityManager().getPackageForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getUidForIntentSender")
    public static class getUidForIntentSender extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IInterface invoke = (IInterface) args[0];
            return NexBoxCore.getBActivityManager().getUidForIntentSender(invoke.asBinder());
        }
    }

    @ProxyMethod("getIntentSenderWithSourceToken")
    public static class GetIntentSenderWithSourceToken extends GetIntentSender {
    }

    @ProxyMethod("getIntentSenderWithFeature")
    public static class GetIntentSenderWithFeature extends GetIntentSender {
    }

    @ProxyMethod("broadcastIntentWithFeature")
    public static class BroadcastIntentWithFeature extends BroadcastIntent {
    }

    @ProxyMethod("broadcastIntent")
    public static class BroadcastIntent extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object[] request = args.clone();
            int index = -1;
            for (int i = 0; i < request.length; i++) if (request[i] instanceof Intent) { index = i; break; }
            if (index < 0) throw new IllegalArgumentException("Missing broadcast intent");
            Intent original = new Intent((Intent) request[index]);
            String[] permissions = null;
            String[] excludedPermissions = null;
            String[] excludedPackages = null;
            int permissionArrays = 0;
            for (int i = index + 2; i < request.length; i++) {
                if (method.getParameterTypes()[i] == String[].class) {
                    String[] values = (String[]) request[i];
                    if (permissionArrays == 0) permissions = values;
                    else if (permissionArrays == 1) excludedPermissions = values;
                    else if (permissionArrays == 2) excludedPackages = values;
                    else throw new UnsupportedOperationException("Unknown broadcast permission signature");
                    permissionArrays++;
                    request[i] = null; // Enforced against virtual receivers by the server.
                }
            }
            int userIndex = request.length - 1;
            if (method.getParameterTypes()[userIndex] != int.class
                    || method.getParameterTypes()[userIndex - 1] != boolean.class
                    || method.getParameterTypes()[userIndex - 2] != boolean.class) {
                throw new UnsupportedOperationException("Unknown broadcast signature");
            }
            if (Boolean.TRUE.equals(request[userIndex - 1])) throw new SecurityException("Virtual sticky broadcasts are unsupported");
            request[index] = NexBoxCore.getBActivityManager().getService().prepareBroadcast(
                    original, (String) request[index + 1], permissions, excludedPermissions, excludedPackages, BActivityThread.getUserId());
            request[index + 1] = null; // Carrier has no MIME type; original type stays on the server.
            request[userIndex] = NexBoxCore.getHostUserId();
            if (request[index + 2] instanceof IIntentReceiver) {
                IIntentReceiver result = (IIntentReceiver) request[index + 2];
                request[index + 2] = new IIntentReceiver.Stub() {
                    @Override public void performReceive(Intent ignored, int code, String data, Bundle extras,
                            boolean ordered, boolean sticky, int user) throws RemoteException {
                        black.android.content.BRIIntentReceiver.get(result).performReceive(original, code, data, extras,
                                ordered, sticky, BActivityThread.getUserId());
                    }
                };
            }
            try { return method.invoke(who, request); }
            catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        }
    }

    @ProxyMethod("unregisterReceiver")
    public static class unregisterReceiver extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            IIntentReceiver receiver = args[0] instanceof IIntentReceiver ? (IIntentReceiver) args[0] : null;
            InnerReceiverDelegate delegate = receiver == null ? null : InnerReceiverDelegate.getDelegate(receiver.asBinder());
            Object[] request = args.clone();
            Throwable virtualFailure = null;
            if (delegate != null) {
                try { delegate.unregisterVirtual(); }
                catch (Throwable failure) { virtualFailure = failure; }
            }
            Object result;
            try {
                result = invokeServiceMethod(who, method, request);
            } catch (Throwable failure) {
                if (virtualFailure != null) failure.addSuppressed(virtualFailure);
                throw failure;
            }
            if (virtualFailure != null) throw virtualFailure;
            return result;
        }
    }
    @ProxyMethod("finishReceiver")
    public static class finishReceiver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args.length >= 5 && args[0] instanceof IBinder
                    && com.nexbox.core.app.GuestBroadcastCompletion.finish(
                            (IBinder) args[0], (Integer) args[1], (String) args[2],
                            (android.os.Bundle) args[3], (Boolean) args[4])) {
                return null;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("publishService")
    public static class PublishService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("peekService")
    public static class PeekService extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastAppPkg(args);
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            IBinder peek = NexBoxCore.getBActivityManager().peekService(intent, resolvedType, BActivityThread.getUserId());
            return peek;
        }
    }

    
    @ProxyMethod("sendIntentSender")
    public static class SendIntentSender extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // Virtual PendingIntents are real OS capabilities targeting our checked carrier.
            // Returning zero without sending drops alarms/actions and falsely reports success.
            Object result = invokeServiceMethod(who, method, args);
            Slog.dLazy(TAG, () -> "action=sendIntentSender stage=systemReturned result=" + result);
            return result;
        }
    }

    
    @ProxyMethod("registerReceiverWithFeature")
    public static class RegisterReceiverWithFeature extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return registerBroadcast(who, method, args, 4, 6);
        }
    }

    @ProxyMethod("registerReceiver")
    public static class RegisterReceiver extends MethodHook {
        @Override protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return registerBroadcast(who, method, args, 2, 4);
        }
    }

    private static Object registerBroadcast(Object who, Method method, Object[] args, int receiverIndex, int permissionIndex) throws Throwable {
        Class<?>[] parameters = method.getParameterTypes();
        if (args == null || args.length != permissionIndex + 3 || parameters.length != args.length
                || parameters[receiverIndex] != IIntentReceiver.class
                || parameters[receiverIndex + 1] != android.content.IntentFilter.class
                || parameters[permissionIndex] != String.class
                || parameters[permissionIndex + 1] != int.class
                || parameters[permissionIndex + 2] != int.class) {
            throw new UnsupportedOperationException("Unknown receiver registration signature");
        }
        Object[] request = args.clone();
        MethodParameterUtils.replaceFirstAppPkg(request);
        request[permissionIndex + 1] = NexBoxCore.getHostUserId();
        int flags = 0;
        if (request.length > permissionIndex + 2 && request[request.length - 1] instanceof Integer) flags = (Integer) request[request.length - 1];
        if (BuildCompat.isTiramisu() && (flags & (RECEIVER_EXPORTED | RECEIVER_NOT_EXPORTED)) == 0) {
            flags |= RECEIVER_NOT_EXPORTED;
            request[request.length - 1] = flags;
        } else if (!BuildCompat.isTiramisu()) {
            flags |= RECEIVER_EXPORTED; // The pre-33 registration API is exported by default.
        }
        InnerReceiverDelegate delegate = null;
        if (request[receiverIndex] != null) {
            IIntentReceiver original = (IIntentReceiver) request[receiverIndex];
            delegate = InnerReceiverDelegate.createProxy(original);
            // Keep the framework receiver on the OS path. Replacing it with our Stub loses
            // modern sender identity/assumeDelivered handling; virtual delivery has its own Binder.
        }
        Object result;
        try { result = method.invoke(who, request); }
        catch (Throwable failure) {
            if (delegate != null) delegate.discardIfUnregistered();
            throw failure instanceof java.lang.reflect.InvocationTargetException ? failure.getCause() : failure;
        }
        if (delegate != null) {
            try {
                delegate.registerVirtual((android.content.IntentFilter) request[receiverIndex + 1], (String) request[permissionIndex], flags);
            } catch (Throwable failure) {
                try {
                    // The platform's concrete AIDL Proxy can be non-public. Invoke through
                    // the same public Binder interface that declared the registration call.
                    Method unregister = method.getDeclaringClass().getMethod("unregisterReceiver", IIntentReceiver.class);
                    invokeServiceMethod(who, unregister, new Object[]{request[receiverIndex]});
                }
                catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                try { delegate.unregisterVirtual(); }
                catch (Throwable cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                // Android cannot remove only one filter. Cleanup invalidates this receiver's
                // registrations, including earlier filters; never silently replay sticky deliveries.
                Slog.w(TAG, "action=registerReceiver stage=rollback result=registration_failed"
                        + " receiverRegistrationsMayBeRemoved=true retry=explicit_registration", failure);
                throw failure;
            }
        }
        return result;
    }
    @ProxyMethod("grantUriPermission")
    public static class GrantUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceLastUid(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setServiceForeground")
    public static class setServiceForeground extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            for (int i = args.length - 1; i >= 0; i--) {
                if (args[i] instanceof Integer) {
                    args[i] = 0; 
                    break;
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("updateServiceGroup")
    public static class UpdateServiceGroup extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 3 && args[0] instanceof IServiceConnection) {
                ServiceConnectionDelegate delegate = ServiceConnectionDelegate.getDelegate((IServiceConnection) args[0]);
                if (delegate != null) {
                    Object[] request = args.clone();
                    request[0] = delegate;
                    return invokeServiceMethod(who, method, request);
                }
            }
            return invokeServiceMethod(who, method, args);
        }
    }

    @ProxyMethod("getHistoricalProcessExitReasons")
    public static class getHistoricalProcessExitReasons extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethods({"getCurrentUser", "getCurrentUserId"})
    public static class getCurrentUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!NexBoxCore.get().isGuestProcess() || method.getParameterTypes().length != 0) {
                try {
                    return method.invoke(who, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause() == null ? failure : failure.getCause();
                }
            }
            AppConfig config = BActivityThread.getAppConfig();
            if (config == null) throw new IllegalStateException("Current virtual user is unavailable");
            // These are views of the same virtual user, not privileged host-user queries.
            Object result;
            if ("getCurrentUserId".equals(method.getName())) {
                if (method.getReturnType() != int.class) {
                    throw new IllegalStateException("Unsupported current-user ID signature");
                }
                result = config.userId;
            } else {
                result = BRUserInfo.getWithException()._new(config.userId, "NexBox",
                        BRUserInfo.getWithException().FLAG_PRIMARY());
            }
            Slog.dLazy(TAG, () -> "stage=currentUser method=" + method.getName()
                    + " source=virtual userId=" + config.userId + " generation=" + config.userGeneration);
            return result;
        }
    }

    @ProxyMethods({"checkPermission", "checkPermissionForDevice"})
    public static class checkPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return GuestPermissionChecks.invoke(PermissionCheckPolicy.Backend.ACTIVITY, who, method, args);
        }
    }

    @ProxyMethod("checkUriPermission")
    public static class checkUriPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return PERMISSION_GRANTED;
        }
    }

    
    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("setRequestedOrientation")
    public static class setRequestedOrientation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                Slog.e("IActivityManagerProxy", "Container operation failed", e);
            }
            return 0;
        }
    }

    @ProxyMethod("registerUidObserver")
    public static class registerUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("unregisterUidObserver")
    public static class unregisterUidObserver extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("updateConfiguration")
    public static class updateConfiguration extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
