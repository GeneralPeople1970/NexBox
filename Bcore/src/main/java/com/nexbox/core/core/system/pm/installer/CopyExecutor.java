package com.nexbox.core.core.system.pm.installer;

import java.io.File;
import java.io.IOException;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.pm.BPackageSettings;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.NativeUtils;
import com.nexbox.core.utils.Slog;

public class CopyExecutor implements Executor {
    private static final String TAG = "CopyExecutor";

    @Override
    public int exec(BPackageSettings ps, InstallOption option, int userId) {
        if (option.isFlag(InstallOption.FLAG_SYSTEM)) return 0;
        try {
            File base = new File(ps.pkg.baseCodePath);
            if (!base.isFile() || base.length() == 0) throw new IOException("Base APK is missing");
            String[] splitPaths = ps.pkg.applicationInfo == null ? null : ps.pkg.applicationInfo.splitSourceDirs;
            if (splitPaths != null) {
                String[] splitNames = ps.pkg.applicationInfo.splitNames;
                if (splitNames == null || splitNames.length != splitPaths.length) {
                    throw new IOException("Split names and paths do not match");
                }
                for (int index = 0; index < splitPaths.length; index++) {
                    String path = splitPaths[index];
                    if (path == null || !new File(path).isFile() || new File(path).length() == 0) {
                        throw new IOException("Required split APK is missing at index " + index);
                    }
                }
            }

            File libraryRoot = BEnvironment.getAppLibDir(ps.pkg.packageName);
            File packageSource = splitPaths != null && splitPaths.length > 0 ? base.getParentFile() : base;
            NativeUtils.prepareNativeLibraries(packageSource, ps.pkg.applicationInfo, libraryRoot);

            File copiedBase = BEnvironment.getBaseApkDir(ps.pkg.packageName);
            copyChecked(base, copiedBase);
            String[] copiedSplits = null;
            if (splitPaths != null && splitPaths.length > 0) {
                File splitDirectory = BEnvironment.getSplitApkDir(ps.pkg.packageName);
                if (!splitDirectory.isDirectory() && !splitDirectory.mkdirs()) {
                    throw new IOException("Could not create the split APK directory");
                }
                copiedSplits = new String[splitPaths.length];
                for (int index = 0; index < splitPaths.length; index++) {
                    File copied = new File(splitDirectory, "split-" + index + ".apk");
                    copyChecked(new File(splitPaths[index]), copied);
                    copiedSplits[index] = copied.getAbsolutePath();
                }
            }

            // Publish paths only after the whole cluster has been copied. Keep the parser's
            // exact split-name order and feature dependency indexes, never skip an entry.
            ps.pkg.baseCodePath = copiedBase.getAbsolutePath();
            if (ps.pkg.applicationInfo != null) {
                ps.pkg.applicationInfo.sourceDir = copiedBase.getAbsolutePath();
                ps.pkg.applicationInfo.publicSourceDir = copiedBase.getAbsolutePath();
                ps.pkg.applicationInfo.splitSourceDirs = copiedSplits;
                ps.pkg.applicationInfo.splitPublicSourceDirs = copiedSplits;
                NativeUtils.configureNativeLibraryPaths(ps.pkg.applicationInfo, libraryRoot);
            }
            Slog.i(TAG, "action=copyPackage package=" + ps.pkg.packageName
                    + " splits=" + (copiedSplits == null ? 0 : copiedSplits.length) + " success=true");
            return 0;
        } catch (Exception error) {
            Slog.e(TAG, "action=copyPackage package=" + ps.pkg.packageName + " success=false", error);
            return -1;
        }
    }

    private static void copyChecked(File source, File target) throws IOException {
        BzFileUtils.copyFile(source, target);
        if (target.length() != source.length() || target.length() == 0L) {
            throw new IOException("Incomplete APK copy");
        }
        if (!target.setReadOnly()) throw new IOException("Could not make the installed APK read-only");
    }
}
