package com.nexbox.core.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.os.SystemClock;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.app.LoadedApk;
import black.android.content.BRContentProviderClient;
import black.android.content.pm.BRApplicationInfoL;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.configuration.AppLifecycleCallback;
import com.nexbox.core.app.dispatcher.AppServiceDispatcher;
import com.nexbox.core.core.CrashHandler;
import com.nexbox.core.core.GmsCompat;
import com.nexbox.core.xposed.RuntimeLog;
import com.nexbox.core.core.IBActivityThread;
import com.nexbox.core.core.IOCore;
import com.nexbox.core.core.NativeCore;
import com.nexbox.core.core.env.VirtualRuntime;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.am.ReceiverData;

import com.nexbox.core.fake.delegate.AppInstrumentation;
import com.nexbox.core.fake.delegate.ContentProviderDelegate;

import com.nexbox.core.fake.hook.HookManager;
import com.nexbox.core.fake.service.HCallbackProxy;
import com.nexbox.core.entity.pm.InstalledModule;
import com.nexbox.core.fake.frameworks.BXposedManager;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.ActivityIntentLog;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.ActivityManagerCompat;
import com.nexbox.core.utils.compat.ContextCompat;
import com.nexbox.core.utils.compat.StrictModeCompat;
import com.nexbox.core.xposed.XposedRuntime;


public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = NexBoxCore.get().getHandler();
    private static final Object mConfigLock = new Object();
    private final GuestCoreConnection mCoreConnection = new GuestCoreConnection(() -> {
        try {
            Slog.w(TAG, "stage=coreDisconnected action=exitGuest");
        } finally {
            // Keep the captured identity until exit: clearing it would make in-flight
            // guest work fall back to user 0 while its old runtime is still alive.
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    });

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? NexBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (!NexBoxCore.get().isGuestProcess() || appConfig == null || appConfig.token == null) {
                throw new IllegalArgumentException("Guest attachment requires a core process token");
            }
            if (mAppConfig != null && (!mAppConfig.packageName.equals(appConfig.packageName)
                    || !mAppConfig.processName.equals(appConfig.processName)
                    || mAppConfig.userId != appConfig.userId || mAppConfig.userGeneration != appConfig.userGeneration || mAppConfig.bpid != appConfig.bpid
                    || mAppConfig.buid != appConfig.buid || mAppConfig.uid != appConfig.uid
                    || !mAppConfig.token.equals(appConfig.token))) {
                throw new IllegalStateException("Cannot replace a running guest's identity");
            }
            // ProcessRecord is owned by :nexbox_core. Our own asBinder() is local and
            // never reports that server's death.
            mCoreConnection.bind(appConfig.token);
            if (mAppConfig != null) return;
            this.mAppConfig = appConfig;
            RuntimeLog.setProcessContext("userId=" + appConfig.userId + " generation=" + appConfig.userGeneration
                    + " guestPackage=" + appConfig.packageName + " guestProcess=" + appConfig.processName);
            Slog.i(TAG, "stage=processBound " + RuntimeLog.getProcessContext());
        }
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    private static <T, E extends Throwable> T instantiateGuestClass(Class<T> type)
            throws InstantiationException, IllegalAccessException, E {
        try {
            // Keep access checks in the original caller class; guest constructors stay inaccessible
            // when they were inaccessible to Class.newInstance(). Never force accessibility here.
            return type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException missing) {
            throw (InstantiationException) new InstantiationException(type.getName()).initCause(missing);
        } catch (InvocationTargetException wrapped) {
            // Class.newInstance() propagated even checked constructor failures directly. Preserve
            // the original object so existing catch branches, fallback and logging stay unchanged.
            @SuppressWarnings("unchecked")
            E cause = (E) wrapped.getTargetException();
            throw cause;
        }
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        Service service;
        try {
            service = (Service) instantiateGuestClass(classLoader.loadClass(serviceInfo.name));
        } catch (ClassNotFoundException e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services class not found, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.e("BActivityThread", "Container operation failed", e);
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        } catch (Exception e) {
            Slog.e("BActivityThread", "Container operation failed", e);
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = NexBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    NexBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services service creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "Service creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage(), e);
            return null;
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        JobService service;
        try {
            service = (JobService) instantiateGuestClass(classLoader.loadClass(serviceInfo.name));
        } catch (ClassNotFoundException e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService class not found, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.e("BActivityThread", "Container operation failed", e);
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        } catch (Exception e) {
            Slog.e("BActivityThread", "Container operation failed", e);
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = NexBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    NexBoxCore.mainThread(),
                    serviceInfo.name,
                    BActivityThread.currentActivityThread().getActivityThread(),
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "JobService creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage(), e);
            return null;
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            boolean posted = NexBoxCore.get().getHandler().post(() -> {
                try {
                    handleBindApplication(packageName, processName);
                } catch (Throwable cause) {
                    failure.set(cause);
                    // Binding mutates LoadedApk/framework state and cannot be rolled back.
                    // Preserve the main-thread failure instead of leaving a half-bound guest alive.
                    if (cause instanceof Error) throw (Error) cause;
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new IllegalStateException("Guest application binding failed", cause);
                } finally {
                    conditionVariable.open();
                }
            });
            if (!posted) throw new IllegalStateException("Guest main thread is unavailable");
            conditionVariable.block();
            Throwable cause = failure.get();
            if (cause instanceof Error) throw (Error) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause != null) throw new IllegalStateException("Guest application binding failed", cause);
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }

        int virtualUserId = BActivityThread.getUserId();
        int hostUserId = NexBoxCore.getHostUserId();
        PackageInfo packageInfo = NexBoxCore.getBPackageManager().getPackageInfo(
                packageName,
                PackageManager.GET_PROVIDERS,
                virtualUserId
        );
        if (packageInfo == null && hostUserId != virtualUserId) {
            packageInfo = NexBoxCore.getBPackageManager().getPackageInfo(
                    packageName,
                    PackageManager.GET_PROVIDERS,
                    hostUserId
            );
        }
        if (packageInfo == null && virtualUserId != 0) {
            packageInfo = NexBoxCore.getBPackageManager().getPackageInfo(
                    packageName,
                    PackageManager.GET_PROVIDERS,
                    0
            );
        }
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            Slog.e(TAG, "handleBindApplication: PackageInfo is null for pkg=" + packageName
                    + ", vUserId=" + virtualUserId + ", hostUserId=" + hostUserId);
            return;
        }

        ApplicationInfo applicationInfo = packageInfo.applicationInfo;

        String nativeLibraryRootDir = null;
        boolean nativeLibraryRootRequiresIsa = false;
        try {
            nativeLibraryRootDir = BRApplicationInfoL.get(applicationInfo).nativeLibraryRootDir();
            nativeLibraryRootRequiresIsa = BRApplicationInfoL.get(applicationInfo).nativeLibraryRootRequiresIsa();
        } catch (Throwable metadataUnavailable) {
            // Diagnostics must never prevent a guest from binding when an older framework
            // omits these hidden ApplicationInfo fields.
            Slog.w(TAG, "Could not read native library root metadata: "
                    + metadataUnavailable.getClass().getSimpleName());
        }
        Slog.i(TAG, "action=bindNativeLibraries package=" + packageName + " userId=" + virtualUserId
                + " extractNativeLibs=" + com.nexbox.core.utils.NativeUtils.extractsNativeLibraries(applicationInfo)
                + " nativeLibraryDir=" + applicationInfo.nativeLibraryDir
                + " nativeLibraryRootDir=" + nativeLibraryRootDir
                + " nativeLibraryRootRequiresIsa=" + nativeLibraryRootRequiresIsa
                + " sourceDir=" + applicationInfo.sourceDir
                + " splits=" + (applicationInfo.splitSourceDirs == null ? 0 : applicationInfo.splitSourceDirs.length));
        ensureApacheLegacyOnSharedLibraryPath(applicationInfo);
        if (packageInfo.providers == null) {
            packageInfo.providers = new ProviderInfo[]{};
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));

        Object boundApplication = BRActivityThread.get(NexBoxCore.mainThread()).mBoundApplication();

        NativeCore.init(Build.VERSION.SDK_INT);
        // Install before the guest classloader/factory can cache feature checks. The switch is
        // global and independent of model overrides, device scopes and per-user device profiles.
        if (NexBoxCore.get().isGuestProcess()) {
            try {
                if (com.nexbox.core.fake.device.DeviceSpoofManager.isEsimSpoofEnabledForApp(packageName, virtualUserId)) {
                    com.nexbox.core.xposed.EuiccSpoofer.install(NexBoxCore.getContext());
                }
            } catch (Throwable error) {
                Slog.w(TAG, "Could not read guest eSIM setting: " + error.getMessage(), error);
            }
        }
        List<XposedRuntime.ModuleSpec> xposedModules = prepareXposedModules(packageName, processName, applicationInfo);
        Context packageContext = createPackageContext(applicationInfo);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);

        if (xposedModules.isEmpty() && !GmsCompat.isCorePackage(packageName)) {
            try {
                com.nexbox.core.utils.Reflector.with(loadedApk).field("mClassLoader").set(null);
            } catch (Throwable t) {
                Slog.w(TAG, "Failed to reset LoadedApk.mClassLoader: " + t.getMessage(), t);
            }
        }
        // With XP, retain the factory-produced loader passed to onPackageReady. Resetting only
        // mClassLoader creates a second custom loader after modules have already hooked the first.
        // Google also owns its factory/Dynamite loader graph even without XP modules.

        try {
            BRLoadedApk.get(loadedApk).getClassLoader();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to (re)create LoadedApk classloader: " + t.getMessage(), t);
        }

        try {
            ClassLoader cl = BRLoadedApk.get(loadedApk).getClassLoader();
            boolean hasApacheLegacy;
            try {
                Class.forName("org.apache.http.entity.InputStreamEntity", false, cl);
                hasApacheLegacy = true;
            } catch (Throwable ignored) {
                hasApacheLegacy = false;
            }

            if (!hasApacheLegacy) {
                String apacheLegacyJar = pickApacheLegacyJarPath();
                if (apacheLegacyJar != null) {
                    injectJarIntoClassLoader(cl, apacheLegacyJar, NexBoxCore.getContext());
                    Slog.d(TAG, "Injected apache legacy jar for " + packageName);
                }
            }
        } catch (Throwable t) {
            Slog.w(TAG, "Apache legacy injection failed for " + packageName + ": " + t.getMessage(), t);
        }

        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (targetSdkVersion < Build.VERSION_CODES.N) {
            StrictModeCompat.disableDeathOnFileUriExposure();
        }
        WebView.setDataDirectorySuffix(getUserId() + ":" + packageName + ":" + processName);

        VirtualRuntime.setupRuntime(processName, applicationInfo);

        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);

        assert packageContext != null;
        IOCore.get().enableRedirect(packageContext);
        GuestLocaleController.initialize(packageContext);

        // Complete cached LoadedApk paths before makeApplication. Normal modern callbacks ran
        // at createAppFactory/createOrUpdateClassLoaderLocked, not late synthetic notifications.
        loadXposedModules(packageContext, BRLoadedApk.get(loadedApk).getClassLoader(),
                packageName, processName, applicationInfo, loadedApk, xposedModules);

        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;

        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);

        mBoundApplication = bindData;

        
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Application application;
        try {
            onBeforeCreateApplication(packageName, processName, packageContext);
            
            
            try {
                application = BRLoadedApk.get(loadedApk).makeApplication(false, null);
            } catch (Exception makeAppException) {
                if (GmsCompat.isCorePackage(packageName)) throw makeAppException;
                Slog.e(TAG, "Failed to makeApplication, trying fallback approach", makeAppException);
                application = null;
            }

            if (application == null && GmsCompat.isCorePackage(packageName)) {
                // A plain Application cannot supply Play/GMS's process-specific DI graph.
                throw new IllegalStateException("Google Application initialization failed: " + packageName);
            }
            
            
            if (application == null) {
                Slog.w(TAG, "makeApplication returned null, attempting fallback creation");
                
                
                try {
                    application = BRLoadedApk.get(loadedApk).makeApplication(true, null);
                } catch (Exception e) {
                    Slog.e(TAG, "Fallback makeApplication also failed", e);
                }
                
                
                if (application == null) {
                    Slog.w(TAG, "Creating minimal application context as fallback");
                    try {
                        
                        application = (Application) packageContext;
                        if (application == null) {
                            Slog.e(TAG, "Even package context is null, this is critical");
                            throw new RuntimeException("Unable to create application context");
                        }
                    } catch (Exception contextException) {
                        Slog.e(TAG, "Failed to create fallback application context", contextException);
                        throw new RuntimeException("Unable to makeApplication - all fallback attempts failed", contextException);
                    }
                }
            }
            
            if (application == null) {
                Slog.e(TAG, "makeApplication application Error! All attempts failed");
                throw new RuntimeException("Unable to create application - all creation methods failed");
            }
            
            mInitialApplication = application;
            BRActivityThread.get(NexBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
            ContextCompat.fix((Context) BRActivityThread.get(NexBoxCore.mainThread()).getSystemContext());
            ContextCompat.fix(mInitialApplication);
            GuestLocaleController.attach(mInitialApplication);
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            ModuleServiceBridge.install(mInitialApplication, bindData.providers);

            onBeforeApplicationOnCreate(packageName, processName, application);
            AppInstrumentation.get().callApplicationOnCreate(application);
            onAfterApplicationOnCreate(packageName, processName, application);

            HookManager.get().checkEnv(HCallbackProxy.class);
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in handleBindApplication", e);
            throw new RuntimeException("Unable to makeApplication", e);
        }
    }

    /**
     * Loads this guest's enabled Xposed modules.
     *
     * <p>The module list comes from one synchronous binder call. That call must happen here rather
     * than in {@link #initProcess}: the server resolves the caller through its pid, and the process
     * record is only assigned once the process has been started. Nothing on the server side of this
     * call depends on the guest, so it cannot deadlock against the handleBindApplication lock.
     *
     * <p>Any failure — a broken module, an unreachable service, an unsupported API level — is
     * logged and swallowed. Modules are an optional feature; they must never stop a guest app from
     * starting.
     */
    private List<XposedRuntime.ModuleSpec> prepareXposedModules(String packageName, String processName,
                                                               ApplicationInfo applicationInfo) {
        if (!NexBoxCore.get().isGuestProcess()) {
            return java.util.Collections.emptyList();
        }
        try {
            BXposedManager manager = NexBoxCore.getBXposedManager();
            long startedAt = SystemClock.uptimeMillis();
            List<InstalledModule> modules = manager.getModulesForCurrentProcess();
            if (modules == null || modules.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            List<XposedRuntime.ModuleSpec> specs = new ArrayList<>(modules.size());
            for (InstalledModule module : modules) {
                if (!module.supported) {
                    manager.reportModuleLoad(module.packageName, false, module.error);
                    continue;
                }
                ApplicationInfo info = module.applicationInfo;
                if (info == null || info.sourceDir == null || info.dataDir == null) {
                    continue;
                }
                specs.add(new XposedRuntime.ModuleSpec(info));
            }
            if (specs.isEmpty()) {
                return specs;
            }
            XposedRuntime.prepareModules(NexBoxCore.getContext(), packageName, processName,
                    getUserId(), applicationInfo, specs, manager::reportModuleLoad);
            Slog.d(TAG, "Xposed prepared " + specs.size() + " module(s) for " + packageName
                    + ":" + processName + " in " + (SystemClock.uptimeMillis() - startedAt) + "ms");
            return specs;
        } catch (Throwable t) {
            Slog.w(TAG, "Xposed module preparation skipped for " + packageName + ": " + t);
            return java.util.Collections.emptyList();
        }
    }

    private void loadXposedModules(Context packageContext, ClassLoader guestClassLoader,
                                  String packageName, String processName,
                                  ApplicationInfo applicationInfo, Object loadedApk,
                                  List<XposedRuntime.ModuleSpec> specs) {
        if (specs.isEmpty()) return;
        try {
            ClassLoader defaultLoader = guestClassLoader;
            android.app.AppComponentFactory factory = new android.app.AppComponentFactory();
            try {
                java.lang.reflect.Field field = loadedApk.getClass().getDeclaredField("mDefaultClassLoader");
                field.setAccessible(true);
                Object value = field.get(loadedApk);
                if (value instanceof ClassLoader) defaultLoader = (ClassLoader) value;
                java.lang.reflect.Method method = loadedApk.getClass().getDeclaredMethod("getAppFactory");
                method.setAccessible(true);
                factory = (android.app.AppComponentFactory) method.invoke(loadedApk);
            } catch (ReflectiveOperationException e) {
                Slog.w(TAG, "Unable to inspect guest AppComponentFactory: " + e);
            }
            XposedRuntime.loadModules(packageContext, guestClassLoader, packageName, processName,
                    getUserId(), applicationInfo, specs,
                    NexBoxCore.getBXposedManager()::reportModuleLoad, defaultLoader, factory);
        } catch (Throwable t) {
            Slog.w(TAG, "Xposed module load skipped for " + packageName + ": " + t);
        }
    }

    private static void ensureApacheLegacyOnSharedLibraryPath(ApplicationInfo info) {
        if (info == null) {
            return;
        }

        String jarToUse = pickApacheLegacyJarPath();
        if (jarToUse == null) {
            return;
        }

        java.util.LinkedHashSet<String> libs = new java.util.LinkedHashSet<>();
        if (info.sharedLibraryFiles != null) {
            for (String s : info.sharedLibraryFiles) {
                if (s != null && s.length() > 0) {
                    libs.add(s);
                }
            }
        }
        libs.add(jarToUse);
        info.sharedLibraryFiles = libs.toArray(new String[0]);
    }

    private static String pickApacheLegacyJarPath() {
        final String apacheLegacyJar = "/system/framework/org.apache.http.legacy.jar";
        final String apacheLegacyBootJar = "/system/framework/org.apache.http.legacy.boot.jar";

        if (com.nexbox.core.utils.BzFileUtils.isExist(apacheLegacyJar)) {
            return apacheLegacyJar;
        }
        if (com.nexbox.core.utils.BzFileUtils.isExist(apacheLegacyBootJar)) {
            return apacheLegacyBootJar;
        }
        return null;
    }

    private static void injectJarIntoClassLoader(ClassLoader target, String jarPath, Context context) {
        if (target == null || jarPath == null || jarPath.length() == 0) {
            return;
        }

        try {
            Object pathList = com.nexbox.core.utils.Reflector.on("dalvik.system.BaseDexClassLoader")
                    .field("pathList")
                    .get(target);
            if (pathList == null) {
                return;
            }

            java.util.ArrayList<java.io.File> files = new java.util.ArrayList<>();
            files.add(new java.io.File(jarPath));

            java.util.ArrayList<java.io.IOException> suppressed = new java.util.ArrayList<>();
            java.io.File optimizedDir = null;
            try {
                if (context != null) {
                    optimizedDir = context.getCodeCacheDir();
                }
            } catch (Throwable ignored) {
            }

            Object[] newElements = makeDexElements(pathList, files, optimizedDir, suppressed);
            if (newElements == null || newElements.length == 0) {
                return;
            }

            expandFieldArray(pathList, "dexElements", newElements);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to inject jar into classloader: " + t.getMessage(), t);
        }
    }

    private static Object[] makeDexElements(Object dexPathList,
                                            java.util.ArrayList<java.io.File> files,
                                            java.io.File optimizedDirectory,
                                            java.util.ArrayList<java.io.IOException> suppressed) throws Exception {
        Class<?> cls = dexPathList.getClass();

        try {
            java.lang.reflect.Method m = cls.getDeclaredMethod(
                    "makePathElements",
                    java.util.List.class,
                    java.io.File.class,
                    java.util.List.class
            );
            m.setAccessible(true);
            Object r = m.invoke(dexPathList, files, optimizedDirectory, suppressed);
            return (Object[]) r;
        } catch (NoSuchMethodException ignored) {
        }

        java.lang.reflect.Method m = cls.getDeclaredMethod(
                "makeDexElements",
                java.util.ArrayList.class,
                java.io.File.class,
                java.util.ArrayList.class
        );
        m.setAccessible(true);
        Object r = m.invoke(dexPathList, files, optimizedDirectory, suppressed);
        return (Object[]) r;
    }

    private static void expandFieldArray(Object instance, String fieldName, Object[] extraElements)
            throws NoSuchFieldException, IllegalAccessException {
        java.lang.reflect.Field jlrField = instance.getClass().getDeclaredField(fieldName);
        jlrField.setAccessible(true);
        Object[] original = (Object[]) jlrField.get(instance);
        if (original == null) {
            jlrField.set(instance, extraElements);
            return;
        }

        Object[] combined = (Object[]) java.lang.reflect.Array.newInstance(
                original.getClass().getComponentType(),
                original.length + extraElements.length
        );
        System.arraycopy(original, 0, combined, 0, original.length);
        System.arraycopy(extraElements, 0, combined, original.length, extraElements.length);
        jlrField.set(instance, combined);
    }
    
    
    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return NexBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            Slog.e("BActivityThread", "Container operation failed", e);
        }
        return null;
    }

    
    private static Context createMinimalPackageContext(ApplicationInfo info) {
        try {
            
            Context baseContext = NexBoxCore.getContext();
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, 0);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with minimal flags for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with minimal flags for " + info.packageName + ": " + e.getMessage(), e);
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with ignore security for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with ignore security for " + info.packageName + ": " + e.getMessage(), e);
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_INCLUDE_CODE);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with include code for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with include code for " + info.packageName + ": " + e.getMessage(), e);
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create minimal package context for " + info.packageName + ": " + e.getMessage(), e);
        }
        
        
        Slog.w(TAG, "Using base context as fallback for " + info.packageName);
        return createWrappedBaseContext(info.packageName);
    }

    
    private static Context createWrappedBaseContext(String packageName) {
        try {
            Context baseContext = NexBoxCore.getContext();
            
            
            return new ContextWrapper(baseContext) {
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public PackageManager getPackageManager() {
                    return baseContext.getPackageManager();
                }
                
                @Override
                public Resources getResources() {
                    return baseContext.getResources();
                }
                
                @Override
                public ClassLoader getClassLoader() {
                    return baseContext.getClassLoader();
                }
                
                @Override
                public Context getApplicationContext() {
                    return baseContext.getApplicationContext();
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create wrapped base context for " + packageName + ": " + e.getMessage(), e);
            
            return NexBoxCore.getContext();
        }
    }

    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                if (GmsCompat.isCorePackage(getAppPackageName())) {
                    if (providerInfo == null || !GmsCompat.providerBelongsInProcess(getAppPackageName(),
                            processName, providerInfo.packageName, providerInfo.processName, providerInfo.multiprocess)) {
                        if (providerInfo != null) Slog.d(TAG, "stage=googleProviderSkipped provider="
                                + providerInfo.name + " declaredProcess=" + providerInfo.processName
                                + " currentProcess=" + processName);
                        continue;
                    }
                    try {
                        installProvider(NexBoxCore.mainThread(), context, providerInfo, null);
                    } catch (Throwable failure) {
                        // Keep the first initialization error; do not run a partly initialized
                        // Google process and replace it with unrelated asynchronous cast errors.
                        throw new IllegalStateException("Google provider initialization failed: "
                                + providerInfo.name + " in " + processName, failure);
                    }
                    continue;
                }
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(NexBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public Object getPackageInfo() {
        return mBoundApplication.info;
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider != null) {
            installProvider.setAccessible(true);
            Object installed = installProvider.invoke(mainThread, context, holder, providerInfo, false, true, true);
            ContentProviderDelegate.onGuestProviderInstalled(installed, providerInfo);
        } else if (providerInfo != null && GmsCompat.isCorePackage(providerInfo.packageName)) {
            throw new NoSuchMethodException("ActivityThread.installProvider for " + providerInfo.name);
        }
    }



    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(NexBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void dispatchJobStart(com.nexbox.core.entity.JobRecord record, android.app.job.JobParameters params) {
        mH.post(() -> com.nexbox.core.app.dispatcher.AppJobServiceDispatcher.get().dispatch(record, params, true));
    }

    @Override
    public void dispatchJobStop(com.nexbox.core.entity.JobRecord record, android.app.job.JobParameters params) {
        mH.post(() -> com.nexbox.core.app.dispatcher.AppJobServiceDispatcher.get().dispatch(record, params, false));
    }

    @Override
    public void dispatchJobEvent(com.nexbox.core.entity.JobRecord record, android.app.job.JobParameters params,
            android.app.job.JobWorkItem work, String event) {
        mH.post(() -> com.nexbox.core.app.dispatcher.AppJobServiceDispatcher.get().dispatchEvent(record, params, work, event));
    }

    @Override
    public void restartJobService(String selfId) throws RemoteException {

    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (!isInit()) {
            bindApplication(BActivityThread.getAppConfig().packageName, BActivityThread.getAppConfig().processName);
        }
        if (providerInfo != null && GmsCompat.isCorePackage(providerInfo.packageName)) {
            // mBoundApplication is published before makeApplication. A concurrent Binder
            // request must not acquire providers against an Application still being created.
            if (mInitialApplication == null) return null;
            if (!GmsCompat.providerBelongsInProcess(getAppPackageName(), getAppProcessName(),
                    providerInfo.packageName, providerInfo.processName, providerInfo.multiprocess)
                    || TextUtils.isEmpty(providerInfo.authority)) return null;
            for (String authority : providerInfo.authority.split(";")) {
                if (authority.isEmpty()) continue;
                try (ContentProviderClient client = NexBoxCore.getContext().getContentResolver()
                        .acquireContentProviderClient(authority)) {
                    if (client == null) continue;
                    IInterface provider = BRContentProviderClient.get(client).mContentProvider();
                    IBinder binder = provider == null ? null : provider.asBinder();
                    if (binder != null && binder.isBinderAlive()) {
                        return com.nexbox.core.fake.service.context.providers.GuestProviderTransport
                                .export(binder);
                    }
                }
            }
            return null;
        }
        String[] split = providerInfo.authority.split(";");
        for (String auth : split) {
            try (ContentProviderClient client = NexBoxCore.getContext().getContentResolver()
                    .acquireContentProviderClient(auth)) {
                if (client == null) continue;
                IInterface provider = BRContentProviderClient.get(client).mContentProvider();
                if (provider == null) continue;
                return com.nexbox.core.fake.service.context.providers.GuestProviderTransport
                        .export(provider.asBinder());
            }
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void refreshApplicationLocales(IBinder processToken) {
        synchronized (mConfigLock) {
            if (mAppConfig == null || !mAppConfig.token.equals(processToken)) {
                throw new SecurityException("Locale refresh belongs to another guest process");
            }
        }
        GuestLocaleController.requestRefresh();
    }

    @SuppressWarnings("deprecation")
    private static Activity getEmbeddedActivityRoot(Activity activity) {
        // Legacy guest ActivityGroups still finish through the enclosing activity. A manifest
        // navigation parent is not this live embedding relationship.
        while (activity.getParent() != null) {
            activity = activity.getParent();
        }
        return activity;
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(NexBoxCore.mainThread()).mActivities();
            if (activities.isEmpty())
                return;
            Object clientRecord = activities.get(token);
            if (clientRecord == null)
                return;
            Activity activity = getEmbeddedActivityRoot(getActivityByToken(token));

            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        if (token == null || intent == null) throw new IllegalArgumentException("Missing new-intent target or payload");
        AppConfig config = getAppConfig();
        if (config == null) throw new IllegalStateException("New-intent process is not bound");
        Intent request = new Intent(intent);
        if (!mH.post(() -> {
            try {
                request.setExtrasClassLoader(mInitialApplication.getClassLoader());
                com.nexbox.core.utils.IntentSanitizer.restoreSanitizedClassExtras(request, mInitialApplication.getClassLoader());
                com.nexbox.core.core.system.accounts.AccountAuthenticatorTransport.prepareActivityIntent(request);
                ActivityIntentLog.log("newIntentReceived", request, config.userId, config.userGeneration,
                        true, "package=" + config.packageName);
                Intent newIntent = BRReferrerIntent.getWithException()._new(request, NexBoxCore.getHostPkg());
                if (newIntent == null) throw new IllegalStateException("Unable to create referrer intent");
                Object mainThread = NexBoxCore.mainThread();
                String entry;
                if (BRActivityThread.get(mainThread)._check_handleNewIntent(null, null) != null) {
                    Object record = BRActivityThread.getWithException(mainThread).getActivityClient(token);
                    if (record == null) {
                        ActivityIntentLog.failure("newIntentDropped", config.userId, config.userGeneration,
                                "activityMissing");
                        return;
                    }
                    entry = "activityRecord";
                    BRActivityThread.getWithException(mainThread).handleNewIntent(record, Collections.singletonList(newIntent));
                } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                    entry = "binder";
                    BRActivityThreadQ.getWithException(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
                } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                    entry = "performWithResume";
                    BRActivityThreadNMR1.getWithException(mainThread).performNewIntents(token, Collections.singletonList(newIntent), true);
                } else if (BRActivityThread.get(mainThread)._check_performNewIntents(null, null) != null) {
                    entry = "perform";
                    BRActivityThread.getWithException(mainThread).performNewIntents(token, Collections.singletonList(newIntent));
                } else {
                    throw new IllegalStateException("No supported framework new-intent entry point");
                }
                ActivityIntentLog.log("newIntentFrameworkReturned", request, config.userId, config.userGeneration,
                        false, "package=" + config.packageName + " entry=" + entry);
            } catch (RuntimeException | Error failure) {
                ActivityIntentLog.failure("newIntentFailed", config.userId, config.userGeneration,
                        failure.getClass().getSimpleName());
                throw failure;
            }
        })) throw new IllegalStateException("New-intent main queue is unavailable");
    }

    @Override
    public void scheduleReceiver(ReceiverData data) throws RemoteException {
        if (data == null || data.intent == null || data.activityInfo == null) {
            throw new IllegalArgumentException("Incomplete manifest broadcast delivery");
        }
        if (!GuestBroadcastCompletion.register(data.data)) return;
        try {
            if (!isInit()) {
                bindApplication();
            }
        } catch (RuntimeException | Error error) {
            GuestBroadcastCompletion.fail(data.data, null);
            throw error;
        }
        boolean posted = mH.post(() -> {
            ActivityInfo activityInfo = data.activityInfo;
            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader classLoader = baseContext.getClassLoader();
                BroadcastReceiver receiver = (BroadcastReceiver) instantiateGuestClass(classLoader.loadClass(activityInfo.name));
                GuestBroadcastDispatcher.dispatch(receiver, baseContext, data, "manifest");
            } catch (Throwable throwable) {
                GuestBroadcastCompletion.fail(data.data, null);
                Slog.e(TAG, "action=deliverBroadcast stage=instantiateReceiver kind=manifest result=failed"
                        + " component=" + activityInfo.name, throwable);
            }
        });
        if (!posted) {
            GuestBroadcastCompletion.fail(data.data, null);
            Slog.e(TAG, "action=deliverBroadcast stage=handlerPost kind=manifest result=failed");
        }
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> iBinderObjectMap =
                BRActivityThread.get(NexBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(iBinderObjectMap.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeCreateApplication(packageName, processName, context, BActivityThread.getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    
    public static void ensureActivityContext(Activity activity) {
        if (activity == null) {
            return;
        }
        
        try {
            
            Context currentContext = activity.getBaseContext();
            if (currentContext != null) {
                Slog.d(TAG, "Activity already has context: " + activity.getClass().getName());
                return;
            }
            
            Slog.w(TAG, "Activity has null context, ensuring valid context: " + activity.getClass().getName());
            
            
            Context validContext = null;
            try {
                validContext = getApplication();
                if (validContext == null) {
                    validContext = NexBoxCore.getContext();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Could not get application context: " + e.getMessage(), e);
                validContext = NexBoxCore.getContext();
            }
            
            if (validContext != null) {
                
                try {
                    Context packageContext = validContext.createPackageContext(
                        activity.getPackageName(),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
                    );
                    
                    
                    java.lang.reflect.Method attachBaseContext = Activity.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attachBaseContext.setAccessible(true);
                    attachBaseContext.invoke(activity, packageContext);
                    Slog.d(TAG, "Successfully attached package context to activity: " + activity.getClass().getName());
                } catch (Exception e) {
                    Slog.w(TAG, "Could not attach base context to activity: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error ensuring activity context: " + e.getMessage(), e);
        }
    }

    
    public static void hookActivityThread() {
        try {
            
            Object activityThread = NexBoxCore.mainThread();
            if (activityThread != null) {
                
                Instrumentation instrumentation = BRActivityThread.get(activityThread).mInstrumentation();
                if (instrumentation != null) {
                    Slog.d(TAG, "Found ActivityThread instrumentation, ensuring it's our AppInstrumentation");
                    
                    
                    if (!(instrumentation instanceof AppInstrumentation)) {
                        Slog.w(TAG, "ActivityThread instrumentation is not our AppInstrumentation, attempting to replace");
                        
                        
                        try {
                            AppInstrumentation appInstrumentation = AppInstrumentation.get();
                            appInstrumentation.injectHook();
                            Slog.d(TAG, "Successfully replaced ActivityThread instrumentation with AppInstrumentation");
                        } catch (Exception e) {
                            Slog.w(TAG, "Could not replace ActivityThread instrumentation: " + e.getMessage(), e);
                        }
                    } else {
                        Slog.d(TAG, "ActivityThread instrumentation is already our AppInstrumentation");
                    }
                } else {
                    Slog.w(TAG, "ActivityThread instrumentation is null");
                }
            } else {
                Slog.w(TAG, "ActivityThread is null");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error hooking ActivityThread: " + e.getMessage(), e);
        }
    }

    
    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}
