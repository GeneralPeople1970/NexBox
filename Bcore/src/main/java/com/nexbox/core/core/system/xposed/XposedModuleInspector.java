package com.nexbox.core.core.system.xposed;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Bundle;

import java.io.File;
import java.io.IOException;
import java.util.zip.ZipFile;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.entity.pm.InstalledModule;
import com.nexbox.core.xposed.ModuleMetadata;

final class XposedModuleInspector {
    static final int API_VERSION = ModuleMetadata.MODERN_API;

    static boolean isCandidate(PackageInfo info) {
        return info != null && XposedModulePolicy.isCandidate(info.applicationInfo);
    }

    static InstalledModule inspect(PackageInfo info, int userId) {
        InstalledModule result = new InstalledModule();
        result.packageName = info.packageName;
        result.userId = userId;
        result.applicationInfo = info.applicationInfo;
        result.versionName = info.versionName;
        result.versionCode = info.getLongVersionCode();
        try {
            if (info.applicationInfo == null) throw new IOException("Module application information is unavailable");
            Bundle metadata = info.applicationInfo.metaData;
            ModuleMetadata parsed;
            try (ZipFile archive = new ZipFile(info.applicationInfo.sourceDir)) {
                result.modern = archive.getEntry(ModuleMetadata.MODERN_ENTRY) != null;
                verifyPaths(info.applicationInfo, userId);
                parsed = ModuleMetadata.readArchive(archive);
            }
            result.modern = parsed.modern;
            result.recommendedScope.addAll(parsed.scope);
            result.staticScope = parsed.staticScope;
            result.entryPoints.addAll(parsed.entries);
            if (parsed.modern) {
                result.minXposedVersion = parsed.minApi;
                result.supported = true;
                return result;
            }
            if (metadata == null
                    || !ModuleMetadata.declaresLegacy(legacyMetadataValue(metadata, "xposedmodule"))) {
                throw new IOException("Not a legacy Xposed module (xposedmodule=true is required)");
            }
            Resources resources = null;
            try {
                resources = NexBoxCore.getPackageManager().getResourcesForApplication(info.applicationInfo);
            } catch (Exception ignored) { }
            result.description = metadataString(legacyMetadataValue(metadata, "xposeddescription"), resources);
            result.minXposedVersion = ModuleMetadata.legacyMinimum(
                    legacyMetadataValue(metadata, "xposedminversion"));
            result.supported = true;
        } catch (Exception e) {
            result.supported = false;
            result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return result;
    }

    // Legacy keys accept mixed scalar values and integer resource IDs. Preserve the original
    // value so each consumer retains its own type checks and malformed-value failure behavior.
    @SuppressWarnings("deprecation")
    private static Object legacyMetadataValue(Bundle metadata, String key) {
        return metadata.get(key);
    }

    private static String metadataString(Object value, Resources resources) {
        if (value instanceof String) return (String) value;
        if (value instanceof Integer && resources != null) {
            try { return resources.getString((Integer) value); } catch (Resources.NotFoundException ignored) { }
        }
        return null;
    }

    private static void verifyPaths(ApplicationInfo info, int userId) throws Exception {
        String expectedData = BEnvironment.getDataDir(info.packageName, userId).getCanonicalPath();
        if (info.dataDir == null || !expectedData.equals(new File(info.dataDir).getCanonicalPath())) {
            throw new IOException("Module data directory does not belong to this virtual user");
        }
        File base = new File(info.sourceDir);
        verifyApk(base);
        String ownedRoot = BEnvironment.getAppDir(info.packageName).getCanonicalPath() + File.separator;
        boolean ownedApk = base.getCanonicalPath().startsWith(ownedRoot);
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) {
                if (path == null) throw new IOException("Missing module split APK path");
                File split = new File(path);
                if (ownedApk && !split.getCanonicalPath().startsWith(ownedRoot)) {
                    throw new IOException("Incomplete import: module splits are outside its container package");
                }
                verifyApk(split);
            }
        }
    }

    private static void verifyApk(File file) throws Exception {
        if (!file.isFile() || !file.canRead()) throw new IOException("Module APK is missing or unreadable: " + file.getName());
        // The property that matters is that the code-loading process (the guest, which shares the
        // host UID) cannot rewrite the APK between validation and dex load. A system-owned APK
        // referenced by a regular clone (e.g. /data/app/.../base.apk, mode 0644) satisfies this even
        // though its owner-write bit is set, so test real writability rather than raw mode bits.
        if (file.canWrite()) {
            throw new IOException("Module APK must be read-only before dynamic code loading");
        }
    }

}
