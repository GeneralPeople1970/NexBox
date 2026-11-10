package com.nexbox.core.xposed;

import android.app.Application;
import android.app.AppComponentFactory;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import dalvik.system.DexClassLoader;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Guest-process only entry point. Bcore owns trust, scope, process isolation and APK installation. */
public final class XposedRuntime {
    public static final int LEGACY_API_VERSION = ModuleMetadata.LEGACY_API;
    public static final int MODERN_API_VERSION = ModuleMetadata.MODERN_API;
    private static volatile Map<String, File> preferenceRoots = Collections.emptyMap();
    private static final Map<String, Result> RESULTS = new HashMap<>();
    private static final List<ClassLoader> MODULE_LOADERS = new ArrayList<>();
    private static volatile ApplicationInfo currentApplicationInfo;
    private static volatile String currentPackage;
    private static volatile String currentProcess;
    private static String boundGuest;
    private static boolean packageCallbacksDispatched;
    private static final Map<String, ModernModule> MODERN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final List<ModuleSpec> LEGACY = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Set<String> READY_PACKAGES = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static Context moduleContext;
    private static LoadReporter loadReporter;
    private static boolean productionLifecycle;

    private XposedRuntime() {}

    public static final class ModuleSpec {
        public final String packageName, baseApkPath, nativeLibraryDir, dataDir;
        public final String[] splitApkPaths;
        public final ApplicationInfo applicationInfo;
        public ModuleSpec(String packageName, String baseApkPath, String[] splitApkPaths,
                          String nativeLibraryDir, String dataDir) {
            this.packageName = Objects.requireNonNull(packageName);
            this.baseApkPath = Objects.requireNonNull(baseApkPath);
            this.splitApkPaths = splitApkPaths == null ? new String[0] : splitApkPaths.clone();
            this.nativeLibraryDir = nativeLibraryDir;
            this.dataDir = Objects.requireNonNull(dataDir);
            this.applicationInfo = new ApplicationInfo();
            this.applicationInfo.packageName = packageName;
            this.applicationInfo.sourceDir = baseApkPath;
            this.applicationInfo.publicSourceDir = baseApkPath;
            this.applicationInfo.splitSourceDirs = this.splitApkPaths;
            this.applicationInfo.nativeLibraryDir = nativeLibraryDir;
            this.applicationInfo.dataDir = dataDir;
        }
        public ModuleSpec(ApplicationInfo info) {
            this.packageName = Objects.requireNonNull(info.packageName);
            this.baseApkPath = Objects.requireNonNull(info.sourceDir);
            this.splitApkPaths = info.splitSourceDirs == null ? new String[0] : info.splitSourceDirs.clone();
            this.nativeLibraryDir = info.nativeLibraryDir;
            this.dataDir = Objects.requireNonNull(info.dataDir);
            this.applicationInfo = new ApplicationInfo(info);
        }
    }

    public interface LoadReporter {
        void onResult(String modulePackage, boolean loaded, String detail);
    }

    private static final class Result {
        final boolean loaded;
        final String detail;
        Result(boolean loaded, String detail) { this.loaded = loaded; this.detail = detail; }
    }

    /** Called before ContextImpl creates the guest LoadedApk/classloader/component factory. */
    public static void prepareModules(Context context, String packageName, String processName,
            int userId, ApplicationInfo applicationInfo, List<ModuleSpec> modules, LoadReporter reporter) {
        prepare(context, packageName, processName, userId, applicationInfo, modules, reporter, true);
    }

    private static boolean prepare(Context context, String packageName, String processName,
            int userId, ApplicationInfo applicationInfo, List<ModuleSpec> modules,
            LoadReporter reporter, boolean observeLifecycle) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(applicationInfo);
        Objects.requireNonNull(modules);
        String guest = userId + ":" + packageName + ":" + processName;
        synchronized (XposedRuntime.class) {
            if (boundGuest != null) {
                if (boundGuest.equals(guest)) return true;
                for (ModuleSpec module : modules) report(reporter, module.packageName,
                        new Result(false, "A native hook process cannot be rebound to a different guest"));
                return false;
            }
            boundGuest = guest;
            currentPackage = Objects.requireNonNull(packageName);
            currentProcess = Objects.requireNonNull(processName);
            currentApplicationInfo = new ApplicationInfo(applicationInfo);
            moduleContext = context;
            loadReporter = reporter;
            productionLifecycle = observeLifecycle;
        }
        Map<String, File> roots = new HashMap<>();
        for (ModuleSpec module : modules) {
            try {
                roots.put(module.packageName, new File(module.dataDir, "shared_prefs").getCanonicalFile());
            } catch (IOException ignored) { }
        }
        preferenceRoots = Collections.unmodifiableMap(roots);
        if (modules.isEmpty()) return true; // No ART hooks for a disabled framework.
        if (!NativeBridge.initializeGuest()) {
            for (ModuleSpec module : modules)
                report(reporter, module.packageName, new Result(false, NativeBridge.getError()));
            return false;
        }
        Throwable lifecycleFailure = null;
        if (observeLifecycle) {
            try { GuestPackageLifecycle.install(context.getPackageName()); }
            catch (Throwable error) { lifecycleFailure = error; XposedBridge.log(error); }
        }
        for (ModuleSpec module : modules) {
            try {
                ModuleMetadata metadata = ModuleMetadata.read(module.baseApkPath);
                if (metadata.modern) {
                    if (lifecycleFailure != null)
                        throw new IllegalStateException("Cannot observe the guest package lifecycle", lifecycleFailure);
                    MODERN.put(module.packageName, new ModernModule(module.applicationInfo, metadata,
                            createModuleLoader(context, module, metadata), processName));
                } else LEGACY.add(module);
            } catch (Throwable error) { record(module.packageName, failure(error)); }
        }
        return true;
    }

    /** Direct embedding/test entry point; Bcore calls prepareModules before constructing the guest. */
    public static void loadModules(Context context, ClassLoader guestClassLoader,
            String packageName, String processName, int userId, ApplicationInfo applicationInfo,
            List<ModuleSpec> modules, LoadReporter reporter) {
        loadModules(context, guestClassLoader, packageName, processName, userId, applicationInfo,
                modules, reporter, guestClassLoader, new AppComponentFactory());
    }

    public static void loadModules(Context context, ClassLoader guestClassLoader,
            String packageName, String processName, int userId, ApplicationInfo applicationInfo,
            List<ModuleSpec> modules, LoadReporter reporter, ClassLoader defaultLoader, AppComponentFactory factory) {
        if (!prepare(context, packageName, processName, userId, applicationInfo, modules, reporter, false)) return;
        if (!productionLifecycle) dispatchPackageLoaded(applicationInfo, defaultLoader);
        // Covers cached LoadedApk paths. ModernModule rejects a missed early phase, never fakes it.
        dispatchPackageReady(applicationInfo, defaultLoader, guestClassLoader, factory);
    }

    static void dispatchPackageLoaded(ApplicationInfo info, ClassLoader defaultLoader) {
        for (ModernModule module : MODERN.values())
            module.packageLoaded(info, defaultLoader, info.packageName.equals(currentPackage));
    }

    static void dispatchPackageReady(ApplicationInfo info, ClassLoader defaultLoader,
                                     ClassLoader loader, AppComponentFactory factory) {
        if (!READY_PACKAGES.add(info.packageName)) return;
        boolean first = info.packageName.equals(currentPackage);
        for (Map.Entry<String, ModernModule> entry : MODERN.entrySet()) {
            ModernModule module = entry.getValue();
            module.packageReady(info, defaultLoader, loader, factory, first);
            if (module.failure() != null) record(entry.getKey(), failure(module.failure()));
            else if (first) record(entry.getKey(), new Result(true,
                    "libxposed API " + MODERN_API_VERSION + " Java callbacks loaded in guest process"));
        }
        if (first) {
            for (ModuleSpec spec : LEGACY) record(spec.packageName, loadOne(moduleContext, loader, spec));
            LEGACY.clear();
            if (!packageCallbacksDispatched) {
                packageCallbacksDispatched = true;
                XposedBridge.dispatchLoadPackage(newPackageParam(loader));
            }
        }
    }

    private static void record(String module, Result result) {
        synchronized (RESULTS) { RESULTS.put(module, result); }
        report(loadReporter, module, result);
    }

    private static Result failure(Throwable error) {
        XposedBridge.log(error);
        return new Result(false, error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
    }

    private static ClassLoader createModuleLoader(Context context, ModuleSpec module, ModuleMetadata metadata)
            throws IOException {
        File apk = new File(module.baseApkPath).getCanonicalFile();
        if (!apk.isFile() || !apk.canRead()) throw new IOException("Module APK is not readable");
        StringBuilder dexPath = new StringBuilder(apk.getPath());
        for (String split : module.splitApkPaths) {
            File file = new File(split).getCanonicalFile();
            if (!file.isFile() || !file.canRead()) throw new IOException("Module split APK is not readable");
            dexPath.append(File.pathSeparator).append(file.getPath());
        }
        return new ModuleClassLoader(dexPath.toString(), context.getCodeCacheDir().getAbsolutePath(),
                module.nativeLibraryDir, metadata.modern && metadata.targetApi >= 102);
    }

    private static Result loadOne(Context context, ClassLoader guestClassLoader, ModuleSpec module) {
        XposedBridge.beginModuleLoad();
        boolean success = false;
        try {
            File apk = new File(module.baseApkPath).getCanonicalFile();
            if (!apk.isFile() || !apk.canRead()) throw new IOException("Module APK is not readable");
            ModuleMetadata metadata = ModuleMetadata.read(apk.getPath());
            List<String> entries = metadata.entries;
            ClassLoader loader = createModuleLoader(context, module, metadata);
            boolean skippedResources = false;
            for (String entry : entries) {
                Class<?> type = Class.forName(entry, false, loader);
                boolean javaEntry = IXposedHookLoadPackage.class.isAssignableFrom(type)
                        || IXposedHookZygoteInit.class.isAssignableFrom(type);
                if (!javaEntry)
                    throw new UnsupportedOperationException("No supported Java callback in " + entry
                            + "; resource/native-only modules are unsupported");
                Object instance = type.getDeclaredConstructor().newInstance();
                if (instance instanceof IXposedHookZygoteInit zygote) {
                    IXposedHookZygoteInit.StartupParam param = new IXposedHookZygoteInit.StartupParam();
                    param.modulePath = apk.getPath();
                    param.startsSystemServer = false;
                    zygote.initZygote(param); // Compatibility callback in this guest, never system Zygote.
                }
                if (instance instanceof IXposedHookLoadPackage packageHook)
                    packageHook.handleLoadPackage(newPackageParam(guestClassLoader));
                skippedResources |= instance instanceof IXposedHookInitPackageResources;
            }
            MODULE_LOADERS.add(loader);
            success = true;
            return new Result(true, skippedResources
                    ? "Java callbacks loaded; resource hooks are unsupported"
                    : "Java callbacks loaded in guest process (not a system Xposed framework)");
        } catch (Throwable failure) {
            XposedBridge.log(failure);
            return new Result(false, failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage()));
        } finally {
            XposedBridge.finishModuleLoad(success);
        }
    }

    private static XC_LoadPackage.LoadPackageParam newPackageParam(ClassLoader loader) {
        XC_LoadPackage.LoadPackageParam param = new XC_LoadPackage.LoadPackageParam(new XposedBridge.CopyOnWriteSortedSet<>());
        param.packageName = currentPackage;
        param.processName = currentProcess;
        param.appInfo = currentApplicationInfo;
        param.classLoader = loader;
        param.isFirstApplication = true;
        return param;
    }

    private static void report(LoadReporter reporter, String module, Result result) {
        if (reporter == null) return;
        try { reporter.onResult(module, result.loaded, result.detail); }
        catch (Throwable failure) { XposedBridge.log(failure); }
    }

    /** Resolve only enabled modules' preferences in this guest user, never host/global /data/data. */
    public static File resolvePreferences(String packageName, String preferenceName) {
        File root = preferenceRoots.get(packageName);
        if (root == null) throw new SecurityException("Module is not in this guest's scope");
        if (preferenceName == null || !preferenceName.matches("[A-Za-z0-9_$.-]+")
                || preferenceName.equals(".") || preferenceName.equals(".."))
            throw new SecurityException("Invalid preference filename");
        try {
            File file = new File(root, preferenceName + ".xml").getCanonicalFile();
            if (!root.equals(file.getParentFile())) throw new SecurityException("Preferences escaped their module directory");
            return file;
        } catch (IOException failure) { throw new SecurityException(failure); }
    }

    public static File validatePreferencesFile(File requested) {
        try {
            File file = requested.getCanonicalFile();
            if (!file.getName().endsWith(".xml")) throw new SecurityException("Not a preference XML file");
            if (preferenceRoots.containsValue(file.getParentFile())) return file;
        } catch (IOException failure) { throw new SecurityException(failure); }
        throw new SecurityException("Preferences are outside this guest's module scope");
    }

    public static String currentPackageName() { return currentPackage; }
    public static String currentProcessName() { return currentProcess; }
    public static ApplicationInfo currentApplicationInfo() { return currentApplicationInfo; }
    public static Application currentApplication() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Application application = (Application) thread.getMethod("currentApplication").invoke(null);
            return application != null && application.getPackageName().equals(currentPackage) ? application : null;
        } catch (Throwable ignored) { return null; }
    }

    /** API identity is parent-first even when a module incorrectly bundles Xposed API classes. */
    private static final class ApiClassLoader extends ClassLoader {
        ApiClassLoader() { super(ClassLoader.getSystemClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("io.github.libxposed.api.")
                    || name.startsWith("de.robv.android.xposed.") || name.equals("android.app.AndroidAppHelper")
                    || name.startsWith("android.content.res.X"))
                return XposedRuntime.class.getClassLoader().loadClass(name);
            return super.loadClass(name, resolve);
        }
    }

    private static final class ModuleClassLoader extends DexClassLoader {
        private final boolean modernOnly;
        ModuleClassLoader(String dexPath, String cache, String libs, boolean modernOnly) {
            super(dexPath, cache, libs, new ApiClassLoader());
            this.modernOnly = modernOnly;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (modernOnly && (name.startsWith("de.robv.android.xposed.")
                    || name.equals("android.app.AndroidAppHelper") || name.startsWith("android.content.res.X")))
                throw new ClassNotFoundException("Modules targeting API 102 cannot use legacy Xposed APIs: " + name);
            return super.loadClass(name, resolve);
        }
    }
}
