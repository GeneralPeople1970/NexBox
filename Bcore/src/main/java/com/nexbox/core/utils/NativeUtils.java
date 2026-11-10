package com.nexbox.core.utils;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import black.android.content.pm.BRApplicationInfoL;
import black.com.android.internal.content.BRNativeLibraryHelper;
import black.com.android.internal.content.BRNativeLibraryHelperHandle;

public final class NativeUtils {
    private static final String TAG = "NativeUtils";
    // Hidden PackageManager result codes, stable across the supported Android versions.
    private static final int INSTALL_SUCCEEDED = 1;
    private static final int NO_NATIVE_LIBRARIES = -114;

    private NativeUtils() { }

    /** packageSource is the base APK or the parser's complete, flat split staging directory. */
    public static void prepareNativeLibraries(File packageSource, ApplicationInfo application,
                                              File libraryRoot) throws IOException {
        if (application == null) throw new IOException("APK application metadata is missing");
        Object handle = BRNativeLibraryHelperHandle.getWithException().create(packageSource);
        if (!(handle instanceof Closeable)) {
            throw new IOException("Android native library helper is unavailable");
        }
        try (Closeable ignored = (Closeable) handle) {
            boolean extract = extractsNativeLibraries(application);
            if (BRNativeLibraryHelperHandle.getWithException(handle).extractNativeLibs() != extract) {
                throw new IOException("Native library policy does not match the base APK manifest");
            }
            String[] abis = supportedRuntimeAbis();
            Integer index = BRNativeLibraryHelper.getWithException().findSupportedAbi(handle, abis);
            if (index == null || (index != NO_NATIVE_LIBRARIES && (index < 0 || index >= abis.length))) {
                throw new IOException("APK native code is incompatible with the container CPU architecture"
                        + " (status=" + index + ")");
            }
            if (!libraryRoot.isDirectory() && !libraryRoot.mkdirs()) {
                throw new IOException("Could not create the native library root directory");
            }
            String abi = index == NO_NATIVE_LIBRARIES ? null : abis[index];
            if (abi != null) {
                File directory = nativeLibraryDirectory(libraryRoot, abi, extract);
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create the native library directory");
                }
                // Android validates compression and page alignment even when no extraction
                // is requested. It also chooses one ABI across the entire base/split set.
                Integer status = BRNativeLibraryHelper.getWithException()
                        .copyNativeBinaries(handle, directory, abi);
                if (status == null || status != INSTALL_SUCCEEDED) {
                    throw new IOException("Could not prepare APK native libraries (status=" + status + ")");
                }
            }
            BRApplicationInfoL.getWithException(application)._set_primaryCpuAbi(abi);
            configureNativeLibraryPaths(application, libraryRoot);
            Slog.i(TAG, "action=prepareNativeLibraries package=" + application.packageName
                    + " mode=" + (extract ? "extracted" : "apk") + " abi=" + abi
                    + " nativeLibraryDir=" + application.nativeLibraryDir);
        }
    }

    public static boolean extractsNativeLibraries(ApplicationInfo application) {
        return (application.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0;
    }

    /** Only for stored APKs; host clones retain the system's native and code metadata. */
    public static void configureNativeLibraryPaths(ApplicationInfo application, File libraryRoot) {
        var fields = BRApplicationInfoL.getWithException(application);
        String storedAbi = fields.primaryCpuAbi();
        String abi = TextUtils.isEmpty(storedAbi) ? null
                : selectRuntimeAbi(storedAbi, supportedRuntimeAbis());
        boolean extract = extractsNativeLibraries(application);
        // Packages without JNI code must not be assigned an ABI just because the
        // container has one. Keep the root as the harmless fallback search path;
        // embedded APK paths are added only when primaryCpuAbi is present.
        application.nativeLibraryDir = abi == null
                ? libraryRoot.getAbsolutePath()
                : nativeLibraryDirectory(libraryRoot, abi, extract).getAbsolutePath();
        fields._set_primaryCpuAbi(abi);
        fields._set_secondaryCpuAbi(null);
        fields._set_secondaryNativeLibraryDir(null);
        fields._set_nativeLibraryRootDir(libraryRoot.getAbsolutePath());
        fields._set_nativeLibraryRootRequiresIsa(abi != null && !extract);

        // The parsed/imported package owns its code origin, not the NexBox host APK.
        String codeDirectory = new File(application.sourceDir).getParent();
        fields._set_scanSourceDir(codeDirectory);
        fields._set_scanPublicSourceDir(codeDirectory);
    }

    private static String[] supportedRuntimeAbis() {
        // CPU_ABI describes the device, not necessarily this container process.
        return Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
    }

    static String selectRuntimeAbi(String storedAbi, String[] supportedAbis) {
        if (supportedAbis.length == 0) throw new IllegalStateException("No ABI is available for this process");
        for (String abi : supportedAbis) {
            if (abi.equals(storedAbi)) return abi;
        }
        return supportedAbis[0];
    }

    static File nativeLibraryDirectory(File libraryRoot, String abi, boolean extract) {
        if (extract || TextUtils.isEmpty(abi)) return libraryRoot;
        String instructionSet = switch (abi) {
            case "arm64-v8a" -> "arm64";
            case "armeabi", "armeabi-v7a" -> "arm";
            default -> abi;
        };
        // Old imports left extracted .so files directly in lib/. Never let these shadow
        // APK-backed libraries; use the platform's ISA subdirectory without deleting data.
        return new File(libraryRoot, instructionSet);
    }
}
