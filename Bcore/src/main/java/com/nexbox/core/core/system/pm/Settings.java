package com.nexbox.core.core.system.pm;

import android.content.pm.PackageParser;
import android.os.Parcel;
import android.os.Process;
import android.util.ArrayMap;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.ParcelCompat;


 class Settings {
    public static final String TAG = "Settings";

    final ArrayMap<String, BPackageSettings> mPackages = new ArrayMap<>();
    private final Map<String, Integer> mAppIds = new HashMap<>();
    private final Map<String, SharedUserSetting> mSharedUsers = SharedUserSetting.sSharedUsers;
    private int mCurrUid = 0;
    private IllegalStateException mUidLoadError;
    private final Set<String> mUnreadablePackages = new HashSet<>();
    private boolean mPackageIndexScanned;
    private IllegalStateException mPackageScanError;

    boolean lifecycleMetadataReadable() {
        synchronized (mPackages) {
            return mPackageIndexScanned && mPackageScanError == null && mUidLoadError == null
                    && mUnreadablePackages.isEmpty() && SharedUserSetting.isReadable();
        }
    }

    void requireLifecycleMetadataReadable() {
        if (!lifecycleMetadataReadable()) {
            throw new IllegalStateException("Package and UID metadata needs recovery; existing user data was preserved");
        }
    }

    public Settings() {
        synchronized (mPackages) {
            loadUidLP();
            SharedUserSetting.loadSharedUsers();
        }
    }

    BPackageSettings getPackageLPw(String name, PackageParser.Package aPackage, InstallOption installOption) {
        if (mUnreadablePackages.contains(name)) {
            throw new IllegalStateException("Existing package metadata needs recovery; refusing to replace it");
        }
        BPackageSettings pkgSettings;
        BPackageSettings origSettings = new BPackageSettings();
        origSettings.pkg = new BPackage(aPackage);
        origSettings.pkg.installOption = installOption;
        origSettings.installOption = installOption;
        origSettings.pkg.mExtras = origSettings;
        origSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(origSettings.pkg, 0, BPackageUserState.create(), 0);
        synchronized (mPackages) {
            pkgSettings = mPackages.get(name);
            if (pkgSettings != null) {
                origSettings.appId = pkgSettings.appId;
                origSettings.userState = pkgSettings.userState;
            } else {
                boolean b = registerAppIdLPw(origSettings);
                if (!b) {
                    throw new RuntimeException("registerAppIdLPw err.");
                }
            }
        }
        return origSettings;
    }

    boolean registerAppIdLPw(BPackageSettings p) {
        if (mUidLoadError != null) throw mUidLoadError;
        if (!mUnreadablePackages.isEmpty()) {
            throw new IllegalStateException("A stored package identity needs recovery before assigning new UIDs");
        }
        SharedUserSetting.requireReadable();
        boolean createdNew = false;
        String sharedUserId = p.pkg.mSharedUserId;
        SharedUserSetting sharedUserSetting = null;
        if (sharedUserId != null) {
            sharedUserSetting = mSharedUsers.get(sharedUserId);
            if (sharedUserSetting == null) {
                sharedUserSetting = new SharedUserSetting(sharedUserId);
                sharedUserSetting.userId = acquireAndRegisterNewAppIdLPw(p);
                mSharedUsers.put(sharedUserId, sharedUserSetting);
            }
        }
        if (sharedUserSetting != null) {
            p.appId = sharedUserSetting.userId;
            Slog.d(TAG, p.pkg.packageName + " sharedUserId = " + sharedUserId + ", setAppId = " + p.appId);
        }
        if (p.appId == 0) {

            p.appId = acquireAndRegisterNewAppIdLPw(p);
        }
        if (p.appId < 0) {
            createdNew = false;




        } else {
            createdNew = true;
        }
        saveUidLP();
        SharedUserSetting.saveSharedUsers();
        return createdNew;
    }

    private int acquireAndRegisterNewAppIdLPw(BPackageSettings obj) {

        Integer integer = mAppIds.get(obj.pkg.packageName);
        if (integer != null) {
            // Earlier registries stored the allocation counter; newer records keep
            // the full app id. Never return a small counter as an Android identity.
            int appId = integer < Process.FIRST_APPLICATION_UID
                    ? Process.FIRST_APPLICATION_UID + integer : integer;
            if (integer <= 0 || appId > Process.LAST_APPLICATION_UID) return -1;
            return appId;
        }

        if (mCurrUid >= Process.LAST_APPLICATION_UID - Process.FIRST_APPLICATION_UID) {
            return -1;
        }
        mCurrUid++;
        int appId = Process.FIRST_APPLICATION_UID + mCurrUid;
        mAppIds.put(obj.pkg.packageName, appId);
        return appId;
    }

    private void saveUidLP() {
        if (mUidLoadError != null) throw mUidLoadError;
        Parcel parcel = Parcel.obtain();
        FileOutputStream fileOutputStream = null;
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUidConf());
        try {
            // Keep allocation tombstones. A package can be temporarily unavailable
            // during migration, and a just-allocated package has not been published yet.
            parcel.writeInt(mCurrUid);
            parcel.writeMap(mAppIds);

            fileOutputStream = atomicFile.startWrite();
            BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
            atomicFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            Slog.e("Settings", "Container operation failed", e);
            atomicFile.failWrite(fileOutputStream);
        } finally {
            parcel.recycle();
        }
    }

    private void loadUidLP() {
        Parcel parcel = Parcel.obtain();
        try {
            File uidFile = BEnvironment.getUidConf();
            if (!uidFile.exists() && !new File(uidFile.getPath() + ".bak").exists()) {
                File appRoot = BEnvironment.getAppRootDir();
                if (appRoot.exists()) {
                    File[] entries = appRoot.listFiles();
                    if (entries == null || entries.length > 0) {
                        throw new IOException("UID registry is missing for existing package storage");
                    }
                }
                return;
            }
            byte[] uidBytes = MetadataParcel.readBytes(uidFile);
            if (uidBytes.length < 8) throw new IOException("Truncated UID registry");

            parcel.unmarshall(uidBytes, 0, uidBytes.length);
            parcel.setDataPosition(0);

            int currentUid = parcel.readInt();
            Map<?, ?> hashMap = ParcelCompat.readHashMap(parcel, HashMap.class.getClassLoader());
            if (currentUid < 0 || hashMap == null || parcel.dataAvail() != 0) {
                throw new IOException("Invalid UID registry");
            }
            Map<String, Integer> loadedAppIds = new HashMap<>(hashMap.size());
            for (Map.Entry<?, ?> entry : hashMap.entrySet()) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Integer)) {
                    throw new IOException("Invalid UID registry entry");
                }
                loadedAppIds.put((String) entry.getKey(), (Integer) entry.getValue());
            }
            synchronized (mAppIds) {
                mCurrUid = currentUid;
                mAppIds.clear();
                mAppIds.putAll(loadedAppIds);
            }
        } catch (Exception e) {
            // Resetting this counter can assign an existing app's identity to a new
            // package. Preserve the file and block allocation until it is recoverable.
            mUidLoadError = new IllegalStateException("UID registry needs recovery", e);
            Slog.e(TAG, "Could not load UID registry; original file retained", e);
        } finally {
            parcel.recycle();
        }
    }

    public void scanPackage(Consumer<BPackage> registerComponents) {
        synchronized (mPackages) {
            mPackageIndexScanned = false;
            File appRootDir = BEnvironment.getAppRootDir();
            BzFileUtils.mkdirs(appRootDir);
            File[] apps = appRootDir.listFiles();
            if (apps == null) {
                mPackageScanError = new IllegalStateException("Package storage could not be enumerated");
                Slog.w(TAG, "Could not inspect package storage; existing metadata retained");
                return;
            }
            for (File app : apps) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(app.toPath(), BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isDirectory() || attributes.isSymbolicLink()) {
                        scanPackage(app.getName(), registerComponents);
                    }
                } catch (IOException failure) {
                    mPackageScanError = new IllegalStateException("A package storage entry could not be inspected", failure);
                    Slog.e(TAG, "Package enumeration incomplete; user data retained", failure);
                    return;
                }
            }
            mPackageScanError = null;
            mPackageIndexScanned = true;
        }
    }

    public void scanPackage(String packageName, Consumer<BPackage> registerComponents) {
        synchronized (mPackages) {
            updatePackageLP(BEnvironment.getAppDir(packageName), registerComponents);
        }
    }

    private void updatePackageLP(File app, Consumer<BPackage> registerComponents) {
        String packageName = app.getName();
        Parcel packageSettingsIn = Parcel.obtain();
        File packageConf = BEnvironment.getPackageConf(packageName);
        String stage = "decode";
        try {
            byte[] bPackageSettingsBytes = MetadataParcel.readBytes(packageConf);

            packageSettingsIn.unmarshall(bPackageSettingsBytes, 0, bPackageSettingsBytes.length);
            packageSettingsIn.setDataPosition(0);

            BPackageSettings bPackageSettings;
            try (MetadataParcel metadata = MetadataParcel.open(packageConf, packageSettingsIn)) {
                bPackageSettings = new BPackageSettings(packageSettingsIn);
                if (bPackageSettings.pkg == null || !packageName.equals(bPackageSettings.pkg.packageName)
                        || bPackageSettings.installOption == null || bPackageSettings.appId <= 0) {
                    throw new IOException("Package metadata identity does not match its directory");
                }
                metadata.finish(packageConf, bPackageSettingsBytes);
            }
            if (bPackageSettings.relocateStoragePaths()) {
                MetadataParcel.preserveOriginal(packageConf, bPackageSettingsBytes);
            }
            bPackageSettings.pkg.mExtras = bPackageSettings;
            boolean preserveStoredMetadata = false;
            if (bPackageSettings.installOption.isFlag(InstallOption.FLAG_SYSTEM)) {
                try {
                    BPackageSettings refreshed = HostPackageSync.prepare(bPackageSettings);
                    if (refreshed != null) {
                        if (!refreshed.save()) throw new IOException("Could not persist synchronized host source");
                        bPackageSettings = refreshed;
                        Slog.i(TAG, "action=syncHostSource stage=committed package=" + packageName
                                + " version=" + refreshed.pkg.getLongVersionCode() + " identity=preserved data=retained");
                    }
                } catch (Throwable unavailable) {
                    preserveStoredMetadata = true;
                    Slog.w(TAG, "action=syncHostSource stage=rejected package=" + packageName
                            + " metadata=readable data=retained", unavailable);
                }
            } else {
                bPackageSettings.pkg.applicationInfo = PackageManagerCompat.generateApplicationInfo(bPackageSettings.pkg, 0, BPackageUserState.create(), 0);
            }
            if (!preserveStoredMetadata && !bPackageSettings.save()) {
                Slog.w(TAG, "Could not update package metadata; recovery copy retained");
            }
            // Publish the same graph to both indices. On registration failure the resolver
            // restores its previous graph, and this scan retains the matching package record.
            stage = "componentIndex";
            registerComponents.accept(bPackageSettings.pkg);
            mPackages.put(bPackageSettings.pkg.packageName, bPackageSettings);
            mUnreadablePackages.remove(packageName);
            synchronized (mAppIds) {
                // The package's persisted identity is authoritative; retain it across
                // root moves and repair a missing/older relative-counter map in memory.
                mAppIds.put(packageName, bPackageSettings.appId);
                mCurrUid = Math.max(mCurrUid, bPackageSettings.appId - Process.FIRST_APPLICATION_UID);
            }
            SharedUserSetting.rememberPackageIdentity(bPackageSettings.pkg.mSharedUserId, bPackageSettings.appId);
            Slog.d(TAG, "loaded Package: " + packageName);
        } catch (Throwable e) {
            Slog.e(TAG, "action=scanPackage stage=" + stage + " package=" + packageName
                    + " result=failed files=retained", e);

            // A new namespace, temporarily unavailable host APK or damaged metadata
            // is not a user request to uninstall. Keep the APK, private data and any
            // last successfully loaded state; refuse later blind replacement.
            mUnreadablePackages.add(packageName);
            Slog.w(TAG, "Package metadata needs recovery; files retained: " + packageName);
        } finally {
            packageSettingsIn.recycle();
        }
    }

    public void removePackage(String packageName) {
        mPackages.remove(packageName);
        mUnreadablePackages.remove(packageName);
    }

}
