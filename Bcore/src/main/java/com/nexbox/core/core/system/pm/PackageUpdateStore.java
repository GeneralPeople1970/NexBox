package com.nexbox.core.core.system.pm;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.system.Os;
import android.system.OsConstants;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.utils.NativeUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.PackageParserCompat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

/** Immutable code generations; package.conf is the only published generation pointer. */
final class PackageUpdateStore {
    private static final String VERSIONS = "versions";
    private PackageUpdateStore() { }

    static PackageParser.Package parse(File directory) throws Throwable {
        PackageParser parser = PackageParserCompat.createParser(directory);
        PackageParser.Package parsed = PackageParserCompat.parsePackage(parser, directory, 0);
        if (parsed == null || parsed.applicationInfo == null) throw new IOException("Invalid APK cluster");
        PackageParserCompat.collectCertificates(parser, parsed, 0, false);
        parsed.applicationInfo.sourceDir = parsed.baseCodePath;
        parsed.applicationInfo.publicSourceDir = parsed.baseCodePath;
        parsed.applicationInfo.splitNames = parsed.splitNames;
        parsed.applicationInfo.splitSourceDirs = parsed.splitCodePaths;
        parsed.applicationInfo.splitPublicSourceDirs = parsed.splitCodePaths;
        return parsed;
    }

    static BPackageSettings prepare(BPackageSettings previous, File input) throws Throwable {
        PackageParser.Package parsed = parse(input);
        BPackage candidate = new BPackage(parsed);
        if (!previous.pkg.packageName.equals(candidate.packageName)
                || !Objects.equals(previous.pkg.mSharedUserId, candidate.mSharedUserId)) {
            throw new SecurityException("Update package or shared UID does not match the installed package");
        }
        // Exact signer identity is deliberate. Certificate rotation needs capability-aware
        // lineage validation; accepting only an ancestor certificate is unsafe.
        requireSameSigners(previous.pkg.mSignatures, candidate.mSignatures);
        if (version(parsed.baseCodePath) < version(previous.pkg.baseCodePath)) {
            throw new IllegalArgumentException("Package downgrade is not supported");
        }
        File root = new File(BEnvironment.getAppDir(candidate.packageName), VERSIONS);
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create code storage");
        File generation = new File(root, UUID.randomUUID().toString());
        if (!generation.mkdir()) throw new IOException("Cannot create code generation");
        boolean ready = false;
        try {
            copy(new File(parsed.baseCodePath), new File(generation, "base.apk"));
            if (parsed.splitCodePaths != null) {
                for (int i = 0; i < parsed.splitCodePaths.length; i++) {
                    copy(new File(parsed.splitCodePaths[i]), new File(generation, "split-" + i + ".apk"));
                }
            }
            PackageParser.Package stored = parse(generation);
            File libraries = new File(generation, "lib");
            NativeUtils.prepareNativeLibraries(generation, stored.applicationInfo, libraries);
            syncTree(generation);
            syncDirectory(root);
            BPackageSettings next = new BPackageSettings();
            next.pkg = new BPackage(stored);
            next.appId = previous.appId;
            next.installOption = InstallOption.installByStorage();
            next.pkg.installOption = next.installOption;
            next.pkg.mExtras = next;
            for (Integer userId : previous.userState.keySet()) {
                next.userState.put(userId, new BPackageUserState(previous.userState.get(userId)));
            }
            ready = true;
            return next;
        } finally {
            if (!ready) removeTree(generation);
        }
    }

    static void requireSameSigners(Signature[] oldSigners, Signature[] newSigners) {
        if (oldSigners == null || newSigners == null || oldSigners.length == 0
                || oldSigners.length != newSigners.length
                || !new HashSet<>(Arrays.asList(oldSigners)).equals(new HashSet<>(Arrays.asList(newSigners)))) {
            throw new SecurityException("Update signing certificates do not match");
        }
    }

    static long version(String path) throws IOException {
        PackageInfo info = NexBoxCore.getPackageManager().getPackageArchiveInfo(path, 0);
        if (info == null) throw new IOException("Cannot read package version");
        return info.getLongVersionCode();
    }

    static void configureNativeLibraryPaths(BPackage pkg, ApplicationInfo application) {
        File code = new File(pkg.baseCodePath).getParentFile();
        File versions = new File(BEnvironment.getAppDir(pkg.packageName), VERSIONS);
        File libraries = code != null && versions.equals(code.getParentFile())
                ? new File(code, "lib") : BEnvironment.getAppLibDir(pkg.packageName);
        NativeUtils.configureNativeLibraryPaths(application, libraries);
    }

    static void copy(File source, File target) throws IOException {
        if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("APK source is not a regular file");
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (target.length() == 0 || target.length() != source.length() || !target.setReadOnly()) {
            throw new IOException("Incomplete immutable APK copy");
        }
    }

    private static void syncTree(File directory) throws Exception {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot enumerate code generation");
        for (File file : files) {
            if (file.isDirectory()) syncTree(file);
            else try (FileInputStream input = new FileInputStream(file)) { input.getFD().sync(); }
        }
        syncDirectory(directory);
    }

    private static void syncDirectory(File directory) throws Exception {
        FileDescriptor descriptor = Os.open(directory.getPath(),
                OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
                        | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw new IOException("Code storage path is not a directory");
            }
            Os.fsync(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    /** Retain the current and immediately previous generation, including after a crash. */
    static void prune(BPackage current, BPackage previous) {
        File root = new File(BEnvironment.getAppDir(current.packageName), VERSIONS);
        File currentDir = new File(current.baseCodePath).getParentFile();
        File previousDir = new File(previous.baseCodePath).getParentFile();
        File[] entries = root.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.equals(currentDir) && !entry.equals(previousDir)) {
                try { removeTree(entry); }
                catch (IOException error) { Slog.w("PackageUpdateStore", "Code generation cleanup deferred", error); }
            }
        }
    }

    static void discard(BPackage candidate) throws IOException {
        File generation = new File(candidate.baseCodePath).getParentFile();
        File root = new File(BEnvironment.getAppDir(candidate.packageName), VERSIONS);
        if (generation == null || !root.equals(generation.getParentFile())) throw new IOException("Invalid code generation");
        removeTree(generation);
    }

    static void removeTree(File file) throws IOException {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            File[] entries = file.listFiles();
            if (entries == null) throw new IOException("Cannot enumerate staging directory");
            for (File child : entries) removeTree(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
