package com.nexbox.core.core.system.pm;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.GmsCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.installer.CreateUserExecutor;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.entity.pm.InstallResult;
import com.nexbox.core.entity.pm.InstalledPackage;
import com.nexbox.core.utils.AbiUtils;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.PackageParserCompat;


import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;



public class BPackageManagerService extends IBPackageManagerService.Stub implements ISystemService {
    public static final String TAG = "BPackageManagerService";
    public static BPackageManagerService sService = new BPackageManagerService();
    private final Settings mSettings = new Settings();
    private final ComponentResolver mComponentResolver;
    private static final BUserManagerService sUserManager = BUserManagerService.get();
    private final List<PackageMonitor> mPackageMonitors = new ArrayList<>();
    private final ApplicationLocaleStore mLocaleStore = new ApplicationLocaleStore();
    private final java.util.concurrent.Executor mHostSyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

    final Map<String, BPackageSettings> mPackages = mSettings.mPackages;
    final Object mInstallLock = new Object();
    private final InventorySnapshotStore mInventory = new InventorySnapshotStore();

    /** Lock order: lifecycle/install -> user -> packages. */
    public Object getLifecycleLock() { return mInstallLock; }

    @Override
    public android.os.IBinder getGuestPackageInstaller() {
        return GuestPackageInstallerService.get();
    }

    public static BPackageManagerService get() {
        return sService;
    }

    @Override
    public Bundle getApplicationLocaleState(String packageName, int userId) {
        synchronized (mInstallLock) {
            BUserInfo user = requireLocaleCaller(packageName, userId, false);
            return mLocaleStore.read(packageName, userId, user.createTime);
        }
    }

    @Override
    public void setApplicationLocales(String packageName, String languageTags, int userId) {
        setLocaleState(packageName, languageTags, userId, false);
    }

    @Override
    public void setOverrideLocaleConfig(String packageName, String languageTags, int userId) {
        setLocaleState(packageName, languageTags, userId, true);
    }

    private void setLocaleState(String packageName, String tags, int userId, boolean override) {
        long generation;
        synchronized (mInstallLock) {
            BUserInfo user = requireLocaleCaller(packageName, userId, true);
            generation = user.createTime;
            if (!mLocaleStore.write(packageName, userId, generation, tags, override)) return;
        }
        if (!override) BProcessManagerService.get().notifyApplicationLocales(packageName, userId, generation);
    }

    private BUserInfo requireLocaleCaller(String packageName, int userId, boolean write) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        ApplicationInfo target = getApplicationInfo(packageName, 0, userId);
        if (user == null || TextUtils.isEmpty(packageName) || target == null || !isInstalled(packageName, userId)) {
            throw new IllegalArgumentException("Locale target is not installed in this profile");
        }
        int pid = Binder.getCallingPid();
        if (pid == android.os.Process.myPid() && Binder.getCallingUid() == NexBoxCore.getHostUid()) return user;
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(pid);
        if (caller == null) {
            XposedCallerGuard.enforceHostManager();
            return user;
        }
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller.userId != userId
                || caller.userGeneration != user.createTime || caller.bActivityThread == null
                || !caller.bActivityThread.asBinder().isBinderAlive()) {
            throw new SecurityException("Locale request belongs to another user lifetime");
        }
        // The project's reported installer is Play. It may read, never change, other apps' locales.
        boolean installerRead = !write && "com.android.vending".equals(caller.getPackageName());
        if (caller.buid != target.uid && !installerRead) {
            throw new SecurityException("Application locale access denied");
        }
        return user;
    }

    public BPackageManagerService() {
        mComponentResolver = new ComponentResolver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.PACKAGE_ADDED");
        filter.addAction("android.intent.action.PACKAGE_REMOVED");
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        NexBoxCore.getContext()
                .registerReceiver(mPackageChangedHandler, filter);
    }

    private final BroadcastReceiver mPackageChangedHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_PACKAGE_ADDED.equals(action) && !Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) return;
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action) && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String packageName = intent.getData() == null ? null : intent.getData().getSchemeSpecificPart();
            if (TextUtils.isEmpty(packageName)) return;
            // Parsing a large split set must not block BroadcastReceiver's main thread.
            mHostSyncExecutor.execute(() -> {
                try { syncHostPackagesInternal(packageName); }
                catch (RuntimeException error) { Slog.w(TAG, "action=syncHostSource stage=broadcast failed", error); }
            });
        }
    };

    @Override
    public Bundle syncHostPackages(String packageName) {
        XposedCallerGuard.enforceHostManager();
        return syncHostPackagesInternal(packageName);
    }

    private Bundle syncHostPackagesInternal(String packageName) {
        Bundle result = new Bundle();
        ArrayList<Bundle> entries = new ArrayList<>();
        int updated = 0;
        int failed = 0;
        synchronized (mInstallLock) {
            requireLifecycleMetadataReadable();
            List<BPackageSettings> snapshot;
            synchronized (mPackages) { snapshot = new ArrayList<>(mPackages.values()); }
                for (BPackageSettings previous : snapshot) {
                    if (!previous.installOption.isFlag(InstallOption.FLAG_SYSTEM)
                            || (packageName != null && !packageName.equals(previous.pkg.packageName))) continue;
                    Bundle entry = new Bundle();
                    entry.putString("packageName", previous.pkg.packageName);
                    try {
                        BPackageSettings next = HostPackageSync.prepare(previous);
                        if (next == null) entry.putString("status", "unchanged");
                        else {
                            mInventory.invalidate(previous.pkg.packageName);
                            // Process startup acquires the process lock before package queries.
                            BProcessManagerService.get().killAllByPackageName(previous.pkg.packageName);
                            synchronized (mPackages) {
                                try {
                                    mComponentResolver.addAllComponents(next.pkg);
                                    if (!next.save()) throw new IOException("Could not persist synchronized host package");
                                    mPackages.put(next.pkg.packageName, next);
                                } catch (Throwable failure) {
                                    mComponentResolver.addAllComponents(previous.pkg);
                                    throw failure;
                                }
                            }
                            for (Integer userId : next.getUserIds()) {
                                if (next.getInstalled(userId)) {
                                    try { onPackageInstalled(next.pkg.packageName, userId); }
                                    catch (Throwable error) { Slog.w(TAG, "Host update observer failed after commit", error); }
                                }
                            }
                            entry.putString("status", "updated");
                            entry.putLong("versionCode", next.pkg.getLongVersionCode());
                            updated++;
                        }
                        entry.putBoolean("sourceAvailable", true);
                    } catch (Throwable error) {
                        failed++;
                        entry.putString("status", error instanceof PackageManager.NameNotFoundException
                                || error instanceof PackageCodeAvailability.SourceUnavailableException ? "unavailable" : "failed");
                        entry.putString("reason", error.getClass().getSimpleName());
                        entry.putBoolean("sourceAvailable", PackageCodeAvailability.isAvailable(previous.pkg.applicationInfo));
                        Slog.w(TAG, "action=syncHostSource stage=rejected package=" + previous.pkg.packageName
                                + " data=retained", error);
                    }
                    if (!"unchanged".equals(entry.getString("status"))) {
                        Slog.i(TAG, "action=syncHostSource package=" + previous.pkg.packageName
                                + " status=" + entry.getString("status") + " sourceAvailable=" + entry.getBoolean("sourceAvailable"));
                    }
                    entries.add(entry);
            }
        }
        result.putParcelableArrayList("packages", entries);
        result.putInt("updated", updated);
        result.putInt("failed", failed);
        result.putBoolean("success", failed == 0);
        return result;
    }

    @Override
    public ApplicationInfo getApplicationInfo(String packageName, int flags, int userId) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        if (Objects.equals(packageName, NexBoxCore.getHostPkg())) {
            try {
                ApplicationInfo result = NexBoxCore.getPackageManager().getApplicationInfo(packageName, flags);
                requireQueryUser(userId, user.createTime);
                return result;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e("BPackageManagerService", "Container operation failed", e);
            }
            return null;
        }
        PackageQueryContext request = new PackageQueryContext(updateFlags(flags, userId), userId, user.createTime);
        PackageQueryContext.Snapshot snapshot = capturePackageQuery(request, packageName);
        requireQueryUser(userId, request.userCreateTime);
        ApplicationInfo result = snapshot == null ? null : PackageManagerCompat.generateApplicationInfo(snapshot, request);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    private PackageQueryContext.Snapshot capturePackageQuery(PackageQueryContext request, String packageName) {
        synchronized (mPackages) {
            BPackageSettings settings = mPackages.get(packageName);
            return settings == null ? null : request.capture(settings.pkg, settings.readUserState(request.userId));
        }
    }

    private List<PackageQueryContext.Snapshot> captureInstalledQuery(PackageQueryContext request, boolean hostPackageSpecialCase) {
        return captureInstalledQuery(request, hostPackageSpecialCase, false);
    }

    private List<PackageQueryContext.Snapshot> captureInstalledQuery(PackageQueryContext request,
                                                                    boolean hostPackageSpecialCase, boolean includeHidden) {
        String hostPackage = NexBoxCore.getHostPkg();
        synchronized (mPackages) {
            ArrayList<PackageQueryContext.Snapshot> snapshots = new ArrayList<>(mPackages.size());
            for (BPackageSettings settings : mPackages.values()) {
                BPackageUserState state = settings.readUserState(request.userId);
                boolean hostSpecial = hostPackageSpecialCase && Objects.equals(settings.pkg.packageName, hostPackage);
                if ((!state.installed || state.hidden && !includeHidden) && !hostSpecial) continue;
                snapshots.add(request.capture(settings.pkg, state));
            }
            return snapshots;
        }
    }

    private void requireQueryUser(int userId, long createTime) {
        BUserInfo current = sUserManager.getUserInfo(userId);
        if (!PackageQueryContext.matchesUser(userId, createTime, current)) {
            throw new IllegalStateException("Virtual user changed during package query");
        }
    }

    @Override
    public ResolveInfo resolveService(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> query = queryIntentServicesInternal(
                intent, resolvedType, flags, userId);
        if (query != null) {
            if (query.size() >= 1) {
                
                
                return query.get(0);
            }
        }
        ResolveInfo samsungHealthFallback = resolveSamsungHealthServiceFallback(intent, flags, userId);
        if (samsungHealthFallback != null) {
            return samsungHealthFallback;
        }
        return null;
    }

    private ResolveInfo resolveSamsungHealthServiceFallback(Intent intent, int flags, int userId) {
        if (intent == null || intent.getComponent() != null) {
            return null;
        }
        String packageName = intent.getPackage();
        String action = intent.getAction();
        if (!TextUtils.equals("com.sec.android.app.shealth", packageName)) {
            return null;
        }
        if (!TextUtils.equals("com.samsung.android.sdk.healthdata.IHealthDataStore", action)
                && !TextUtils.equals("com.samsung.android.sdk.healthdata.IPrivilegedHealth", action)) {
            return null;
        }
        ComponentName component = new ComponentName(
                "com.sec.android.app.shealth",
                "com.samsung.android.service.health.HealthService"
        );
        ServiceInfo serviceInfo = getServiceInfo(component, flags, userId);
        if (serviceInfo == null) {
            return null;
        }
        ResolveInfo resolveInfo = new ResolveInfo();
        resolveInfo.serviceInfo = serviceInfo;
        return resolveInfo;
    }

    private List<ResolveInfo> queryIntentServicesInternal(Intent intent, String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ServiceInfo si = getServiceInfo(comp, flags, userId);
            if (si != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.serviceInfo = si;
                list.add(ri);
            }
            return list;
        }

        
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;
                    return mComponentResolver.queryServices(intent, resolvedType, flags, pkg.services,
                            userId);
                }
            } else {
               return mComponentResolver.queryServices(intent, resolvedType, flags, userId);
            }
            return Collections.emptyList();
        }
    }

    @Override
    public ResolveInfo resolveActivity(Intent intent, int flags, String resolvedType, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    @Override
    public ProviderInfo resolveContentProvider(String authority, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        return mComponentResolver.queryProvider(authority, flags, userId);
    }

    @Override
    public ResolveInfo resolveIntent(Intent intent, String resolvedType, int flags, int userId) {
        if (!sUserManager.exists(userId)) return null;
        List<ResolveInfo> resolves = queryIntentActivities(intent, resolvedType, flags, userId);
        return chooseBestActivity(intent, resolvedType, flags, resolves);
    }

    private ResolveInfo chooseBestActivity(Intent intent, String resolvedType,
                                           int flags, List<ResolveInfo> query) {
        if (query != null) {
            final int N = query.size();
            if (N == 1) {
                return query.get(0);
            } else if (N > 1) {
                
                
                ResolveInfo r0 = query.get(0);
                ResolveInfo r1 = query.get(1);
                
                
                if (r0.priority != r1.priority
                        || r0.preferredOrder != r1.preferredOrder
                        || r0.isDefault != r1.isDefault) {
                    return query.get(0);
                }
            }
        }
        return null;
    }

    private List<ResolveInfo> queryIntentActivities(Intent intent,
                                                    String resolvedType, int flags, int userId) {
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivity(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
                return list;
            }
        }

        
        synchronized (mPackages) {
            return mComponentResolver.queryActivities(intent, resolvedType, flags, userId);
        }
    }

    @Override
    public List<ResolveInfo> queryIntentServices(
            Intent intent, int flags, int userId) {
        final String resolvedType = intent.resolveTypeIfNeeded(NexBoxCore.getContext().getContentResolver());
        return this.queryIntentServicesInternal(intent, resolvedType, flags, userId);
    }

    private ActivityInfo getActivity(ComponentName component, int flags,
                                     int userId) {
        return queryActivityInfo(component, updateFlags(flags, userId), userId, false);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int userId) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        if (Objects.equals(packageName, NexBoxCore.getHostPkg())) {
            try {
                PackageInfo result = NexBoxCore.getPackageManager().getPackageInfo(packageName, flags);
                requireQueryUser(userId, user.createTime);
                return result;
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e("BPackageManagerService", "Container operation failed", e);
            }
            return null;
        }

        PackageQueryContext request = new PackageQueryContext(updateFlags(flags, userId), userId, user.createTime);
        PackageQueryContext.Snapshot snapshot = capturePackageQuery(request, packageName);
        requireQueryUser(userId, request.userCreateTime);
        PackageInfo result = snapshot == null ? null : PackageManagerCompat.generatePackageInfo(snapshot, request);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public ServiceInfo getServiceInfo(ComponentName component, int flags, int userId) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        PackageQueryContext request = new PackageQueryContext(flags, userId, user.createTime);
        final BPackage.Service service;
        final PackageQueryContext.Snapshot snapshot;
        synchronized (mPackages) {
            service = mComponentResolver.getService(component);
            BPackageSettings settings = mPackages.get(component.getPackageName());
            if (service == null || settings == null) return null;
            snapshot = request.capture(service.owner, settings.readUserState(userId));
        }
        ServiceInfo result = PackageManagerCompat.generateServiceInfo(service, snapshot, request);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public ActivityInfo getReceiverInfo(ComponentName component, int flags, int userId) {
        return queryActivityInfo(component, flags, userId, true);
    }

    @Override
    public ActivityInfo getActivityInfo(ComponentName component, int flags, int userId) {
        return queryActivityInfo(component, flags, userId, false);
    }

    private ActivityInfo queryActivityInfo(ComponentName component, int flags, int userId, boolean receiver) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        PackageQueryContext request = new PackageQueryContext(flags, userId, user.createTime);
        final BPackage.Activity activity;
        final PackageQueryContext.Snapshot snapshot;
        synchronized (mPackages) {
            activity = receiver ? mComponentResolver.getReceiver(component) : mComponentResolver.getActivity(component);
            BPackageSettings settings = mPackages.get(component.getPackageName());
            if (activity == null || settings == null) return null;
            snapshot = request.capture(activity.owner, settings.readUserState(userId));
        }
        ActivityInfo result = PackageManagerCompat.generateActivityInfo(activity, snapshot, request);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public ProviderInfo getProviderInfo(ComponentName component, int flags, int userId) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        PackageQueryContext request = new PackageQueryContext(flags, userId, user.createTime);
        final BPackage.Provider provider;
        final PackageQueryContext.Snapshot snapshot;
        synchronized (mPackages) {
            provider = mComponentResolver.getProvider(component);
            BPackageSettings settings = mPackages.get(component.getPackageName());
            if (provider == null || settings == null) return null;
            snapshot = request.capture(provider.owner, settings.readUserState(userId));
        }
        ProviderInfo result = PackageManagerCompat.generateProviderInfo(provider, snapshot, request);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getInstalledApplicationsListInternal(flags, userId, Binder.getCallingUid());
    }

    @Override
    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return Collections.emptyList();
        PackageQueryContext request = new PackageQueryContext(updateFlags(flags, userId), userId, user.createTime);
        List<PackageQueryContext.Snapshot> snapshots = captureInstalledQuery(request, true);
        requireQueryUser(userId, request.userCreateTime);
        ArrayList<PackageInfo> result = new ArrayList<>(snapshots.size());
        for (PackageQueryContext.Snapshot snapshot : snapshots) {
            PackageInfo info;
            if (Objects.equals(snapshot.code.packageName, NexBoxCore.getHostPkg())) {
                try { info = NexBoxCore.getPackageManager().getPackageInfo(snapshot.code.packageName, flags); }
                catch (PackageManager.NameNotFoundException missing) { info = null; }
            } else {
                info = PackageManagerCompat.generatePackageInfo(snapshot, request);
            }
            if (info != null) result.add(info);
        }
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    private List<ApplicationInfo> getInstalledApplicationsListInternal(int flags, int userId,
                                                                       int callingUid) {
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return Collections.emptyList();
        PackageQueryContext request = new PackageQueryContext(flags, userId, user.createTime);
        List<PackageQueryContext.Snapshot> snapshots = captureInstalledQuery(request, false);
        requireQueryUser(userId, request.userCreateTime);
        ArrayList<ApplicationInfo> result = new ArrayList<>(snapshots.size());
        for (PackageQueryContext.Snapshot snapshot : snapshots) {
            ApplicationInfo application = PackageManagerCompat.generateApplicationInfo(snapshot, request);
            if (application != null) result.add(application);
        }
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public List<ResolveInfo> queryIntentActivities(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        final String pkgName = intent.getPackage();
        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }

        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getActivityInfo(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                final ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        
        List<ResolveInfo> result;
        synchronized (mPackages) {
            if (pkgName != null) {
                BPackageSettings bPackageSettings = mPackages.get(pkgName);
                result = null;
                if (bPackageSettings != null) {
                    final BPackage pkg = bPackageSettings.pkg;

                    result = mComponentResolver.queryActivities(
                            intent, resolvedType, flags, pkg.activities, userId);
                }
                if (result == null || result.size() == 0) {
                    
                    
                    if (result == null) {
                        result = new ArrayList<>();
                    }
                }
                return result;
            }
            return mComponentResolver.queryActivities(intent, resolvedType, flags, userId);
        }
    }

    @Override
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent, int flags, String resolvedType, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        ComponentName comp = intent.getComponent();
        if (comp == null) {
            if (intent.getSelector() != null) {
                intent = intent.getSelector();
                comp = intent.getComponent();
            }
        }
        if (comp != null) {
            final List<ResolveInfo> list = new ArrayList<>(1);
            final ActivityInfo ai = getReceiverInfo(comp, flags, userId);
            if (ai != null) {
                
                
                
                
                ResolveInfo ri = new ResolveInfo();
                ri.activityInfo = ai;
                list.add(ri);
            }
            return list;
        }

        
        synchronized (mPackages) {
            String pkgName = intent.getPackage();
            BPackageSettings bPackageSettings = mPackages.get(pkgName);
            if (bPackageSettings != null) {
                final BPackage pkg = bPackageSettings.pkg;
                return mComponentResolver.queryReceivers(
                        intent, resolvedType, flags, pkg.receivers, userId);
            } else {
                return mComponentResolver.queryReceivers(intent, resolvedType, flags, userId);
            }
        }
    }

    @Override
    public List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return Collections.emptyList();

        List<ProviderInfo> providers = new ArrayList<>();
        if (TextUtils.isEmpty(processName))
            return providers;
        providers.addAll(mComponentResolver.queryProviders(processName, null, flags, userId));
        return providers;
    }

    @Override
    public InstallResult installPackageAsUser(String file, InstallOption option, int userId) {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mInstallLock) {
            return installPackageAsUserLocked(file, option, userId);
        }
    }

    /** Host module imports allow updates, unlike the duplicate-rejecting clone session. */
    @Override
    public InstallResult installPackageUpdateAsUserIfMatches(String file, InstallOption option, int userId,
                                                             long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime)) {
                return new InstallResult().installError("User generation changed");
            }
            InstallResult result = installPackageAsUserLocked(file, option, userId);
            Slog.i(TAG, "action=guardedPackageUpdate userId=" + userId + " generation=" + expectedUserCreateTime
                    + " success=" + result.success + " package=" + result.packageName);
            return result.success ? result.installSuccess(result.packageName, userId, expectedUserCreateTime) : result;
        }
    }

    @Override
    public InstallResult installExistingPackageAsUserIfMatches(String packageName, int userId,
                                                               long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime)) {
                return new InstallResult().installError("User generation changed");
            }
            InstallResult result = installExistingPackageAsUser(packageName, userId);
            Slog.i(TAG, "action=guardedExistingInstall userId=" + userId + " generation=" + expectedUserCreateTime
                    + " package=" + packageName + " success=" + result.success);
            return result.success ? result.installSuccess(packageName, userId, expectedUserCreateTime) : result;
        }
    }

    @Override
    public InstallResult installPackageAsUserIfMatches(String file, InstallOption option, int userId,
                                                      long expectedUserCreateTime, String expectedPackageName) {
        XposedCallerGuard.enforceHostManager();
        return installPackageAsUserIfMatches(file, option, userId, expectedUserCreateTime,
                expectedPackageName, null);
    }

    @Override
    public IBCloneInstallSession prepareCloneInstall(String source, InstallOption option,
                                                     android.os.IBinder owner) {
        XposedCallerGuard.enforceHostManager();
        if (option == null || owner == null || TextUtils.isEmpty(source)) {
            throw new IllegalArgumentException("A source, options and live owner are required");
        }
        return CloneInstallSession.prepare(this, source, option, owner);
    }

    InstallResult commitPreparedClone(PreparedCloneSource source, int userId, long expectedCreateTime) {
        return installPackageAsUserIfMatches(source.baseApk.getPath(), source.installOption(), userId,
                expectedCreateTime, source.packageName, source);
    }

    private InstallResult installPackageAsUserIfMatches(String file, InstallOption option, int userId,
                                                       long expectedUserCreateTime, String expectedPackageName,
                                                       PreparedCloneSource preparedSource) {
        synchronized (mInstallLock) {
            if (preparedSource != null) {
                try { preparedSource.checkActive(); }
                catch (IOException cancelled) { return new InstallResult().installError(expectedPackageName, cancelled.getMessage()); }
            }
            BUserInfo before;
            synchronized (sUserManager.mUserLock) { before = sUserManager.getUserInfo(userId); }
            String error = InstallRequestPolicy.targetError(userId, expectedUserCreateTime,
                    before == null ? null : before.createTime);
            if (error != null) return new InstallResult().installError(expectedPackageName, error);
            if (TextUtils.isEmpty(expectedPackageName) || expectedPackageName.trim().isEmpty()) {
                return new InstallResult().installError("An expected package name is required");
            }
            // Creation/deletion share mInstallLock. Identity cannot change while parsing,
            // committing and issuing the receipt, without holding mUserLock across slow I/O.
            InstallResult result = installPackageAsUserLocked(file, option, userId, expectedPackageName, preparedSource);
            BUserInfo after = sUserManager.getUserInfo(userId);
            if (result.success) {
                if (after == null || after.createTime <= 0
                        || (expectedUserCreateTime > 0 && after.createTime != expectedUserCreateTime)) {
                    return result.installError("The committed user identity could not be verified");
                }
                return result.installSuccess(result.packageName, userId, after.createTime);
            }
            // Failed imports must not publish an empty, newly allocated profile. Never
            // remove a profile with a committed package, even if another install check failed.
            if (before == null && after != null && !hasAnyInstalledPackage(userId)) {
                try {
                    if (!sUserManager.deleteUserIfMatches(userId, after.createTime, true)) {
                        return result.installError(result.msg + "; empty user cleanup was not confirmed");
                    }
                } catch (Exception cleanupError) {
                    return result.installError(result.msg + "; empty user cleanup failed: " + cleanupError.getMessage());
                }
            }
            return result;
        }
    }

    /** Raw persisted installation flags, never a filtered/possibly failed UI projection. */
    public boolean hasAnyInstalledPackage(int userId) {
        synchronized (mPackages) {
            // An incomplete scan is not proof that a profile is empty. This method
            // gates automatic pruning as well as failed-import cleanup.
            if (!mSettings.lifecycleMetadataReadable()) return true;
            return InstallRequestPolicy.hasInstalledPackages(mPackages.values(), userId);
        }
    }

    /** Display-only summary. The deletion/pruning guard above deliberately keeps its raw semantics. */
    public java.util.Set<Integer> getCloneGroupUserIds() {
        synchronized (mPackages) {
            mSettings.requireLifecycleMetadataReadable();
            return CloneGroupSummary.populatedUsers(mPackages, packageName ->
                    !packageName.equals(NexBoxCore.getHostPkg()) && !packageName.equals("com.nexbox.gallery")
                            && !com.nexbox.core.core.env.AppSystemEnv.isBlackPackage(packageName));
        }
    }

    public void requireLifecycleMetadataReadable() {
        mSettings.requireLifecycleMetadataReadable();
    }

    @Override
    public Bundle getRawPackageInventory(int userId, long createTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            try {
                Map<String, String> entries = rawInventoryLocked(userId, createTime);
                Bundle result = new Bundle();
                result.putBoolean("success", true);
                result.putInt("userId", userId);
                result.putLong("createTime", createTime);
                result.putBoolean("complete", true);
                result.putString("inventoryToken", mInventory.issue(userId, createTime, entries,
                        android.os.SystemClock.elapsedRealtime()));
                result.putLong("expiresAfterMillis", InventorySnapshotStore.RETENTION_MILLIS);
                result.putParcelableArrayList("packages", inventoryEntries(entries));
                return result;
            } catch (com.nexbox.core.utils.MutationReceipt.Rejected failure) {
                Bundle result = new Bundle();
                result.putBoolean("success", false);
                result.putBoolean("complete", false);
                result.putString("errorCode", failure.code);
                return result;
            }
        }
    }

    /** Recompute within the actual mutation's lifecycle section, never at the host UI boundary. */
    public Map<String, String> requireInventoryLocked(int userId, long createTime, String token) {
        Map<String, String> entries = rawInventoryLocked(userId, createTime);
        mInventory.require(token, userId, createTime, entries, android.os.SystemClock.elapsedRealtime());
        return entries;
    }

    Map<String, String> rawInventoryLocked(int userId, long createTime) {
        if (!Thread.holdsLock(mInstallLock)) throw new IllegalStateException("Inventory requires lifecycle lock");
        try {
            if (!matchesExistingUser(userId, createTime)) {
                throw new com.nexbox.core.utils.MutationReceipt.Rejected("stale_user");
            }
            requireLifecycleMetadataReadable();
            RawInventoryCollector result = new RawInventoryCollector();
            synchronized (mPackages) {
                for (Map.Entry<String, BPackageSettings> entry : mPackages.entrySet()) {
                    String name = entry.getKey();
                    BPackageSettings settings = entry.getValue();
                    if (settings == null || settings.pkg == null) {
                        throw new com.nexbox.core.utils.MutationReceipt.Rejected("invalid_inventory_identity");
                    }
                    result.identity(name, settings.pkg.packageName);
                    if (settings.userState == null || settings.userState.containsKey(null)
                            || settings.userState.containsValue(null) || settings.installOption == null || settings.appId <= 0) {
                        throw new com.nexbox.core.utils.MutationReceipt.Rejected("inventory_unreadable");
                    }
                    if (!settings.getInstalled(userId)) continue;
                    String version = mInventory.version(userId, createTime, name, settings, settings.pkg,
                            settings.userState.get(userId), inventoryCodeRevision(settings.pkg));
                    result.installed(name, version);
                }
            }
            return result.snapshot();
        } catch (com.nexbox.core.utils.MutationReceipt.Rejected rejected) {
            throw rejected;
        } catch (RuntimeException failure) {
            throw new com.nexbox.core.utils.MutationReceipt.Rejected("inventory_unreadable");
        }
    }

    private String inventoryCodeRevision(BPackage pkg) {
        StringBuilder state = new StringBuilder().append(pkg.getLongVersionCode());
        ArrayList<String> paths = new ArrayList<>();
        paths.add(pkg.baseCodePath);
        if (pkg.applicationInfo != null) {
            paths.add(pkg.applicationInfo.sourceDir);
            if (pkg.applicationInfo.splitSourceDirs != null) {
                Collections.addAll(paths, pkg.applicationInfo.splitSourceDirs);
            }
        }
        for (String path : paths) {
            state.append('|').append(path);
            if (path == null) continue; // Raw identity survives an unavailable PackageInfo projection.
            try {
                android.system.StructStat stat = android.system.Os.stat(path);
                state.append(':').append(stat.st_dev).append(':').append(stat.st_ino)
                        .append(':').append(stat.st_size).append(':').append(stat.st_mtim.tv_sec)
                        .append(':').append(stat.st_mtim.tv_nsec).append(':').append(stat.st_ctim.tv_sec)
                        .append(':').append(stat.st_ctim.tv_nsec);
            } catch (android.system.ErrnoException failure) {
                if (failure.errno != android.system.OsConstants.ENOENT) {
                    throw new com.nexbox.core.utils.MutationReceipt.Rejected("inventory_unreadable");
                }
                state.append(":absent");
            }
        }
        return state.toString();
    }

    public static ArrayList<Bundle> inventoryEntries(Map<String, String> entries) {
        ArrayList<Bundle> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            Bundle value = new Bundle();
            value.putString("packageName", entry.getKey());
            value.putString("installationVersion", entry.getValue());
            result.add(value);
        }
        return result;
    }

    @Override
    public Bundle installGmsIfInventory(int userId, long createTime, String token,
                                        boolean requireTraceFree, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return new ConditionalGmsLifecycle(this).install(userId, createTime, token, requireTraceFree, requestId);
    }

    @Override
    public Bundle uninstallGmsIfInventory(int userId, long createTime, String token,
                                          List<Bundle> expectedComponents, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return new ConditionalGmsLifecycle(this).remove(userId, createTime, token, expectedComponents, requestId, false);
    }

    public Bundle resetGmsIfInventory(int userId, long createTime, String token,
                                      List<Bundle> expectedComponents, String requestId) {
        return new ConditionalGmsLifecycle(this).remove(userId, createTime, token, expectedComponents, requestId, true);
    }

    private boolean isPackageInstalledRaw(String packageName, int userId) {
        synchronized (mPackages) {
            BPackageSettings settings = mPackages.get(packageName);
            return settings != null && settings.getInstalled(userId);
        }
    }

    private boolean matchesExistingUser(int userId, long expectedCreateTime) {
        if (userId < 0 || expectedCreateTime <= 0) return false;
        synchronized (sUserManager.mUserLock) {
            BUserInfo user = sUserManager.getUserInfo(userId);
            return user != null && user.createTime == expectedCreateTime;
        }
    }

    /** Add a user installation without copying an already-owned APK onto itself. */
    @Override
    public InstallResult installExistingPackageAsUser(String packageName, int userId) {
        XposedCallerGuard.enforceHostManager();
        InstallResult result = new InstallResult();
        result.packageName = packageName;
        if (userId < 0 || !sUserManager.exists(userId)) return result.installError("Virtual user does not exist");
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null || ps.pkg == null || !new File(ps.pkg.baseCodePath).isFile()) {
                    return result.installError("Package has not been imported into NexBox");
                }
                if (ps.getInstalled(userId)) return result.installSuccess(packageName);
                mInventory.invalidate(packageName);
                int status = new CreateUserExecutor().exec(ps, ps.installOption, userId);
                if (status != 0) return result.installError("Could not create virtual user data");
                ps.setInstalled(true, userId);
                if (!ps.save()) {
                    ps.removeUser(userId);
                    return result.installError("Could not persist virtual user installation");
                }
                onPackageInstalled(packageName, userId);
                return result.installSuccess(packageName);
            }
        }
    }

    /** Defensive internal snapshot, with no host split fallback or caller-supplied paths. */
    public PackageInfo getInstalledPackageSnapshot(String packageName, int userId) {
        if (userId < 0) return null;
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return null;
        PackageQueryContext request = PackageQueryContext.raw(PackageManager.GET_META_DATA, userId, user.createTime);
        PackageQueryContext.Snapshot snapshot = capturePackageQuery(request, packageName);
        requireQueryUser(userId, request.userCreateTime);
        PackageInfo result = snapshot == null ? null : generateInstalledSnapshot(snapshot, userId);
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    private PackageInfo generateInstalledSnapshot(PackageQueryContext.Snapshot snapshot, int userId) {
        if (!snapshot.state.installed || snapshot.application == null) return null;
        BPackage code = snapshot.code;
        PackageInfo info = new PackageInfo();
        info.packageName = code.packageName;
        info.versionName = code.mVersionName;
        info.setLongVersionCode(code.getLongVersionCode());
        ApplicationInfo application = PackageQueryContext.copyApplication(snapshot.application);
        application.packageName = code.packageName;
        application.sourceDir = code.baseCodePath;
        application.publicSourceDir = code.baseCodePath;
        application.splitPublicSourceDirs = application.splitSourceDirs == null ? null : application.splitSourceDirs.clone();
        application.metaData = snapshot.appMetadata == null ? null : new Bundle(snapshot.appMetadata);
        application.dataDir = BEnvironment.getDataDir(code.packageName, userId).getAbsolutePath();
        application.deviceProtectedDataDir = BEnvironment.getDeDataDir(code.packageName, userId).getAbsolutePath();
        application.uid = BUserHandle.getUid(userId, snapshot.appId);
        if (!snapshot.systemInstall) {
            PackageUpdateStore.configureNativeLibraryPaths(code, application);
        }
        info.applicationInfo = application;
        return info;
    }

    public List<PackageInfo> getInstalledPackageSnapshots(int userId) {
        if (userId < 0) return Collections.emptyList();
        BUserInfo user = sUserManager.getUserInfo(userId);
        if (user == null) return Collections.emptyList();
        PackageQueryContext request = PackageQueryContext.raw(PackageManager.GET_META_DATA, userId, user.createTime);
        List<PackageQueryContext.Snapshot> snapshots = captureInstalledQuery(request, false, true);
        requireQueryUser(userId, request.userCreateTime);
        ArrayList<PackageInfo> result = new ArrayList<>(snapshots.size());
        for (PackageQueryContext.Snapshot snapshot : snapshots) {
            PackageInfo info = generateInstalledSnapshot(snapshot, userId);
            if (info != null) result.add(info);
        }
        requireQueryUser(userId, request.userCreateTime);
        return result;
    }

    @Override
    public void uninstallPackageAsUser(String packageName, int userId) throws RemoteException {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mInstallLock) {
            uninstallPackageForUserLocked(packageName, userId, false);
        }
    }

    @Override
    public boolean uninstallPackageAsUserIfMatches(String packageName, int userId, long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime)) return false;
            try { return uninstallPackageForUserLocked(packageName, userId, false); }
            catch (Exception error) {
                Slog.w(TAG, "Could not uninstall the requested user package", error);
                return false;
            }
        }
    }

    private boolean uninstallPackageForUserLocked(String packageName, int userId, boolean removeStaleState) {
        BPackageSettings settings;
        BPackageUserState previous;
        boolean removeApp;
        synchronized (mPackages) {
            settings = mPackages.get(packageName);
            if (settings == null) return false;
            previous = settings.userState.get(userId);
            if (previous == null || (!removeStaleState && !previous.installed)) return false;
            removeApp = settings.userState.size() <= 1;
        }
        // Process initialization also queries package state: do not kill while holding
        // mPackages, which would invert package/process lock order.
        mInventory.invalidate(packageName);
        GuestPackageInstallerService.get().removeOwnerSessions(userId, packageName);
        com.nexbox.core.core.system.am.VirtualPendingIntents.removeOwner(userId, packageName);
        com.nexbox.core.media.PrivateDocuments.removeOwner(userId, packageName);
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
        int status = BPackageInstallerService.get().uninstallPackageAsUser(settings, removeApp, userId);
        if (status != 0) throw new IllegalStateException("Could not remove package data");
        synchronized (mPackages) {
            if (mPackages.get(packageName) != settings) {
                throw new IllegalStateException("Package state changed during removal");
            }
            if (removeApp) {
                mSettings.removePackage(packageName);
                mComponentResolver.removeAllComponents(settings.pkg);
            } else {
                settings.removeUser(userId);
                if (!settings.save()) {
                    settings.userState.put(userId, previous);
                    throw new IllegalStateException("Could not persist package user removal");
                }
            }
        }
        onPackageUninstalled(packageName, removeApp, userId);
        return true;
    }

    @Override
    public void uninstallPackage(String packageName) {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mInstallLock) {
            requireLifecycleMetadataReadable();
            List<Integer> owners;
            synchronized (mPackages) {
                BPackageSettings ps = mPackages.get(packageName);
                if (ps == null) return;
                owners = ps.getUserIds();
                if (owners.isEmpty()) throw new IllegalStateException("Package has no verifiable user state");
            }
            // Reuse the per-owner cleanup/receipt path. A failed owner must keep its
            // registry entry; shared code is removed only after the last owner succeeds.
            // Process retirement also queries packages, so never hold mPackages here.
            for (Integer userId : owners) {
                if (!uninstallPackageForUserLocked(packageName, userId, true)) {
                    throw new IllegalStateException("Package removal was not confirmed for every user");
                }
            }
        }
    }

    @Override
    public void clearPackage(String packageName, int userId) {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mInstallLock) { clearPackageLocked(packageName, userId); }
    }

    @Override
    public boolean clearPackageIfMatches(String packageName, int userId, long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime)) return false;
            try { return clearPackageLocked(packageName, userId); }
            catch (Exception error) {
                Slog.w(TAG, "Could not clear the requested user package", error);
                return false;
            }
        }
    }

    private boolean clearPackageLocked(String packageName, int userId) {
        BPackageSettings settings;
        synchronized (mPackages) {
            settings = mPackages.get(packageName);
            if (settings == null || !settings.getInstalled(userId) || !sUserManager.exists(userId)) return false;
        }
        mInventory.invalidate(packageName);
        GuestPackageInstallerService.get().removeOwnerSessions(userId, packageName);
        com.nexbox.core.core.system.am.VirtualPendingIntents.removeOwner(userId, packageName);
        com.nexbox.core.media.PrivateDocuments.removeOwner(userId, packageName);
        BProcessManagerService.get().killPackageAsUser(packageName, userId);
        return BPackageInstallerService.get().clearPackage(settings, userId) == 0;
    }

    @Override
    public void stopPackage(String packageName, int userId) {
        int pid = Binder.getCallingPid();
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(pid);
        if (caller == null) {
            XposedCallerGuard.enforceHostManagerOrCore();
            synchronized (mInstallLock) {
                BProcessManagerService.get().killPackageAsUser(packageName, userId);
            }
            return;
        }
        synchronized (mInstallLock) {
            BUserInfo user = sUserManager.getUserInfo(userId);
            if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller.userId != userId
                    || !caller.getPackageName().equals(packageName) || user == null
                    || caller.userGeneration != user.createTime
                    || BProcessManagerService.get().findProcessByPid(pid) != caller
                    || caller.bActivityThread == null || !caller.bActivityThread.asBinder().isBinderAlive()) {
                Slog.w(TAG, "action=stopPackage stage=rejected reason=caller_or_generation userId=" + userId);
                throw new SecurityException("A virtual application may stop only its own current instance");
            }
            // Auto-freeze runs inside the guest. Keep that path, while refusing to kill
            // an out-of-scope package that happens to share its process/virtual UID.
            if (!BProcessManagerService.get().stopPackagesIfMatches(
                    Collections.singletonList(packageName), userId, caller.userGeneration)) {
                throw new IllegalStateException("Application stop could not be confirmed");
            }
        }
    }

    @Override
    public boolean stopPackageIfMatches(String packageName, int userId, long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime)) return false;
            synchronized (mPackages) {
                BPackageSettings settings = mPackages.get(packageName);
                if (settings == null || !settings.getInstalled(userId)) return false;
            }
            return BProcessManagerService.get().stopPackagesIfMatches(
                    java.util.Collections.singletonList(packageName), userId, expectedUserCreateTime);
        }
    }

    /** Configuration updates provide the complete scope so shared processes can be checked atomically. */
    @Override
    public boolean stopPackagesIfMatches(List<String> packageNames, int userId, long expectedUserCreateTime) {
        XposedCallerGuard.enforceHostManager();
        synchronized (mInstallLock) {
            if (!matchesExistingUser(userId, expectedUserCreateTime) || packageNames == null) return false;
            List<String> targets = new ArrayList<>(new java.util.LinkedHashSet<>(packageNames));
            for (String packageName : targets) {
                if (packageName == null || packageName.equals(NexBoxCore.getHostPkg())) return false;
                ApplicationInfo app = getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId);
                if (app == null) return false;
            }
            return BProcessManagerService.get().stopPackagesIfMatches(targets, userId, expectedUserCreateTime);
        }
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mInstallLock) {
            requireLifecycleMetadataReadable();
            GuestPackageInstallerService.get().removeOwnerSessions(userId, null);
            List<String> names;
            synchronized (mPackages) { names = new ArrayList<>(mPackages.keySet()); }
            // Also remove orphan/disabled records before reusing an absent numeric id.
            // The display-facing isInstalled/exists checks intentionally do not apply here.
            for (String name : names) uninstallPackageForUserLocked(name, userId, true);
            mInventory.forgetUser(userId);
        }
    }

    @Override
    public boolean isInstalled(String packageName, int userId) {
        if (!sUserManager.exists(userId)) return false;
        synchronized (mPackages) {
            BPackageSettings ps = mPackages.get(packageName);
            if (ps == null)
                return false;
            return ps.getInstalled(userId);
        }
    }

    @Override
    public List<InstalledPackage> getInstalledPackagesAsUser(int userId) {
        if (!sUserManager.exists(userId)) return Collections.emptyList();
        synchronized (mPackages) {
            List<InstalledPackage> installedPackages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                if (ps.getInstalled(userId) && !GmsCore.isGoogleAppOrService(ps.pkg.packageName)) {
                    InstalledPackage installedPackage = new InstalledPackage();
                    installedPackage.userId = userId;
                    installedPackage.packageName = ps.pkg.packageName;
                    installedPackages.add(installedPackage);
                }
            }
            return installedPackages;
        }
    }

    /** Reset must include hidden components and must not depend on PackageInfo generation. */
    public List<String> getInstalledGmsPackagesForUser(int userId) {
        synchronized (mInstallLock) {
            requireLifecycleMetadataReadable();
            if (!sUserManager.exists(userId)) {
                throw new IllegalStateException("The GMS profile no longer exists");
            }
            synchronized (mPackages) {
                List<String> packages = new ArrayList<>();
                for (BPackageSettings settings : mPackages.values()) {
                    if (settings == null || settings.pkg == null || settings.pkg.packageName == null) {
                        throw new IllegalStateException("Installed package identity is unavailable");
                    }
                    if (settings.getInstalled(userId)
                            && GmsCore.isGmsEnvironmentComponent(settings.pkg.packageName)) {
                        packages.add(settings.pkg.packageName);
                    }
                }
                Collections.sort(packages);
                return packages;
            }
        }
    }

    @Override
    public String[] getPackagesForUid(int uid, int userId) throws RemoteException {
        if (!sUserManager.exists(userId)) return new String[]{};
        synchronized (mPackages) {
            List<String> packages = new ArrayList<>();
            for (BPackageSettings ps : mPackages.values()) {
                String packageName = ps.pkg.packageName;
                if (ps.getInstalled(userId) && getAppId(packageName) == uid) {
                    packages.add(packageName);
                }
            }
            if (packages.isEmpty()) {
                ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(getCallingPid());
                if (processByPid != null) {
                    packages.add(processByPid.getPackageName());
                }
            }
            return packages.toArray(new String[]{});
        }
    }

    private InstallResult installPackageAsUserLocked(String file, InstallOption option, int userId) {
        return installPackageAsUserLocked(file, option, userId, null);
    }

    private InstallResult installPackageAsUserLocked(String file, InstallOption option, int userId,
                                                     String expectedPackageName) {
        return installPackageAsUserLocked(file, option, userId, expectedPackageName, null);
    }

    /** Conditional user deletion shares the ordinary package removal implementation. */
    public void deleteUserWithReceipt(int userId, com.nexbox.core.utils.MutationReceipt receipt) {
        if (!Thread.holdsLock(mInstallLock)) throw new IllegalStateException("Deletion requires lifecycle lock");
        requireLifecycleMetadataReadable();
        receipt.begin("close_installer_sessions");
        GuestPackageInstallerService.get().removeOwnerSessions(userId, null);
        receipt.confirmed("installerSessionsClosed");
        List<String> names;
        synchronized (mPackages) { names = new ArrayList<>(mPackages.keySet()); }
        for (String name : names) {
            synchronized (mPackages) {
                BPackageSettings settings = mPackages.get(name);
                if (settings == null || !settings.userState.containsKey(userId)) continue;
            }
            receipt.begin("remove_user_package");
            if (!uninstallPackageForUserLocked(name, userId, true)) {
                throw new IllegalStateException("Package removal was not confirmed");
            }
            Bundle entry = new Bundle();
            entry.putString("packageName", name);
            receipt.confirmed("packageRemoved", entry);
        }
        mInventory.forgetUser(userId);
    }

    private InstallResult installPackageAsUserLocked(String file, InstallOption option, int userId,
                                                     String expectedPackageName, PreparedCloneSource preparedSource) {
        long l = System.currentTimeMillis();
        InstallResult result = new InstallResult();
        File apkFile = null;
        File stagedFile = null;
        ApkBundle.Prepared preparedBundle = null;
        String stage = "sourceValidate";
        try {
            requireLifecycleMetadataReadable();
            if (option == null || TextUtils.isEmpty(file)) {
                return result.installError("An installation source and options are required");
            }
            String sourceError = ApkSourcePolicy.sourceError(option.isFlag(InstallOption.FLAG_SYSTEM),
                    option.isFlag(InstallOption.FLAG_STORAGE), option.isFlag(InstallOption.FLAG_URI_FILE));
            if (sourceError != null) return result.installError(sourceError);
            Slog.i(TAG, "installPackageAsUserLocked: userId=" + userId
                    + " uriFile=" + option.isFlag(InstallOption.FLAG_URI_FILE)
                    + " source=" + (option.isFlag(InstallOption.FLAG_SYSTEM) ? "host" : "document"));
            if (expectedPackageName == null && !sUserManager.exists(userId)) {
                sUserManager.createUser(userId);
            }
            if (expectedPackageName == null && !sUserManager.exists(userId)) {
                return result.installError("Could not create the target virtual user");
            }
            if (option.isFlag(InstallOption.FLAG_URI_FILE)) {
                stage = "documentStage";
                Uri uri = Uri.parse(file);
                stagedFile = new File(BEnvironment.getCacheDir(), UUID.randomUUID() + ".apk");
                try (InputStream input = NexBoxCore.getContext().getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(stagedFile)) {
                    if (input == null) return result.installError("[documentStage] The document provider returned no data");
                    byte[] buffer = new byte[64 * 1024];
                    long copied = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        copied += read;
                        if (copied > ApkBundle.MAX_DOCUMENT_BYTES) throw new IOException("APK document exceeds the 4 GiB import limit");
                        output.write(buffer, 0, read);
                    }
                }
                Slog.i(TAG, "action=import stage=documentStaged bytes=" + stagedFile.length());
            } else {
                stagedFile = new File(file);
            }

            if (stagedFile == null) {
                return result.installError("install source is null");
            }

            stage = "sourceInspect";
            File parseSource = stagedFile;
            if (preparedSource != null) {
                preparedSource.verifyUnchanged();
                apkFile = preparedSource.baseApk;
                parseSource = preparedSource.parseSource;
            } else if (ApkSourcePolicy.isApksBundle(stagedFile)) {
                if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
                    return result.installError("An installed-package source must be the host package's APK, not an APK bundle");
                }
                stage = "bundlePrepare";
                String[] supportedAbis = NexBoxCore.is64Bit()
                        ? android.os.Build.SUPPORTED_64_BIT_ABIS : android.os.Build.SUPPORTED_32_BIT_ABIS;
                preparedBundle = ApkBundle.prepare(stagedFile, BEnvironment.getCacheDir(), supportedAbis,
                        (step, details) -> Slog.i(TAG, "action=import stage=" + step + " " + details));
                apkFile = preparedBundle.baseApk;
                parseSource = preparedBundle.packageDirectory;
            } else {
                apkFile = stagedFile;
            }
            stage = "packageParse";
            // Parse the selected directory as one Android package, not a base APK with
            // guessed split paths. This merges feature manifests, dependencies and names.
            PackageParser.Package aPackage = parserApk(parseSource.getAbsolutePath(),
                    option.isFlag(InstallOption.FLAG_SYSTEM));
            if (aPackage == null || aPackage.applicationInfo == null) {
                return result.installError("[packageParse] Android could not parse the APK/split set");
            }
            String packageName = aPackage.packageName;
            if (TextUtils.isEmpty(packageName)) {
                return result.installError("The APK does not declare a package name");
            }
            String hostPackageName = NexBoxCore.getHostPkg();
            if (packageName.equals(hostPackageName)) {
                return result.installError("Cannot clone the host into its own container.");
            }

            boolean support = AbiUtils.isSupport(apkFile);
            if (!support) {
                return result.installError(packageName,
                        "[abiValidate] " + (NexBoxCore.is64Bit() ? "The box does not support 32-bit applications" : "The box does not support 64-bit applications"));
            }
            result.packageName = aPackage.packageName;
            if (TextUtils.isEmpty(result.packageName)) {
                return result.installError("The parsed APK does not declare a package name");
            }
            if (expectedPackageName != null) {
                String error = InstallRequestPolicy.packageError(expectedPackageName, result.packageName,
                        sUserManager.exists(userId) && isPackageInstalledRaw(result.packageName, userId));
                if (error != null) return result.installError(error);
                // Validate the package before allocating a new user or changing shared APKs.
                if (preparedSource != null) preparedSource.checkActive();
                if (!sUserManager.exists(userId)) sUserManager.createUser(userId);
                if (!sUserManager.exists(userId)) return result.installError("Could not create the target virtual user");
            }

            if (option.isFlag(InstallOption.FLAG_SYSTEM)) {
                // Only a real host installation may supply its ApplicationInfo/native and
                // split paths. External APKs retain their parsed metadata and are copied by
                // FLAG_STORAGE; never query the host for an embedded Dynamite module.
                PackageInfo installedSource = NexBoxCore.getPackageManager().getPackageInfo(aPackage.packageName, 0);
                if (installedSource.applicationInfo == null
                        || TextUtils.isEmpty(installedSource.applicationInfo.sourceDir)
                        || !apkFile.getCanonicalFile().equals(new File(installedSource.applicationInfo.sourceDir).getCanonicalFile())) {
                    return result.installError(aPackage.packageName,
                            "The source APK no longer matches the host installation; select the app again");
                }
                if (preparedSource != null) preparedSource.verifyInstalledSource(installedSource.applicationInfo);
                aPackage.applicationInfo = installedSource.applicationInfo;
            }
            if (option.isFlag(InstallOption.FLAG_SYSTEM) && expectedPackageName != null) {
                if (preparedSource != null) preparedSource.checkActive();
                BPackageSettings shared;
                synchronized (mPackages) { shared = mPackages.get(packageName); }
                if (shared != null && !shared.getInstalled(userId)) {
                    PackageUpdateStore.requireSameSigners(shared.pkg.mSignatures,
                            aPackage.mSigningDetails == null ? null : aPackage.mSigningDetails.signatures);
                    // Adding a profile must not overwrite a Play-updated shared APK with
                    // the (possibly older) host source, or alter other profiles' code.
                    return installExistingPackageAsUser(packageName, userId);
                }
            }
            if (preparedSource != null) preparedSource.checkActive();
            mInventory.invalidate(aPackage.packageName);
            BPackageSettings bPackageSettings = mSettings.getPackageLPw(aPackage.packageName, aPackage, option);

            
            BProcessManagerService.get().killPackageAsUser(aPackage.packageName, userId);

            stage = "packageCopy";
            int i = BPackageInstallerService.get().installPackageAsUser(bPackageSettings, userId);
            if (i != 0) {
                return result.installError("Package installation failed with status " + i);
            }
            stage = "packagePersist";
            synchronized (mPackages) {
                boolean previouslyInstalled = bPackageSettings.getInstalled(userId);
                bPackageSettings.setInstalled(true, userId);
                if (!bPackageSettings.save()) {
                    bPackageSettings.setInstalled(previouslyInstalled, userId);
                    return result.installError("Could not persist the installed package");
                }
            }
            if (!sUserManager.exists(userId)) {
                return result.installError("The target virtual user no longer exists");
            }
            synchronized (mPackages) {
                stage = "packageReload";
                mSettings.scanPackage(aPackage.packageName, mComponentResolver::addAllComponents);
                // A failed scan can retain the previous record. Do not publish that stale
                // graph, or the pre-scan graph, as the newly committed installation.
                BPackageSettings installed = mPackages.get(aPackage.packageName);
                if (!mSettings.lifecycleMetadataReadable() || installed == null || installed.pkg == null
                        || !installed.getInstalled(userId)
                        || !new File(installed.pkg.baseCodePath).isFile()) {
                    return result.installError("The installed package could not be verified for the target user");
                }
            }
            Slog.i(TAG, "action=import stage=installed package=" + aPackage.packageName
                    + " splits=" + (preparedSource != null ? preparedSource.splitCount
                            : preparedBundle == null ? 0 : preparedBundle.splitCount)
                    + " installer=com.android.vending");
            onPackageInstalled(bPackageSettings.pkg.packageName, userId);
            return result.installSuccess(aPackage.packageName);
        } catch (Throwable t) {
            Slog.e("BPackageManagerService", "action=import stage=" + stage + " success=false", t);
            String detail = t.getMessage();
            return result.installError("Installation failed [" + stage + "]: " + t.getClass().getSimpleName()
                    + (TextUtils.isEmpty(detail) ? "" : ": " + detail));
        } finally {
            if (stagedFile != null && option != null && option.isFlag(InstallOption.FLAG_URI_FILE)) {
                BzFileUtils.deleteDir(stagedFile);
            }
            if (preparedBundle != null) {
                try { preparedBundle.close(); }
                catch (IOException error) { Slog.w(TAG, "action=import stage=bundleCleanup success=false", error); }
            }
            Slog.d(TAG, "install finish: " + (System.currentTimeMillis() - l) + "ms");
        }
    }

    interface PreparedUpdateListener { void prepared(String codePath) throws IOException; }

    /** Caller holds the lifecycle lock and has authenticated the session owner. */
    void commitSessionUpdate(String packageName, File cluster, int userId, long generation,
                             long requiredVersion, PreparedUpdateListener receipt) throws Throwable {
        synchronized (mInstallLock) {
            requireLifecycleMetadataReadable();
            if (Objects.equals(packageName, NexBoxCore.getHostPkg())) throw new SecurityException("Cannot update the container host");
            BUserInfo user = sUserManager.getUserInfo(userId);
            if (user == null || user.createTime != generation) throw new SecurityException("Stale installer user");
            BPackageSettings previous;
            synchronized (mPackages) { previous = mPackages.get(packageName); }
            if (previous == null || !previous.getInstalled(userId)) {
                throw new SecurityException("Only an existing container application can be updated");
            }
            if (requiredVersion != -1 && PackageUpdateStore.version(previous.pkg.baseCodePath) != requiredVersion) {
                throw new IllegalArgumentException("Installed package version changed since the update was requested");
            }
            mInventory.invalidate(packageName);
            BPackageSettings next = PackageUpdateStore.prepare(previous, cluster);
            boolean persisted = false;
            // Never hold the package map across the process lock. Process startup
            // queries package metadata in the opposite order.
            BProcessManagerService.get().killAllByPackageName(packageName);
            synchronized (mPackages) {
                try {
                    // Journal the candidate before the atomic pointer switch so a server
                    // restart can distinguish a committed update from abandoned staging.
                    receipt.prepared(next.pkg.baseCodePath);
                    if (!next.save()) throw new IOException("Cannot persist updated package");
                    persisted = true;
                    mSettings.scanPackage(packageName, mComponentResolver::addAllComponents);
                    BPackageSettings actual = mPackages.get(packageName);
                    if (!mSettings.lifecycleMetadataReadable() || actual == null
                            || !next.pkg.baseCodePath.equals(actual.pkg.baseCodePath)
                            || !actual.getInstalled(userId) || !new File(actual.pkg.baseCodePath).isFile()) {
                        throw new IOException("Updated package metadata could not be reloaded");
                    }
                } catch (Throwable failure) {
                    // A crash at any point leaves package.conf referring to a complete
                    // immutable generation. Never remove code if rollback persistence fails.
                    if (persisted && !previous.save()) {
                        throw new IOException("Update requires metadata recovery; both code generations retained", failure);
                    }
                    if (persisted) {
                        mSettings.scanPackage(packageName, mComponentResolver::addAllComponents);
                        BPackageSettings restored = mPackages.get(packageName);
                        if (!mSettings.lifecycleMetadataReadable() || restored == null
                                || !previous.pkg.baseCodePath.equals(restored.pkg.baseCodePath)) {
                            throw new IOException("Update rollback requires metadata recovery; both code generations retained", failure);
                        }
                    }
                    try { PackageUpdateStore.discard(next.pkg); }
                    catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                    throw failure;
                }
            }
            PackageUpdateStore.prune(next.pkg, previous.pkg);
            for (Integer installedUser : next.getUserIds()) {
                if (next.getInstalled(installedUser)) {
                    try { onPackageInstalled(packageName, installedUser); }
                    catch (Throwable observerFailure) { Slog.w(TAG, "Update observer failed after commit", observerFailure); }
                    BUserInfo owner = sUserManager.getUserInfo(installedUser);
                    if (owner != null) {
                        long ownerGeneration = owner.createTime;
                        int uid = BUserHandle.getUid(installedUser, next.appId);
                        NexBoxCore.get().getHandler().post(() -> {
                            com.nexbox.core.core.system.am.BActivityManagerService broadcasts =
                                    com.nexbox.core.core.system.am.BActivityManagerService.get();
                            try {
                                for (String action : new String[]{Intent.ACTION_PACKAGE_REMOVED,
                                        Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED}) {
                                    Intent event = new Intent(action, Uri.parse("package:" + packageName))
                                            .putExtra(Intent.EXTRA_REPLACING, true)
                                            .putExtra(Intent.EXTRA_DATA_REMOVED, false)
                                            .putExtra(Intent.EXTRA_UID, uid);
                                    broadcasts.sendSystemBroadcast(event, installedUser, ownerGeneration);
                                }
                                broadcasts.sendSystemBroadcast(new Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
                                        .setPackage(packageName), installedUser, ownerGeneration);
                            } catch (RuntimeException failure) {
                                Slog.w(TAG, "Could not deliver virtual package update events", failure);
                            }
                        });
                    }
                }
            }
        }
    }

    private PackageParser.Package parserApk(String file, boolean trustedHostSource) throws Throwable {
        PackageParser parser = PackageParserCompat.createParser(new File(file));
        PackageParser.Package parsed = PackageParserCompat.parsePackage(parser, new File(file), 0);
        if (parsed == null) return null;
        if (parsed.applicationInfo != null) {
            // PackageParser stores cluster paths on Package, but does not populate every
            // ApplicationInfo path until the system install phase we are replacing here.
            parsed.applicationInfo.sourceDir = parsed.baseCodePath;
            parsed.applicationInfo.publicSourceDir = parsed.baseCodePath;
            parsed.applicationInfo.splitNames = parsed.splitNames;
            parsed.applicationInfo.splitSourceDirs = parsed.splitCodePaths;
            parsed.applicationInfo.splitPublicSourceDirs = parsed.splitCodePaths;
        }
        // Imported base and splits must form one signed package. Do not skip verification
        // merely because the untrusted document was copied into our private cache.
        PackageParserCompat.collectCertificates(parser, parsed, 0, trustedHostSource);
        return parsed;
    }

    static String fixProcessName(String defProcessName, String processName) {
        return ComponentResolver.fixProcessName(defProcessName, processName);
    }

    
    private int updateFlags(int flags, int userId) {
        if ((flags & (PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DIRECT_BOOT_AWARE)) != 0) {
            
            
            
        } else {
            
            flags |= PackageManager.MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
        }
        return flags;
    }

    public int getAppId(String packageName) {
        BPackageSettings bPackageSettings = mPackages.get(packageName);
        if (bPackageSettings != null)
            return bPackageSettings.appId;
        return -1;
    }

    Settings getSettings() {
        return mSettings;
    }

    public void addPackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.add(monitor);
    }

    public void removePackageMonitor(PackageMonitor monitor) {
        mPackageMonitors.remove(monitor);
    }

    void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        com.nexbox.core.core.system.am.VirtualPendingIntents.removeOwner(userId, packageName);
        com.nexbox.core.media.PrivateDocuments.removeOwner(userId, packageName);
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageUninstalled(packageName, isRemove, userId);
        }
        Slog.d(TAG, "onPackageUninstalled: " + packageName + ", userId: " + userId);
    }

    void onPackageInstalled(String packageName, int userId) {
        for (PackageMonitor packageMonitor : mPackageMonitors) {
            packageMonitor.onPackageInstalled(packageName, userId);
        }
        Slog.d(TAG, "onPackageInstalled: " + packageName + ", userId: " + userId);
    }

    public BPackageSettings getBPackageSetting(String packageName) {
        return mPackages.get(packageName);
    }

    public List<BPackageSettings> getBPackageSettings() {
        return new ArrayList<>(mPackages.values());
    }

    @Override
    public void systemReady() {
        synchronized (mInstallLock) {
            synchronized (mPackages) {
                mSettings.scanPackage(mComponentResolver::addAllComponents);
            }
        }
    }
}
