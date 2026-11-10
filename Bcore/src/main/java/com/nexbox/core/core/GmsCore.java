package com.nexbox.core.core;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.entity.pm.InstallResult;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.IBUserManagerService;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.utils.Slog;


public class GmsCore {
    private static final String TAG = "GmsCore";

    private static final HashSet<String> GOOGLE_APP = new HashSet<>();
    private static final HashSet<String> GOOGLE_SERVICE = new HashSet<>();
    public static final String GMS_PKG = "com.google.android.gms";
    public static final String GSF_PKG = "com.google.android.gsf";
    public static final String VENDING_PKG = "com.android.vending";

    static {
        GOOGLE_APP.add(VENDING_PKG);
        GOOGLE_APP.add("com.google.android.play.games");
        GOOGLE_APP.add("com.google.android.wearable.app");
        GOOGLE_APP.add("com.google.android.wearable.app.cn");


        GOOGLE_SERVICE.add(GMS_PKG);
        GOOGLE_SERVICE.add(GSF_PKG);
        GOOGLE_SERVICE.add("com.google.android.gsf.login");
        GOOGLE_SERVICE.add("com.google.android.backuptransport");
        GOOGLE_SERVICE.add("com.google.android.backup");
        GOOGLE_SERVICE.add("com.google.android.configupdater");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.contacts");
        GOOGLE_SERVICE.add("com.google.android.feedback");
        GOOGLE_SERVICE.add("com.google.android.onetimeinitializer");
        GOOGLE_SERVICE.add("com.google.android.partnersetup");
        GOOGLE_SERVICE.add("com.google.android.setupwizard");
        GOOGLE_SERVICE.add("com.google.android.syncadapters.calendar");
    }

    public static boolean isGoogleService(String packageName) {
        return GOOGLE_SERVICE.contains(packageName);
    }

    public static boolean isGoogleAppOrService(String str) {
        return GOOGLE_APP.contains(str) || GOOGLE_SERVICE.contains(str);
    }

    /** Controlled environment batches omit unrelated optional Google applications. */
    public static List<String> environmentInstallPackages() {
        Set<String> packages = new LinkedHashSet<>();
        packages.add(GSF_PKG);
        packages.add("com.google.android.gsf.login");
        packages.add(GMS_PKG);
        packages.add(VENDING_PKG);
        packages.addAll(new TreeSet<>(GOOGLE_SERVICE));
        return new ArrayList<>(packages);
    }

    private static final class InstalledComponent {
        final String packageName;
        final int userId;
        final long userCreateTime;

        InstalledComponent(InstallResult receipt) {
            packageName = receipt.packageName;
            userId = receipt.userId;
            userCreateTime = receipt.userCreateTime;
        }
    }

    private static final class InstallBatch {
        final int userId;
        final List<InstalledComponent> newlyInstalled = new ArrayList<>();
        long userCreateTime;

        InstallBatch(int userId, long expectedUserCreateTime) throws android.os.RemoteException {
            if (userId < 0 || expectedUserCreateTime < 0) {
                throw new IllegalArgumentException("A valid target user identity is required");
            }
            this.userId = userId;
            userCreateTime = expectedUserCreateTime;
            requireCurrentUser();
        }

        void requireCurrentUser() throws android.os.RemoteException {
            BUserInfo current = userServiceChecked().getUserInfo(userId);
            if (userCreateTime == 0 ? current != null
                    : current == null || current.id != userId || current.createTime != userCreateTime) {
                throw new IllegalStateException("The target user changed during the Google service operation");
            }
        }

        boolean record(String expectedPackage, InstallResult receipt) {
            if (receipt == null || !receipt.success || !expectedPackage.equals(receipt.packageName)
                    || receipt.userId != userId || receipt.userCreateTime <= 0
                    || (userCreateTime > 0 && userCreateTime != receipt.userCreateTime)) return false;
            // An absent numeric id is not rebound by a later read. Only the first
            // committed strict receipt may establish this batch's new user lifetime.
            userCreateTime = receipt.userCreateTime;
            newlyInstalled.add(new InstalledComponent(receipt));
            return true;
        }
    }

    private static InstallResult installPackages(Set<String> list, InstallBatch batch) throws android.os.RemoteException {
        String confirmedPackage = null;
        for (String packageName : list) {
            batch.requireCurrentUser();
            // A checked pre-read can skip an existing component, but cannot authorize
            // an install: absence/package/generation are checked again under the
            // server's lifecycle lock by installPackageAsUserIfMatches below.
            if (isInstalledChecked(packageName, batch.userId)) {
                batch.requireCurrentUser();
                confirmedPackage = packageName;
                continue;
            }
            ApplicationInfo source;
            try {
                source = NexBoxCore.getPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // Optional components not present on the real device remain optional.
                continue;
            }
            if (source == null || !packageName.equals(source.packageName)
                    || source.sourceDir == null || source.sourceDir.isEmpty()) {
                return new InstallResult().installError(packageName,
                        "The host Google component does not have an installable APK source");
            }
            InstallResult installResult = packageServiceChecked().installPackageAsUserIfMatches(
                    source.sourceDir, InstallOption.installBySystem(), batch.userId,
                    batch.userCreateTime, packageName);
            if (installResult == null) {
                return new InstallResult().installError(packageName,
                        "The Google package installation returned no committed receipt");
            }
            if (!installResult.success) {
                return installResult;
            }
            if (!batch.record(packageName, installResult)) {
                Slog.w(TAG, "Rejected Google install receipt for " + packageName + " userId=" + batch.userId);
                return new InstallResult().installError(packageName,
                        "The Google package installation receipt did not match its target");
            }
            // Keep a valid receipt even if this later read fails, so rollback can
            // address only that committed package and its original user generation.
            batch.requireCurrentUser();
            if (!isInstalledChecked(packageName, batch.userId)) {
                return new InstallResult().installError(packageName,
                        "The Google package installation could not be verified");
            }
            batch.requireCurrentUser();
            confirmedPackage = packageName;
        }
        return confirmedPackage == null
                ? new InstallResult().installError("No Google packages are available to install")
                : new InstallResult().installSuccess(confirmedPackage);
    }

    private static boolean isInstalledChecked(String packageName, int userId) throws android.os.RemoteException {
        return packageServiceChecked().isInstalled(packageName, userId);
    }

    private static IBPackageManagerService packageServiceChecked() {
        IBPackageManagerService service = NexBoxCore.getBPackageManager().getService();
        if (service == null) throw new IllegalStateException("Package service is unavailable");
        return service;
    }

    private static IBUserManagerService userServiceChecked() {
        IBUserManagerService service = BUserManager.get().getService();
        if (service == null) throw new IllegalStateException("User service is unavailable");
        return service;
    }

    private static boolean rollback(InstallBatch batch) {
        boolean confirmed = true;
        // Unwind dependents before their services. Never infer ownership from a fresh
        // boolean read or from a numeric user id that may have been reused meanwhile.
        for (int i = batch.newlyInstalled.size() - 1; i >= 0; i--) {
            InstalledComponent installed = batch.newlyInstalled.get(i);
            try {
                if (!packageServiceChecked().uninstallPackageAsUserIfMatches(installed.packageName,
                        installed.userId, installed.userCreateTime)) {
                    confirmed = false;
                    Slog.w(TAG, "Google rollback not confirmed for " + installed.packageName
                            + " userId=" + installed.userId + " generation=" + installed.userCreateTime);
                }
            } catch (Exception failure) {
                confirmed = false;
                Slog.e(TAG, "Google rollback failed for " + installed.packageName
                        + " userId=" + installed.userId + " generation=" + installed.userCreateTime, failure);
            }
        }
        return confirmed;
    }

    public static InstallResult installGApps(int userId) {
        try {
            BUserInfo user = userServiceChecked().getUserInfo(userId);
            return installGApps(userId, user == null ? 0L : user.createTime);
        } catch (Exception failure) {
            Slog.w(TAG, "action=installGms stage=identityUnavailable userId=" + userId, failure);
            return new InstallResult().installError("The target user identity could not be verified");
        }
    }

    public static InstallResult installGApps(int userId, long expectedUserCreateTime) {
        Set<String> googleApps = new LinkedHashSet<>();
        googleApps.add(GSF_PKG);
        googleApps.add("com.google.android.gsf.login");
        googleApps.add(GMS_PKG);
        googleApps.add(VENDING_PKG);
        googleApps.addAll(new TreeSet<>(GOOGLE_SERVICE));
        googleApps.addAll(new TreeSet<>(GOOGLE_APP));

        InstallBatch batch = null;
        InstallResult installResult;
        try {
            batch = new InstallBatch(userId, expectedUserCreateTime);
            for (String required : new String[]{GMS_PKG, VENDING_PKG}) {
                if (isInstalledChecked(required, userId)) continue;
                ApplicationInfo source = NexBoxCore.getPackageManager().getApplicationInfo(required, 0);
                if (source == null || !source.enabled || source.sourceDir == null) {
                    throw new IllegalStateException("Required Google component is unavailable: " + required);
                }
            }
            installResult = installPackages(googleApps, batch);
            if (installResult.success) {
                batch.requireCurrentUser();
                if (!isInstalledChecked(GMS_PKG, userId) || !isInstalledChecked(VENDING_PKG, userId)) {
                    installResult = new InstallResult().installError(GMS_PKG,
                            "Google Play services and Play Store must both be installed in this profile");
                } else {
                    batch.requireCurrentUser();
                    if (batch.userCreateTime <= 0) {
                        throw new IllegalStateException("No committed Google installation established the target user");
                    }
                    return new InstallResult().installSuccess(GMS_PKG, userId, batch.userCreateTime);
                }
            }
        } catch (Exception unavailable) {
            Slog.e(TAG, "Could not complete the Google service installation for userId=" + userId, unavailable);
            String detail = unavailable.getMessage();
            installResult = new InstallResult().installError(GMS_PKG,
                    "The Google service environment could not be verified"
                            + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        }
        if (batch != null && !rollback(batch)) {
            String detail = installResult.msg;
            installResult.installError(installResult.packageName,
                    (detail == null ? "Google service installation failed" : detail)
                            + "; some rollback operations could not be confirmed");
        }
        return installResult;
    }

    public static void uninstallGApps(int userId) {
        try {
            BUserInfo user = userServiceChecked().getUserInfo(userId);
            if (user == null || user.createTime <= 0L
                    || !uninstallGApps(userId, user.createTime)) {
                throw new IllegalStateException("Google service removal was not confirmed");
            }
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("The target user identity could not be verified", failure);
        }
    }

    /** Every mutation keeps the caller's generation; partial absence is not reported as full removal. */
    public static boolean uninstallGApps(int userId, long expectedUserCreateTime) {
        if (userId < 0 || expectedUserCreateTime <= 0L) return false;
        Set<String> targets = new LinkedHashSet<>(new TreeSet<>(GOOGLE_APP));
        targets.addAll(new TreeSet<>(GOOGLE_SERVICE));
        boolean confirmed = true;
        try {
            InstallBatch owner = new InstallBatch(userId, expectedUserCreateTime);
            for (String packageName : targets) {
                owner.requireCurrentUser();
                if (!isInstalledChecked(packageName, userId)) continue;
                if (!packageServiceChecked().uninstallPackageAsUserIfMatches(
                        packageName, userId, expectedUserCreateTime)) {
                    confirmed = false;
                    Slog.w(TAG, "action=uninstallGms stage=componentUnconfirmed package=" + packageName
                            + " userId=" + userId + " generation=" + expectedUserCreateTime);
                }
            }
            for (String packageName : targets) {
                owner.requireCurrentUser();
                if (isInstalledChecked(packageName, userId)) confirmed = false;
            }
            owner.requireCurrentUser();
            // Package removal can commit before account cleanup persistence fails. A
            // later retry must not call that residual environment fully uninstalled.
            if (userServiceChecked().hasGmsEnvironmentIfMatches(userId, expectedUserCreateTime)) confirmed = false;
            owner.requireCurrentUser();
            Slog.i(TAG, "action=uninstallGms stage=readBack userId=" + userId
                    + " generation=" + expectedUserCreateTime + " success=" + confirmed);
            return confirmed;
        } catch (Exception failure) {
            // Earlier components may already have been removed; never retry against a new user.
            Slog.w(TAG, "action=uninstallGms stage=failed userId=" + userId
                    + " generation=" + expectedUserCreateTime + " result=unconfirmed", failure);
            return false;
        }
    }

    public static boolean hasGmsTraces(int userId) {
        if (userId < 0) return false;
        try {
            // The server checks raw component install flags and residual virtual accounts;
            // it does not need host AccountManager access or lossy display package metadata.
            IBUserManagerService users = userServiceChecked();
            BUserInfo user = users.getUserInfo(userId);
            return user != null && user.createTime > 0
                    && users.hasGmsEnvironmentIfMatches(userId, user.createTime);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not read Google environment state", failure);
        }
    }

    /** Only the service environment, not unrelated Google apps such as Photos or Chrome. */
    public static boolean isGmsEnvironmentComponent(String packageName) {
        return packageName != null && (GOOGLE_SERVICE.contains(packageName)
                || VENDING_PKG.equals(packageName)
                || packageName.startsWith(GMS_PKG + ".dynamite_"));
    }

    /** Confirmed reset of one captured profile lifetime; never rebind a reused numeric id. */
    public static boolean resetGmsEnvironmentIfMatches(int userId, long userCreateTime) {
        if (userId < 0 || userCreateTime <= 0) return false;
        try {
            // A lost reply may follow a partial reset, so this mutation is not retried.
            return userServiceChecked().resetGmsEnvironmentIfMatches(userId, userCreateTime);
        } catch (Exception error) {
            Slog.e(TAG, "Could not confirm Google environment reset for userId=" + userId
                    + " generation=" + userCreateTime, error);
            return false;
        }
    }

    public static void remove(String packageName) {
        GOOGLE_SERVICE.remove(packageName);
        GOOGLE_APP.remove(packageName);
    }


    public static boolean isSupportGms() {
        try {
            NexBoxCore.getPackageManager().getPackageInfo(GMS_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    public static boolean isInstalledGoogleService(int userId) {
        return NexBoxCore.get().isInstalled(GMS_PKG, userId)
                && NexBoxCore.get().isInstalled(VENDING_PKG, userId);
    }
}
