package com.nexbox.core.fake.service;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.Binder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BPackageManager;
import com.nexbox.core.fake.frameworks.BAccountManager;
import com.nexbox.core.core.system.accounts.IBAccountManagerService;
import com.nexbox.core.core.system.accounts.AccountAuthenticatorTransport;
import com.nexbox.core.utils.Slog;

/** Manifest + host grants, with narrow Google exceptions backed by enabled virtual data sources. */
final class GuestPermissionChecks {
    private static final String TAG = "GuestPermissionChecks";
    private static final AtomicBoolean CACHES_CONFIGURED = new AtomicBoolean();
    private static final Set<String> LOGGED_RESULTS = ConcurrentHashMap.newKeySet();
    private static volatile ManifestSnapshot sManifest;

    private static final class ManifestSnapshot {
        final AppConfig configuration;
        final Set<String> requested;

        ManifestSnapshot(AppConfig configuration, Set<String> requested) {
            this.configuration = configuration;
            this.requested = Collections.unmodifiableSet(requested);
        }
    }

    private GuestPermissionChecks() { }

    static Object invoke(PermissionCheckPolicy.Backend backend, Object service, Method method,
            Object[] args) throws Throwable {
        Class<?>[] parameters = method.getParameterTypes();
        if (args == null || args.length != parameters.length || method.getReturnType() != int.class) {
            return invokeOriginal(service, method, args);
        }
        String[] types = new String[parameters.length];
        for (int i = 0; i < parameters.length; i++) types[i] = parameters[i].getName();
        PermissionCheckPolicy.Layout layout = PermissionCheckPolicy.layout(backend, method.getName(), types);
        if (layout.permission >= 0 && "android.permission.ACCOUNT_MANAGER".equals(args[layout.permission])) {
            Slog.d(TAG, "action=accountPermission backend=" + backend + " method=" + method.getName()
                    + " requestedPid=" + (layout.pid < 0 ? -1 : args[layout.pid])
                    + " requestedUid=" + (layout.uid < 0 ? -1 : args[layout.uid])
                    + " binderPid=" + Binder.getCallingPid() + " binderUid=" + Binder.getCallingUid());
        }
        AppConfig configuration = NexBoxCore.getAppConfig();
        if (configuration == null || !NexBoxCore.get().isGuestProcess()) return invokeOriginal(service, method, args);
        if (backend == PermissionCheckPolicy.Backend.ACTIVITY
                && layout.permission >= 0 && layout.pid >= 0 && layout.uid >= 0
                && "android.permission.ACCOUNT_MANAGER".equals(args[layout.permission])
                && args[layout.pid] instanceof Integer && args[layout.uid] instanceof Integer) {
            int callerPid = (Integer) args[layout.pid];
            int callerUid = (Integer) args[layout.uid];
            if (AccountAuthenticatorTransport.isAccountManagerCall(callerPid, callerUid)) {
                Slog.d(TAG, "action=accountPermission result=granted transport=authenticatedOneway");
                return PackageManager.PERMISSION_GRANTED;
            }
            // Only a live incoming Binder call from our account-service process qualifies.
            // Sharing the host UID alone is insufficient: every guest has that Linux UID.
            if (callerPid != Process.myPid() && callerPid == Binder.getCallingPid()
                    && callerUid == Binder.getCallingUid() && callerUid == NexBoxCore.getHostUid()) {
                IBAccountManagerService accounts = BAccountManager.get().getService();
                if (accounts != null && accounts.isAccountServiceProcess(callerPid, callerUid)) {
                    return PackageManager.PERMISSION_GRANTED;
                }
            }
        }
        PermissionCheckPolicy.Identity identity = new PermissionCheckPolicy.Identity(
                configuration.packageName, configuration.buid, configuration.userId,
                NexBoxCore.getHostPkg(), NexBoxCore.getHostUid(), NexBoxCore.getHostUserId(), Process.myPid());
        if (!PermissionCheckPolicy.isCurrentGuest(layout, args, identity)) return invokeOriginal(service, method, args);

        String permission = (String) args[layout.permission];
        ManifestSnapshot manifest = manifest(configuration);
        if (GooglePermissionCompat.hasVirtualGrant(configuration.packageName,
                manifest == null ? null : manifest.requested, permission)) {
            logResult(backend, permission, identity, "google_virtual_backend", PackageManager.PERMISSION_GRANTED);
            return PackageManager.PERMISSION_GRANTED;
        }
        Object[] hostQuery = PermissionCheckPolicy.mapCurrentGuest(layout, args, identity);
        int result = PermissionCheckPolicy.checkDeclared(manifest == null ? null : manifest.requested,
                permission, () -> (Integer) invokeOriginal(service, method, hostQuery));
        String reason = manifest == null ? "manifest_unavailable"
                : manifest.requested.contains(permission) ? "declared_host_checked" : "not_declared";
        logResult(backend, permission, identity, reason, result);
        return result;
    }

    private static ManifestSnapshot manifest(AppConfig configuration) throws RemoteException {
        ManifestSnapshot cached = sManifest;
        if (cached != null && cached.configuration == configuration) return cached;
        synchronized (GuestPermissionChecks.class) {
            cached = sManifest;
            if (cached != null && cached.configuration == configuration) return cached;
            IBPackageManagerService packages = BPackageManager.get().getService();
            if (packages == null) return null;
            // Do not use the convenience PM fallback, which can read a same-name host APK.
            PackageInfo info = packages.getPackageInfo(configuration.packageName,
                    PackageManager.GET_PERMISSIONS, configuration.userId);
            if (info == null || !configuration.packageName.equals(info.packageName) || info.applicationInfo == null) return null;
            Set<String> requested = new HashSet<>();
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) if (permission != null) requested.add(permission);
            }
            // A guest's installed manifest is fixed for its bound AppConfig/process lifetime.
            // Host runtime grants are intentionally NOT cached here, so revocations still apply.
            cached = new ManifestSnapshot(configuration, requested);
            sManifest = cached;
            return cached;
        }
    }

    static void configureGuestPermissionCaches() {
        if (!NexBoxCore.get().isGuestProcess() || !CACHES_CONFIGURED.compareAndSet(false, true)) return;
        for (String name : new String[]{"disablePermissionCache", "disablePackageNamePermissionCache"}) {
            try {
                Class<?> manager = Class.forName("android.permission.PermissionManager");
                Method disable = manager.getDeclaredMethod(name);
                disable.setAccessible(true);
                disable.invoke(null);
            } catch (NoSuchMethodException ignored) {
                // Older Android versions use a direct service check or lack this local cache.
            } catch (ReflectiveOperationException | RuntimeException failure) {
                try {
                    Slog.w(TAG, "action=configureLocalPermissionCache method=" + name
                            + " error=" + failure.getClass().getSimpleName());
                } catch (Throwable ignored) {
                    // Diagnostics must not interrupt installation of the permission hooks.
                }
            }
        }
    }

    private static Object invokeOriginal(Object service, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(service, args == null ? null : args.clone());
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            throw cause == null ? failure : cause;
        }
    }

    private static void logResult(PermissionCheckPolicy.Backend backend, String permission,
            PermissionCheckPolicy.Identity identity, String reason, int result) {
        try {
            String key = backend + ":" + permission + ":" + reason + ":" + result;
            if (LOGGED_RESULTS.size() < 128 && LOGGED_RESULTS.add(key)) {
                Slog.d(TAG, "action=checkPermission backend=" + backend + " permission=" + permission
                        + " guest=" + identity.guestPackage + " virtualUid=" + identity.guestUid
                        + " hostUid=" + identity.hostUid + " reason=" + reason + " result=" + result);
            }
        } catch (Throwable ignored) {
            // An optional log sink cannot replace the permission verdict returned to the app.
        }
    }
}
