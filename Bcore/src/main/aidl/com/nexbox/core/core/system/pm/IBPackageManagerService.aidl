package com.nexbox.core.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.ProviderInfo;
import android.content.Intent;
import android.content.ComponentName;
import android.os.Bundle;
import java.util.List;
import com.nexbox.core.entity.pm.InstallResult;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.entity.pm.InstalledPackage;
import com.nexbox.core.core.system.pm.IBCloneInstallSession;


interface IBPackageManagerService {
    ResolveInfo resolveService(in Intent intent, int flags, String resolvedType, int userId);
    ResolveInfo resolveActivity(in Intent intent, int flags, String resolvedType, int userId);
    ProviderInfo resolveContentProvider(String authority, int flag, int userId);
    ResolveInfo resolveIntent(in Intent intent, String resolvedType, int flags, int userId);

    ApplicationInfo getApplicationInfo(String packageName, int flags, int userId);
    PackageInfo getPackageInfo(String packageName, int flags, int userId);
    ServiceInfo getServiceInfo(in ComponentName component, int flags, int userId);
    ActivityInfo getReceiverInfo(in ComponentName componentName, int flags, int userId);
    ActivityInfo getActivityInfo(in ComponentName component, int flags, int userId);
    ProviderInfo getProviderInfo(in ComponentName component, int flags, int userId);
    List<ApplicationInfo> getInstalledApplications(int flags, int userId);
    List<PackageInfo> getInstalledPackages(int flags, int userId);

    List<ResolveInfo> queryIntentActivities(in Intent intent, int flags, String resolvedType, int userId);
    List<ResolveInfo> queryBroadcastReceivers(in Intent intent, int flags, String resolvedType, int userId);
    List<ResolveInfo> queryIntentServices(in Intent intent, int flags, int userId);
    List<ProviderInfo> queryContentProviders(String processName, int uid, int flags, int userId);

    InstallResult installPackageAsUser(String file, in InstallOption option, int userId);
    InstallResult installPackageAsUserIfMatches(String file, in InstallOption option, int userId,
        long expectedUserCreateTime, String expectedPackageName);
    InstallResult installExistingPackageAsUser(String packageName, int userId);
    InstallResult installPackageUpdateAsUserIfMatches(String file, in InstallOption option, int userId,
        long expectedUserCreateTime);
    InstallResult installExistingPackageAsUserIfMatches(String packageName, int userId,
        long expectedUserCreateTime);
    void uninstallPackageAsUser(String packageName, int userId);
    boolean uninstallPackageAsUserIfMatches(String packageName, int userId, long expectedUserCreateTime);
    void uninstallPackage(String packageName);
    void clearPackage(String packageName, int userId);
    boolean clearPackageIfMatches(String packageName, int userId, long expectedUserCreateTime);
    void stopPackage(String packageName, int userId);
    boolean stopPackageIfMatches(String packageName, int userId, long expectedUserCreateTime);
    boolean stopPackagesIfMatches(in List<String> packageNames, int userId, long expectedUserCreateTime);
    void deleteUser(int userId);

    boolean isInstalled(String packageName, int userId);
    List<InstalledPackage> getInstalledPackagesAsUser(int userId);
    String[] getPackagesForUid(int uid, int userId);
    IBinder getGuestPackageInstaller();
    Bundle getApplicationLocaleState(String packageName, int userId);
    void setApplicationLocales(String packageName, String languageTags, int userId);
    void setOverrideLocaleConfig(String packageName, String languageTags, int userId);
    IBCloneInstallSession prepareCloneInstall(String source, in InstallOption option, IBinder owner);
    Bundle syncHostPackages(String packageName);
    Bundle getRawPackageInventory(int userId, long createTime);
    Bundle installGmsIfInventory(int userId, long createTime, String expectedInventoryToken,
        boolean requireTraceFree, String requestId);
    Bundle uninstallGmsIfInventory(int userId, long createTime, String expectedInventoryToken,
        in List<Bundle> expectedComponents, String requestId);
}
