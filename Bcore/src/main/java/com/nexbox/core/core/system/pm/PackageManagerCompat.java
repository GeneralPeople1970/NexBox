package com.nexbox.core.core.system.pm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;

import java.lang.reflect.Method;
import java.util.Objects;

import black.android.content.pm.BRApplicationInfoL;
import black.android.content.pm.BRApplicationInfoN;
import black.android.content.pm.BRPackageParserSigningDetails;
import black.android.content.pm.BRSigningInfo;
import black.android.content.res.BRAssetManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.utils.BzArrayUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.PackageSignaturesCompat;


@SuppressLint({"SdCardPath", "NewApi"})
public class PackageManagerCompat {
    private static final Object SIGNING_INFO_LOCK = new Object();

    public static PackageInfo generatePackageInfo(BPackageSettings ps, int flags, BPackageUserState state, int userId) {
        if (ps == null) {
            return null;
        }
        BPackage p = ps.pkg;
        if (p != null) {
            PackageInfo packageInfo = null;
            try {
                packageInfo = generatePackageInfo(p, flags, 0, 0, state, userId);
            } catch (Throwable ignored) {
            }
            return packageInfo;
        }
        return null;
    }

    public static PackageInfo generatePackageInfo(BPackage p, int flags, long firstInstallTime, long lastUpdateTime, BPackageUserState state, int userId) {
        PackageQueryContext request = new PackageQueryContext(flags, userId, 0);
        return generatePackageInfo(request.capture(p, state), request, firstInstallTime, lastUpdateTime);
    }

    static PackageInfo generatePackageInfo(PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        try {
            return generatePackageInfo(snapshot, request, 0, 0);
        } catch (Throwable ignored) {
            // Preserve the existing per-package generation failure boundary.
            return null;
        }
    }

    private static PackageInfo generatePackageInfo(PackageQueryContext.Snapshot snapshot, PackageQueryContext request,
                                                    long firstInstallTime, long lastUpdateTime) {
        if (!isVisible(snapshot)) {
            return null;
        }
        BPackage p = snapshot.code;
        int flags = request.flags;

        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.setLongVersionCode(p.getLongVersionCode());
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.applicationInfo = generateApplicationInfo(snapshot, request);

        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (!p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }

        if ((flags & PackageManager.GET_GIDS) != 0) {
            pi.gids = new int[]{};
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                for (int i = 0; i < N; i++) {
                    ConfigurationInfo config = p.configPreferences.get(i);
                    pi.configPreferences[i] = config == null ? null : new ConfigurationInfo(config);
                }
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                for (int i = 0; i < N; i++) {
                    FeatureInfo feature = p.reqFeatures.get(i);
                    pi.reqFeatures[i] = feature == null ? null : new FeatureInfo(feature);
                }
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            pi.activities = null;
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.activities.get(i);
                    res[num++] = generateActivityInfo(a, snapshot, request);
                }
                pi.activities = BzArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            pi.receivers = null;
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.receivers.get(i);
                    res[num++] = generateActivityInfo(a, snapshot, request);
                }
                pi.receivers = BzArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            pi.services = null;
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Service s = p.services.get(i);
                    
                    ServiceInfo serviceInfo = generateServiceInfo(s, snapshot, request);
                    if(serviceInfo == null) continue;
                    if ((flags & PackageManager.GET_META_DATA) != 0) {
                        if(serviceInfo.metaData != null){
                            res[num++] = serviceInfo;
                        }
                    }else{
                        res[num++] = serviceInfo;
                    }
                }
                pi.services = BzArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            pi.providers = null;
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Provider pr = p.providers.get(i);
                    ProviderInfo providerInfo = generateProviderInfo(pr, snapshot, request);
                    if (providerInfo != null) {
                        res[num++] = providerInfo;
                    }
                }
                pi.providers = BzArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            pi.instrumentation = null;
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            pi.permissions = null;
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            pi.requestedPermissions = null;
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i = 0; i < N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    // These flags describe this declared permission, not every permission held
                    // by the host. Keep metadata-based SDK checks consistent with the checked
                    // Binder permission path without inventing dangerous/runtime grants.
                    try {
                        if (request.permission(perm) == PackageManager.PERMISSION_GRANTED) {
                            pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                        }
                    } catch (RuntimeException unavailable) {
                        try {
                            Slog.w("PackageManagerCompat", "action=permissionFlags permission=" + perm
                                    + " grant=unconfirmed error=" + unavailable.getClass().getSimpleName());
                        } catch (Throwable ignored) {
                            // Optional diagnostics must not discard otherwise valid metadata.
                        }
                    }
                }
            }
        }
        // Only signatures consume host same-package metadata. Ordinary lists must not request it.
        PackageInfo base = request.hostSignatures(p.packageName);
        if ((flags & PackageSignaturesCompat.LEGACY_QUERY_FLAG) != 0) {
            if (base == null) {
                PackageSignaturesCompat.writeLegacy(pi, snapshot.signatures);
            } else {
                PackageSignaturesCompat.writeLegacy(pi, PackageSignaturesCompat.readLegacy(base));
            }
        }
        if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (base == null) {
                if (!snapshot.signingDetailsAvailable) throw new NullPointerException("Package signing details are unavailable");
                // This compatibility constructor uses a shared platform template. Query
                // generation no longer holds mPackages, so pair assignment and copying here.
                synchronized (SIGNING_INFO_LOCK) {
                    PackageParser.SigningDetails signingDetails = PackageParser.SigningDetails.UNKNOWN;
                    BRPackageParserSigningDetails.get(signingDetails)._set_signatures(snapshot.signingSignatures);
                    pi.signingInfo = BRSigningInfo.get()._new(signingDetails);
                }
            } else {
                pi.signingInfo = base.signingInfo;
            }
        }
        return pi;
    }

    public static ActivityInfo generateActivityInfo(BPackage.Activity a, int flags, BPackageUserState state, int userId) {
        PackageQueryContext request = new PackageQueryContext(flags, userId, 0);
        return generateActivityInfo(a, request.capture(a.owner, state), request);
    }

    static ActivityInfo generateActivityInfo(BPackage.Activity a, PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        if (!isVisible(snapshot)) {
            return null;
        }
        
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData == null ? null : new Bundle(a.metaData);
        ai.processName = BPackageManagerService.fixProcessName(ai.packageName, ai.processName);
        ai.applicationInfo = generateApplicationInfo(snapshot, request);

        // Activity-alias support: some apps (e.g., Instagram) use <activity-alias android:name=".InternalLauncher"
        // with android:targetActivity pointing at the real Activity class.
        // If we return the alias name as the class to instantiate, ActivityThread will crash with CNFE.
        if (ai.targetActivity != null && !ai.targetActivity.isEmpty()) {
            String target = ai.targetActivity;
            if (target.startsWith(".")) {
                target = ai.packageName + target;
            } else if (target.indexOf('.') < 0) {
                target = ai.packageName + "." + target;
            }
            ai.name = target;
        }
        return ai;
    }

    public static ServiceInfo generateServiceInfo(BPackage.Service s, int flags, BPackageUserState state, int userId) {
        PackageQueryContext request = new PackageQueryContext(flags, userId, 0);
        return generateServiceInfo(s, request.capture(s.owner, state), request);
    }

    static ServiceInfo generateServiceInfo(BPackage.Service s, PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        if (!isVisible(snapshot)) {
            return null;
        }
        
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData == null ? null : new Bundle(s.metaData);
        si.processName = BPackageManagerService.fixProcessName(si.packageName, si.processName);
        si.applicationInfo = generateApplicationInfo(snapshot, request);
        return si;
    }

    public static ProviderInfo generateProviderInfo(BPackage.Provider p, int flags, BPackageUserState state, int userId) {
        PackageQueryContext request = new PackageQueryContext(flags, userId, 0);
        return generateProviderInfo(p, request.capture(p.owner, state), request);
    }

    static ProviderInfo generateProviderInfo(BPackage.Provider p, PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        if (!isVisible(snapshot)) {
            return null;
        }
        
        ProviderInfo pi = new ProviderInfo(p.info);
        pi.authority = p.resolvedAuthority;
        if (pi.authority == null)
            return null;
        pi.metaData = p.metaData == null ? null : new Bundle(p.metaData);
        pi.processName = BPackageManagerService.fixProcessName(pi.packageName, pi.processName);
        if ((request.flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(snapshot, request);
        return pi;
    }

    public static PermissionInfo generatePermissionInfo(
            BPackage.Permission p, int flags) {
        if (p == null || p.info == null) return null;
        // The virtual PM must retain the platform copy contract for returned permission metadata.
        @SuppressWarnings("deprecation")
        PermissionInfo pi = new PermissionInfo(p.info);
        if ((flags & PackageManager.GET_META_DATA) != 0) pi.metaData = p.metaData == null ? null : new Bundle(p.metaData);
        else if (pi.metaData != null) pi.metaData = new Bundle(pi.metaData);
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(
            BPackage.Instrumentation i, int flags) {
        if (i == null || i.info == null) return null;
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        if ((flags & PackageManager.GET_META_DATA) != 0) ii.metaData = i.metaData == null ? null : new Bundle(i.metaData);
        else if (ii.metaData != null) ii.metaData = new Bundle(ii.metaData);
        return ii;
    }

    public static ApplicationInfo generateApplicationInfo(BPackage p, int flags, BPackageUserState state, int userId) {
        PackageQueryContext request = new PackageQueryContext(flags, userId, 0);
        return generateApplicationInfo(request.capture(p, state), request);
    }

    static ApplicationInfo generateApplicationInfo(PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        return request.application(snapshot, () -> generateApplicationTemplate(snapshot, request));
    }

    private static ApplicationInfo generateApplicationTemplate(PackageQueryContext.Snapshot snapshot, PackageQueryContext request) {
        if (!isVisible(snapshot)) {
            return null;
        }
        ApplicationInfo baseApplication = request.hostApplication();
        if (baseApplication == null || snapshot.application == null) return null;
        BPackage p = snapshot.code;
        int userId = request.userId;
        int flags = request.flags;
        String sourceDir = p.baseCodePath;
        ApplicationInfo ai = PackageQueryContext.copyApplication(snapshot.application);
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = snapshot.appMetadata == null ? null : new Bundle(snapshot.appMetadata);
        }
        ai.dataDir = BEnvironment.getDataDir(ai.packageName, userId).getAbsolutePath();
        ai.processName = BPackageManagerService.fixProcessName(p.packageName, ai.packageName);
        ai.publicSourceDir = sourceDir;
        ai.sourceDir = sourceDir;
        ai.uid = snapshot.appId;
        // A stored APK owns its parsed/copied split set. Pulling paths from a host
        // installation with the same package name can mix two different APK versions
        // (including externally imported Google packages). Only system installs may
        // refer back to their verified host source.
        if (snapshot.systemInstall) {
            hydrateSplitApkPathsFromHost(ai, request);
            String[] runtimeAbis = Process.is64Bit()
                    ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            BRApplicationInfoL.get(ai)._set_primaryCpuAbi(runtimeAbis[0]);
            BRApplicationInfoL.get(ai)._set_scanPublicSourceDir(BRApplicationInfoL.get(baseApplication).scanPublicSourceDir());
            BRApplicationInfoL.get(ai)._set_scanSourceDir(BRApplicationInfoL.get(baseApplication).scanSourceDir());
        } else {
            PackageUpdateStore.configureNativeLibraryPaths(p, ai);
        }

        ai.deviceProtectedDataDir = BEnvironment.getDeDataDir(p.packageName, userId).getAbsolutePath();

        if (BRApplicationInfoN.get(ai)._check_deviceEncryptedDataDir() != null) {
            BRApplicationInfoN.get(ai)._set_deviceEncryptedDataDir(ai.deviceProtectedDataDir);
        }
        if (BRApplicationInfoN.get(ai)._check_credentialEncryptedDataDir() != null) {
            BRApplicationInfoN.get(ai)._set_credentialEncryptedDataDir(ai.dataDir);
        }
        if (BRApplicationInfoN.get(ai)._check_deviceProtectedDataDir() != null) {
            BRApplicationInfoN.get(ai)._set_deviceProtectedDataDir(ai.deviceProtectedDataDir);
        }
        if (BRApplicationInfoN.get(ai)._check_credentialProtectedDataDir() != null) {
            BRApplicationInfoN.get(ai)._set_credentialProtectedDataDir(ai.dataDir);
        }
        ai.sharedLibraryFiles = new String[]{request.sharedLibraryFile()};
        return ai;
    }

    private static void hydrateSplitApkPathsFromHost(ApplicationInfo ai, PackageQueryContext request) {
        if (ai == null) {
            return;
        }
        if (ai.splitSourceDirs != null && ai.splitSourceDirs.length > 0) {
            return;
        }

        try {
            ApplicationInfo hostAi = request.installedApplication(ai.packageName);
            if (hostAi == null || !Objects.equals(hostAi.sourceDir, ai.sourceDir)
                    || hostAi.splitSourceDirs == null || hostAi.splitSourceDirs.length == 0) {
                return;
            }

            ai.splitSourceDirs = hostAi.splitSourceDirs.clone();
            ai.splitPublicSourceDirs = hostAi.splitPublicSourceDirs != null
                    ? hostAi.splitPublicSourceDirs.clone()
                    : hostAi.splitSourceDirs.clone();
            ai.splitNames = hostAi.splitNames == null ? null : hostAi.splitNames.clone();
        } catch (Throwable ignored) {
        }
    }

    static ApplicationInfo resolveHostInstalledApplicationInfo(String packageName) {
        if (packageName == null) {
            return null;
        }

        PackageManager pm = NexBoxCore.getPackageManager();
        if (pm == null) {
            return null;
        }

        int hostUserId = NexBoxCore.getHostUserId();
        if (hostUserId >= 0) {
            try {
                Method asUser = pm.getClass().getMethod("getApplicationInfoAsUser", String.class, int.class, int.class);
                asUser.setAccessible(true);
                Object info = asUser.invoke(pm, packageName, 0, hostUserId);
                if (info instanceof ApplicationInfo) {
                    return (ApplicationInfo) info;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            return pm.getApplicationInfo(packageName, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isVisible(PackageQueryContext.Snapshot snapshot) {
        return snapshot.application != null && !snapshot.hiddenByPolicy && snapshot.state.installed && !snapshot.state.hidden;
    }

    public static Resources getResources(Context context, ApplicationInfo appInfo) {
        BPackageSettings ps = BPackageManagerService.get().getBPackageSetting(appInfo.packageName);
        if (ps != null) {
            AssetManager assets = BRAssetManager.get()._new();
            BRAssetManager.get(assets).addAssetPath(ps.pkg.baseCodePath);
            Resources hostRes = context.getResources();
            // A configuration context uses host assets, not this isolated guest AssetManager.
            @SuppressWarnings("deprecation")
            Resources guestResources = new Resources(assets, hostRes.getDisplayMetrics(), hostRes.getConfiguration());
            return guestResources;
        }
        return null;
    }
}
