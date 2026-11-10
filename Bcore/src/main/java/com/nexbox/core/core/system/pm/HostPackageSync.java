package com.nexbox.core.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.os.Build;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.AbiUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;

/** Validates one complete host code generation without changing clone identities or data. */
final class HostPackageSync {
    private HostPackageSync() { }

    static BPackageSettings prepare(BPackageSettings previous) throws Throwable {
        if (!previous.installOption.isFlag(InstallOption.FLAG_SYSTEM)) return null;
        PackageInfo host = NexBoxCore.getPackageManager().getPackageInfo(previous.pkg.packageName, 0);
        ApplicationInfo application = host.applicationInfo;
        PackageCodeAvailability.requireAvailable(application);
        long previousVersion = previous.pkg.requireLongVersionCode();
        if (Objects.equals(previous.pkg.baseCodePath, application.sourceDir)
                && previous.pkg.applicationInfo != null
                && Arrays.equals(previous.pkg.applicationInfo.splitSourceDirs, application.splitSourceDirs)
                && previousVersion == host.getLongVersionCode()) return null;

        File base = new File(application.sourceDir).getCanonicalFile();
        File source = application.splitSourceDirs == null || application.splitSourceDirs.length == 0
                ? base : base.getParentFile();
        PackageParser.Package parsed = PackageUpdateStore.parse(source);
        BPackage candidate = new BPackage(parsed);
        if (!previous.pkg.packageName.equals(candidate.packageName)
                || !Objects.equals(previous.pkg.mSharedUserId, candidate.mSharedUserId)) {
            throw new SecurityException("Host update package or shared UID does not match");
        }
        PackageUpdateStore.requireSameSigners(previous.pkg.mSignatures, candidate.mSignatures);
        if (candidate.requireLongVersionCode() < previousVersion) {
            throw new SecurityException("Host update would downgrade the cloned package");
        }
        if (!paths(parsed.applicationInfo).equals(paths(application))) {
            throw new IOException("Parsed host APK/split set does not match the installed package");
        }
        requireSupportedAbi(application);
        PackageInfo latest = NexBoxCore.getPackageManager().getPackageInfo(candidate.packageName, 0);
        if (latest.getLongVersionCode() != host.getLongVersionCode()
                || !paths(latest.applicationInfo).equals(paths(application))) {
            throw new IOException("Host package changed during synchronization; retry");
        }
        PackageCodeAvailability.requireAvailable(latest.applicationInfo);
        candidate.applicationInfo = PackageQueryContext.copyApplication(application);
        candidate.baseCodePath = application.sourceDir;
        candidate.installOption = previous.installOption;
        BPackageSettings next = new BPackageSettings();
        next.pkg = candidate;
        next.appId = previous.appId;
        next.installOption = previous.installOption;
        candidate.mExtras = next;
        for (Integer userId : previous.userState.keySet()) {
            next.userState.put(userId, new BPackageUserState(previous.userState.get(userId)));
        }
        return next;
    }

    private static Set<String> paths(ApplicationInfo application) throws IOException {
        if (application == null || application.sourceDir == null) throw new IOException("Host source missing");
        Set<String> result = new HashSet<>();
        result.add(new File(application.sourceDir).getCanonicalPath());
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) result.add(new File(split).getCanonicalPath());
        }
        return result;
    }

    private static void requireSupportedAbi(ApplicationInfo application) throws IOException {
        Set<String> supported = new HashSet<>(Arrays.asList(NexBoxCore.is64Bit()
                ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS));
        Set<String> installed = new HashSet<>(Arrays.asList(AbiUtils.getInstalledAbis(application)));
        installed.remove(null);
        if (!installed.isEmpty()) {
            if (java.util.Collections.disjoint(supported, installed)) throw new IOException("Host update ABI is unsupported");
            return;
        }
        Set<String> archiveAbis = new HashSet<>();
        for (String path : paths(application)) {
            try (ZipFile apk = new ZipFile(path)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = apk.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("lib/") && name.endsWith(".so")) {
                        int slash = name.indexOf('/', 4);
                        if (slash > 4) archiveAbis.add(name.substring(4, slash));
                    }
                }
            }
        }
        if (!archiveAbis.isEmpty() && java.util.Collections.disjoint(supported, archiveAbis)) {
            throw new IOException("Host update ABI is unsupported");
        }
    }
}
