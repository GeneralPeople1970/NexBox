package com.nexbox.core.utils;

import android.content.pm.ApplicationInfo;

import black.android.content.pm.BRApplicationInfoL;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.nexbox.core.NexBoxCore;


public class AbiUtils {
    private final Set<String> mLibs = new HashSet<>();
    private boolean archiveReadable;

    /**
     * Keep generated reflection proxies behind the core's public API. Bcore is itself shrunk
     * before the app's Release R8 pass, so app code must not link directly to those proxies.
     * Unknown fields remain unknown: callers should inspect the archive as a fallback.
     */
    public static String[] getInstalledAbis(ApplicationInfo application) {
        try {
            var info = BRApplicationInfoL.get(application);
            return new String[]{info.primaryCpuAbi(), info.secondaryCpuAbi()};
        } catch (RuntimeException failure) {
            Slog.w("AbiUtils", "Installed ABI fields unavailable; inspecting the APK instead", failure);
            return new String[0];
        }
    }

    public static boolean isSupport(File apkFile) {
        // Installation is infrequent; a File-only cache outlives in-place APK updates
        // and can turn a previous failed read into a permanently accepted archive.
        AbiUtils abiUtils = new AbiUtils(apkFile);
        if (!abiUtils.isArchiveReadable()) return false;
        if (abiUtils.isEmptyAib()) {
            return true;
        }

        if (NexBoxCore.is64Bit()) {
            return abiUtils.is64Bit();
        } else {
            return abiUtils.is32Bit();
        }
    }

    public AbiUtils(File apkFile) {
        if (apkFile == null) {
            Slog.w("AbiUtils", "action=inspectArchive result=unavailable reason=source_unreadable");
            return;
        }
        try (ZipFile zipFile = new ZipFile(apkFile)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                String name = zipEntry.getName();
                if (!zipEntry.isDirectory() && name.startsWith("lib/") && name.endsWith(".so")) {
                    int separator = name.indexOf('/', 4);
                    if (separator > 4 && separator < name.length() - 1
                            && name.indexOf('/', separator + 1) < 0) {
                        // Keep unsupported ABIs too: an x86-only APK is not Java-only.
                        mLibs.add(name.substring(4, separator));
                    }
                }
            }
            archiveReadable = true;
        } catch (IOException | SecurityException failure) {
            mLibs.clear();
            archiveReadable = false;
            Slog.w("AbiUtils", "action=inspectArchive result=failed reason="
                    + failure.getClass().getSimpleName());
        }
    }

    public boolean isArchiveReadable() {
        return archiveReadable;
    }

    public boolean is64Bit() {
        return mLibs.contains("arm64-v8a");
    }

    public boolean is32Bit() {
        return mLibs.contains("armeabi") || mLibs.contains("armeabi-v7a");
    }

    public boolean isEmptyAib() {
        return archiveReadable && mLibs.isEmpty();
    }
}
