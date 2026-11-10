package com.nexbox.core.core.env;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.utils.BzFileUtils;


public class BEnvironment {
    private static final VirtualStorageLayout STORAGE = openStorage();
    private static final File sVirtualRoot = STORAGE.internalRoot();
    private static final File sExternalVirtualRoot = STORAGE.externalRoot();

    private static VirtualStorageLayout openStorage() {
        try {
            return VirtualStorageLayout.open(NexBoxCore.getContext().getCacheDir().getParentFile(),
                    NexBoxCore.getContext().getExternalFilesDir(null));
        } catch (IOException failure) {
            throw new IllegalStateException("Container storage needs recovery; existing data was preserved", failure);
        }
    }

    public static String relocateLegacyPath(String path) { return STORAGE.relocate(path); }

    public static Map<String, String> getLegacyRootMappings() { return STORAGE.historicalRoots(); }

    public static final File EMPTY_JAR = new File(getCacheDir(), "empty.apk");

    public static void load() {
        BzFileUtils.mkdirs(sVirtualRoot);
        BzFileUtils.mkdirs(sExternalVirtualRoot);
        BzFileUtils.mkdirs(getSystemDir());
        BzFileUtils.mkdirs(getCacheDir());
        BzFileUtils.mkdirs(getProcDir());
    }

    public static File getVirtualRoot() {
        return sVirtualRoot;
    }

    public static File getExternalVirtualRoot() {
        return sExternalVirtualRoot;
    }

    public static File getSystemDir() {
        return new File(sVirtualRoot, "system");
    }

    public static File getProcDir() {
        return new File(sVirtualRoot, "proc");
    }

    public static File getCacheDir() {
        return new File(sVirtualRoot, "cache");
    }

    public static File getUserInfoConf() {
        return new File(getSystemDir(), "user.conf");
    }

    public static File getAccountsConf() {
        return new File(getSystemDir(), "accounts.conf");
    }

    public static File getUidConf() {
        return new File(getSystemDir(), "uid.conf");
    }

    public static File getSharedUserConf() {
        return new File(getSystemDir(), "shared-user.conf");
    }

    public static File getXPModuleConf() {
        return new File(getSystemDir(), "xposed-module.conf");
    }

    /** Versioned scoped state; the old text file remains a read-only migration source. */
    public static File getXPStateConf() {
        return new File(getSystemDir(), "xposed-state.bin");
    }

    public static File getFakeLocationConf() {
        return new File(getSystemDir(), "fake-location.conf");
    }

    public static File getPackageConf(String packageName) {
        return new File(getAppDir(packageName), "package.conf");
    }

    public static File getExternalUserDir(int userId) {
        if (sExternalVirtualRoot == null) {
            throw new IllegalStateException("External virtual storage is unavailable");
        }
        return new File(sExternalVirtualRoot, String.format(Locale.CHINA, "storage/emulated/%d/", userId));
    }

    public static File getUserDir(int userId) {
        return new File(sVirtualRoot, String.format(Locale.CHINA, "data/user/%d", userId));
    }

    public static File getDeUserDir(int userId) {
        return new File(sVirtualRoot, "data/user_de/" + userId);
    }

    public static File getExternalObbDir(String packageName, int userId) {
        return new File(getExternalUserDir(userId), String.format(Locale.CHINA, "Android/obb/%s", packageName));
    }


    public static File getDeDataDir(String packageName, int userId) {
        return new File(sVirtualRoot, String.format(Locale.CHINA, "data/user_de/%d/%s", userId, packageName));
    }

    public static File getExternalDataDir(String packageName, int userId) {
        return new File(getExternalUserDir(userId), String.format(Locale.CHINA, "Android/data/%s", packageName));
    }


    public static File getDataDir(String packageName, int userId) {
        return new File(sVirtualRoot, String.format(Locale.CHINA, "data/user/%d/%s", userId, packageName));
    }

    public static File getProcDir(int pid) {
        File file = new File(getProcDir(), String.format(Locale.CHINA, "%d", pid));
        BzFileUtils.mkdirs(file);
        return file;
    }

    public static File getExternalDataFilesDir(String packageName, int userId) {
        return new File(getExternalDataDir(packageName, userId), "files");
    }

    public static File getDataFilesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "files");
    }

    public static File getExternalDataCacheDir(String packageName, int userId) {
        return new File(getExternalDataDir(packageName, userId), "cache");
    }

    public static File getDataCacheDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "cache");
    }

    public static File getDataLibDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "lib");
    }

    public static File getDataDatabasesDir(String packageName, int userId) {
        return new File(getDataDir(packageName, userId), "databases");
    }

    public static File getAppRootDir() {
        return getAppDir("");
    }

    public static File getPackageInstallerSessionsDir() {
        return new File(sVirtualRoot, "data/package-installer-sessions");
    }

    public static File getAppDir(String packageName) {
        return new File(sVirtualRoot, "data/app/" + packageName);
    }

    public static File getBaseApkDir(String packageName) {
        return new File(sVirtualRoot, "data/app/" + packageName + "/base.apk");
    }

    public static File getSplitApkDir(String packageName) {
        return new File(getAppDir(packageName), "splits");
    }

    public static File getAppLibDir(String packageName) {
        return new File(getAppDir(packageName), "lib");
    }

    public static File getXSharedPreferences(String packageName, String prefFileName) {
        return getXSharedPreferences(packageName, prefFileName, NexBoxCore.getUserId());
    }

    public static File getXSharedPreferences(String packageName, String prefFileName, int userId) {
        if (userId < 0 || prefFileName == null || prefFileName.isEmpty()
                || prefFileName.contains("/") || prefFileName.contains("\\") || prefFileName.equals("..")) {
            throw new IllegalArgumentException("A virtual user and a preference basename are required");
        }
        return new File(getDataDir(packageName, userId), "shared_prefs/" + prefFileName + ".xml");
    }
}
