package com.nexbox.core;

import com.nexbox.core.utils.OperationLog;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


import black.android.app.BRActivityThread;
import me.weishu.reflection.Reflection;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.app.LauncherActivity;
import com.nexbox.core.app.configuration.AppLifecycleCallback;
import com.nexbox.core.app.configuration.ClientConfiguration;
import com.nexbox.core.core.GmsCore;
import com.nexbox.core.core.NativeCore;
import com.nexbox.core.core.connection.ServiceConnection;
import com.nexbox.core.core.connection.ServiceReadiness;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.DaemonService;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.pm.PackageCodeAvailability;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.entity.pm.InstallOption;
import com.nexbox.core.entity.pm.InstallResult;

import com.nexbox.core.fake.delegate.ContentProviderDelegate;
import com.nexbox.core.fake.frameworks.BActivityManager;
import com.nexbox.core.fake.frameworks.BJobManager;
import com.nexbox.core.fake.frameworks.BPackageManager;
import com.nexbox.core.fake.frameworks.BStorageManager;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.fake.frameworks.BXposedManager;

import com.nexbox.core.fake.hook.HookManager;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.ShellUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

import com.nexbox.core.utils.provider.ProviderCall;
import com.nexbox.core.utils.CrashMonitor;
import com.nexbox.core.utils.StoragePermissionHelper;



@SuppressLint({"StaticFieldLeak", "NewApi"})
@SuppressWarnings({"unchecked", "deprecation"})
public class NexBoxCore extends ClientConfiguration {
    public static final String TAG = "NexBoxCore";

    private static final int ANDROID_UID_PER_USER_RANGE = 100000;
    private static final long SERVICE_INIT_TIMEOUT_MS = 10_000L;
    private static final long SERVICE_RETRY_DELAY_MS = 2_000L;
    private static final String[] REQUIRED_SERVICES = {
            ServiceManager.ACTIVITY_MANAGER, ServiceManager.PACKAGE_MANAGER,
            ServiceManager.USER_MANAGER, ServiceManager.STORAGE_MANAGER,
            ServiceManager.JOB_MANAGER, ServiceManager.ACCOUNT_MANAGER,
            ServiceManager.LOCATION_MANAGER, ServiceManager.NOTIFICATION_MANAGER,
            ServiceManager.XPOSED_MANAGER
    };

    private static final NexBoxCore sNexBoxCore = new NexBoxCore();
    private static Context sContext;
    
    
    static {
        try {
            CrashMonitor.initialize();
            Slog.d(TAG, "Crash monitoring initialized at class loading time");
        } catch (Exception e) {
            Slog.w(TAG, "Failed to initialize crash monitoring", e);
        }
    }
    private ProcessType mProcessType;
    private final Map<String, ServiceConnection<IBinder>> mServices = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor mServiceExecutor = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            task -> new Thread(task, "NexBox-ServiceConnection"));
    private final ScheduledThreadPoolExecutor mServiceScheduler = createServiceScheduler();
    private final ServiceReadiness mServiceReadiness = new ServiceReadiness(
            (task, delayMs) -> {
                java.util.concurrent.ScheduledFuture<?> scheduled =
                        mServiceScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                return () -> scheduled.cancel(false);
            }, this::requestRequiredServices, System::nanoTime,
            SERVICE_INIT_TIMEOUT_MS, SERVICE_RETRY_DELAY_MS);
    private final AtomicBoolean mDaemonStartRequested = new AtomicBoolean();
    private Thread.UncaughtExceptionHandler mExceptionHandler;
    private ClientConfiguration mClientConfiguration;
    private final List<AppLifecycleCallback> mAppLifecycleCallbacks = new ArrayList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int mHostUid = Process.myUid();
    private final int mHostUserId = mHostUid / ANDROID_UID_PER_USER_RANGE;

    private final Map<Runnable, Runnable> mServiceAvailableCallbacks = new LinkedHashMap<>();
    private final Object mServiceCallbackLock = new Object();

    
    private int mCurrentAppUid = -1;
    private String mCurrentAppPackage = null;
    private boolean mIsSandboxedEnvironment = false;
    private String mProcessName;

    public static NexBoxCore get() {
        return sNexBoxCore;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public static PackageManager getPackageManager() {
        return sContext.getPackageManager();
    }

    public static String getHostPkg() {
        return get().getHostPackageName();
    }

    public static int getHostUid() {
        return get().mHostUid;
    }

    public static int getHostUserId() {
        return get().mHostUserId;
    }

    public static Context getContext() {
        return sContext;
    }

    public Thread.UncaughtExceptionHandler getExceptionHandler() {
        return mExceptionHandler;
    }

    public void setExceptionHandler(Thread.UncaughtExceptionHandler exceptionHandler) {
        mExceptionHandler = exceptionHandler;
    }

    
    public void setCurrentAppUid(int uid, String packageName) {
        mCurrentAppUid = uid;
        mCurrentAppPackage = packageName;
        
        
        if (uid != mHostUid && uid > Process.FIRST_APPLICATION_UID && uid < Process.LAST_APPLICATION_UID) {
            mIsSandboxedEnvironment = true;
            Slog.d("NexBoxCore", "Detected sandboxed environment for " + packageName + " with UID: " + uid);
        }
    }

    public int getCurrentAppUid() {
        return mCurrentAppUid > 0 ? mCurrentAppUid : mHostUid;
    }

    public String getCurrentAppPackage() {
        return mCurrentAppPackage != null ? mCurrentAppPackage : getHostPackageName();
    }

    public boolean isSandboxedEnvironment() {
        return mIsSandboxedEnvironment;
    }

    
    public int resolveUidForOperation(int originalUid, String operation) {
        try {
            
            if (originalUid > 0 && originalUid < Process.FIRST_APPLICATION_UID) {
                return originalUid;
            }
            
            
            if (originalUid > Process.LAST_APPLICATION_UID) {
                return originalUid;
            }

            
            if (mIsSandboxedEnvironment && mCurrentAppUid > 0) {
                Slog.d("NexBoxCore", "Resolving UID for " + operation + ": " + originalUid + " -> " + mCurrentAppUid);
                return mCurrentAppUid;
            }

            
            return originalUid;
        } catch (Exception e) {
            Slog.e("NexBoxCore", "Error resolving UID for " + operation + ": " + e.getMessage(), e);
            return originalUid;
        }
    }

    private static ScheduledThreadPoolExecutor createServiceScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
                1, task -> new Thread(task, "NexBox-ServiceRetry"));
        scheduler.setKeepAliveTime(30L, TimeUnit.SECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    public boolean areServicesAvailable() {
        if (isServicesAvailable()) return true;
        requestServiceInitialization();
        return false;
    }

    public void requestServiceInitialization() {
        if (sContext == null) return;
        isServicesAvailable();
        mServiceReadiness.request().thenAccept(ready -> {
            if (ready) notifyServiceAvailableCallbacks();
        });
    }

    public ServiceReadiness.State getServiceConnectionState() {
        isServicesAvailable();
        return mServiceReadiness.getState();
    }

    public IBinder getService(String name) {
        if (name == null || sContext == null) return null;
        return serviceConnection(name).await(SERVICE_INIT_TIMEOUT_MS);
    }

    public boolean isCachedService(String name, IBinder binder) {
        ServiceConnection<IBinder> connection = mServices.get(name);
        return binder != null && connection != null && connection.peek() == binder;
    }

    public void invalidateService(String name, IBinder expected) {
        ServiceConnection<IBinder> connection = mServices.get(name);
        if (connection == null) return;
        if (expected == null) connection.clear();
        else connection.invalidate(expected);
    }

    private ServiceConnection<IBinder> serviceConnection(String name) {
        return mServices.computeIfAbsent(name, serviceName -> new ServiceConnection<>(
                mServiceExecutor, new ServiceConnection.Endpoint<IBinder>() {
                    @Override
                    public IBinder connect() {
                        try {
                            // The server must not acquire its own provider during Application startup.
                            if (isServerProcess()) return ServiceManager.getService(serviceName);
                            Bundle request = new Bundle();
                            request.putString(NexBoxIpcKeys.SYSTEM_SERVICE_NAME, serviceName);
                            Bundle response = ProviderCall.call(ProxyManifest.getBindProvider(),
                                    getContext(), "VM", null, request, 0);
                            IBinder binder = response == null ? null
                                    : BundleCompat.getBinder(response, NexBoxIpcKeys.SYSTEM_SERVICE_BINDER);
                            if (binder == null) Slog.w(TAG, "Service unavailable: " + serviceName);
                            return binder;
                        } catch (Exception failure) {
                            Slog.w(TAG, "Cannot connect service: " + serviceName, failure);
                            return null;
                        }
                    }

                    @Override
                    public boolean isAlive(IBinder binder) {
                        return binder.isBinderAlive();
                    }

                    @Override
                    public AutoCloseable observeDeath(IBinder binder, Runnable onDeath) throws Exception {
                        IBinder.DeathRecipient recipient = onDeath::run;
                        binder.linkToDeath(recipient, 0);
                        return () -> binder.unlinkToDeath(recipient, 0);
                    }
                }, mServiceReadiness::invalidate, System::nanoTime, SERVICE_RETRY_DELAY_MS));
    }

    private CompletableFuture<Boolean> requestRequiredServices() {
        CompletableFuture<?>[] requests = new CompletableFuture<?>[REQUIRED_SERVICES.length];
        for (int index = 0; index < REQUIRED_SERVICES.length; index++) {
            requests[index] = serviceConnection(REQUIRED_SERVICES[index]).request();
        }
        return CompletableFuture.allOf(requests).thenApply(ignored -> areRequiredServicesCached());
    }

    private boolean areRequiredServicesCached() {
        for (String name : REQUIRED_SERVICES) {
            ServiceConnection<IBinder> connection = mServices.get(name);
            if (connection == null || connection.peek() == null) return false;
        }
        return true;
    }

    /** Compatibility entry point: startup is requested, never slept on by the host Application. */
    public void ensureServerInitialized() {
        requestServiceInitialization();
    }

    private void requestDaemonService() {
        if (!isServerProcess() || !mClientConfiguration.isEnableDaemonService()
                || !mDaemonStartRequested.compareAndSet(false, true)) return;
        mServiceScheduler.execute(() -> startDaemonService(0));
    }

    private void startDaemonService(int attempt) {
        try {
            Intent intent = new Intent(getContext(), DaemonService.class);
            getContext().startForegroundService(intent);
        } catch (Exception failure) {
            if (attempt < 2) {
                mServiceScheduler.schedule(
                        () -> startDaemonService(attempt + 1),
                        (attempt + 1) * 1_000L, TimeUnit.MILLISECONDS);
            } else {
                Slog.w(TAG, "Optional daemon startup failed; provider connection is independent", failure);
            }
        }
    }

    public void doAttachBaseContext(Context context,
                                   ClientConfiguration clientConfiguration) {
        com.nexbox.core.fake.device.HostDeviceInfo.get();
        sContext = context;
        mClientConfiguration = clientConfiguration;

        final String processName = getProcessName(context);
        mProcessName = processName;
        if (processName.equals(NexBoxCore.getHostPkg())) {
            mProcessType = ProcessType.Main;
            // Collection is opt-in through the host diagnostic relay; never clear system Logcat.
        } else if (processName.endsWith(getContext().getString(R.string.nexbox_service_process))) {
            mProcessType = ProcessType.Server;
        } else {
            mProcessType = ProcessType.BAppClient;
        }

        Slog.i(TAG, "Process detected: name=" + processName + ", hostPkg=" + NexBoxCore.getHostPkg() + ", type=" + mProcessType);

        try {
            setEssentialProperties(context, clientConfiguration, mProcessType == ProcessType.Main);

            if (mProcessType == ProcessType.Main) {
                Slog.i(TAG, "Main process: skipped native nexbox resource/hideapi hooks");
            }

            // WindowManager is an interface and has no mIgnoreWindowLeaks field. The old
            // probe never changed state and only produced an exception on Google startup.

        } catch (Exception e) {
            Slog.w(TAG, "Failed to set essential properties: " + e.getMessage(), e);
        }

        initNotificationManager();
        if (NexBoxCore.get().isGuestProcess()) {
            BEnvironment.load();
        }
        initVpnService();
        if (isServerProcess()) {
            // Receiver registration in constructors must not re-enter a hooked package lookup.
            ServiceManager.get();
        }
        HookManager.get().init();
        requestDaemonService();
    }

    public void doCreate() {
        long startTime = SystemClock.elapsedRealtime();
        if (isMainProcess()) {
            requestServiceInitialization();
            getBPackageManager().resetTransactionThrottler();
            return;
        }
        try {
            // Guest native setup, service readiness, and ActivityThread hooks keep their order.
            ensureProperInitialization();
            if (isGuestProcess()) {
                ContentProviderDelegate.init();
                getBPackageManager().resetTransactionThrottler();
            }
            Slog.d(TAG, "NexBox initialization completed in "
                    + (SystemClock.elapsedRealtime() - startTime) + "ms");
        } catch (Exception failure) {
            Slog.e(TAG, "NexBox initialization failed after "
                    + (SystemClock.elapsedRealtime() - startTime) + "ms", failure);
        }
    }

    public static Object mainThread() {
        return BRActivityThread.get().currentActivityThread();
    }

    public void startActivity(Intent intent, int userId) {
        if (mClientConfiguration.isEnableLauncherActivity()) {
            LauncherActivity.launch(intent, userId);
        } else {
            getBActivityManager().startActivity(intent, userId);
        }
    }

    public void startActivity(Intent intent, int userId, long expectedUserCreateTime) {
        if (expectedUserCreateTime <= 0L) throw new IllegalArgumentException("A captured user generation is required");
        if (mClientConfiguration.isEnableLauncherActivity()) {
            LauncherActivity.launch(intent, userId, expectedUserCreateTime);
        } else {
            getBActivityManager().startActivityIfMatches(intent, userId, expectedUserCreateTime);
        }
    }

    public static BJobManager getBJobManager() {
        return BJobManager.get();
    }

    public static BPackageManager getBPackageManager() {
        return BPackageManager.get();
    }

    public static BXposedManager getBXposedManager() {
        return BXposedManager.get();
    }

    public static BActivityManager getBActivityManager() {
        return BActivityManager.get();
    }

    public static BStorageManager getBStorageManager() {
        return BStorageManager.get();
    }
    
    
    
    
    public boolean hasStoragePermission() {
        return StoragePermissionHelper.hasStoragePermission(sContext);
    }
    
    
    public boolean hasAllFilesAccess() {
        return StoragePermissionHelper.hasAllFilesAccess();
    }
    
    
    public boolean hasFullFileAccess() {
        return StoragePermissionHelper.hasFullFileAccess(sContext);
    }
    
    
    public void requestStoragePermission(android.app.Activity activity) {
        StoragePermissionHelper.requestStoragePermission(activity);
    }
    
    
    public void requestAllFilesAccess(android.app.Activity activity) {
        StoragePermissionHelper.requestAllFilesAccess(activity);
    }
    
    
    public void requestFullFileAccess(android.app.Activity activity) {
        StoragePermissionHelper.requestFullFileAccess(activity);
    }
    
    
    public boolean handleStoragePermissionResult(android.app.Activity activity, int requestCode, 
            String[] permissions, int[] grantResults) {
        return StoragePermissionHelper.handlePermissionResult(activity, requestCode, permissions, grantResults);
    }
    
    
    public boolean handleAllFilesAccessResult(int requestCode) {
        return StoragePermissionHelper.handleAllFilesAccessResult(requestCode);
    }
    
    
    public static int getStoragePermissionRequestCode() {
        return StoragePermissionHelper.REQUEST_CODE_STORAGE_PERMISSION;
    }
    
    public static int getAllFilesAccessRequestCode() {
        return StoragePermissionHelper.REQUEST_CODE_MANAGE_STORAGE;
    }

    public boolean launchApk(String packageName, int userId) {
        BUserInfo user = requireLaunchUser(userId, 0L);
        return launchApk(packageName, userId, user.createTime);
    }

    public boolean launchApk(String packageName, int userId, long expectedUserCreateTime) {
        if (expectedUserCreateTime <= 0L) throw new IllegalArgumentException("A captured user generation is required");
        return OperationLog.call(TAG, "launchApp", "package=" + packageName + " userId=" + userId
                + " generation=" + expectedUserCreateTime, () -> {
            requireLaunchUser(userId, expectedUserCreateTime);
            com.nexbox.core.core.system.pm.IBPackageManagerService packages = getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Package service unavailable");
            Bundle sync;
            try {
                sync = packages.syncHostPackages(packageName);
            } catch (android.os.RemoteException error) {
                throw new IllegalStateException("Host source synchronization unavailable", error);
            }
            if (sync == null) throw new IllegalStateException("Host source synchronization returned no receipt");
            if (sync.getInt("failed") > 0) {
                Slog.w(TAG, "action=launchApp stage=hostSync package=" + packageName + " failed=" + sync.getInt("failed"));
            }
            requireLaunchUser(userId, expectedUserCreateTime);
            ApplicationInfo application = getBPackageManager().getApplicationInfo(packageName, 0, userId);
            if (application == null) {
                Slog.w(TAG, "action=launchApp reason=package_unavailable package=" + packageName + " userId=" + userId);
                return false;
            }
            // Reject missing base/splits before host callbacks or launch dispatch.
            PackageCodeAvailability.requireAvailable(application);
            onBeforeMainLaunchApk(packageName, userId);


            Intent launchIntentForPackage = getBPackageManager().getLaunchIntentForPackage(packageName, userId);
            if (launchIntentForPackage == null) {
                Slog.w(TAG, "action=launchApp reason=no_launch_intent package=" + packageName + " userId=" + userId);
                return false;
            }
            startActivity(launchIntentForPackage, userId, expectedUserCreateTime);
            return true;
        });
    }

    private static BUserInfo requireLaunchUser(int userId, long expectedUserCreateTime) {
        com.nexbox.core.core.system.user.IBUserManagerService users = BUserManager.get().getService();
        if (users == null) throw new IllegalStateException("User service unavailable");
        try {
            BUserInfo user = users.getUserInfo(userId);
            if (userId < 0 || user == null || user.id != userId || user.createTime <= 0L
                    || (expectedUserCreateTime > 0L && user.createTime != expectedUserCreateTime)) {
                throw new IllegalStateException("The launch target user changed");
            }
            return user;
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("The launch target user could not be verified", failure);
        }
    }
    public boolean isInstalled(String packageName, int userId) {
        return getBPackageManager().isInstalled(packageName, userId);
    }

    public void uninstallPackageAsUser(String packageName, int userId) {
        getBPackageManager().uninstallPackageAsUser(packageName, userId);
    }

    public void uninstallPackage(String packageName) {
        getBPackageManager().uninstallPackage(packageName);
    }

    public InstallResult installPackageAsUser(String packageName, int userId) {
        try {
            
            if (packageName.equals(getHostPkg())) {
                return new InstallResult().installError("Cannot clone NexBox app from within NexBox. This would create infinite recursion and is not allowed for security reasons.");
            }
            
            PackageInfo packageInfo = getPackageManager().getPackageInfo(packageName, 0);
            return getBPackageManager().installPackageAsUser(packageInfo.applicationInfo.sourceDir, InstallOption.installBySystem(), userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e("NexBoxCore", "Container operation failed", e);
            return new InstallResult().installError(e.getMessage());
        }
    }

    /**
     * Establishes this user's install state and private data for a package whose code is
     * already in the container, without copying the APK again. Used to add a module to a
     * second profile. Must be called from the host main process.
     */
    public InstallResult installExistingPackageAsUser(String packageName, int userId) {
        if (packageName == null || packageName.equals(getHostPkg())) {
            return new InstallResult().installError("Not a virtual package: " + packageName);
        }
        return getBPackageManager().installExistingPackageAsUser(packageName, userId);
    }

    public InstallResult installPackageAsUser(File apk, int userId) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (packageInfo != null) {
                String packageName = packageInfo.packageName;
                if (packageName.equals(getHostPkg())) {
                    return new InstallResult().installError("Cannot clone NexBox app from within NexBox. This would create infinite recursion and is not allowed for security reasons.");
                }
            }
        } catch (Exception e) {
            
            Slog.w(TAG, "Could not verify package info for APK: " + apk.getAbsolutePath());
        }
        
        return getBPackageManager().installPackageAsUser(apk.getAbsolutePath(), InstallOption.installByStorage(), userId);
    }

    public InstallResult installPackageAsUser(Uri apk, int userId) {
        
        return getBPackageManager().installPackageAsUser(apk.toString(), InstallOption.installByStorage().makeUriFile(), userId);
    }





    public List<ApplicationInfo> getInstalledApplications(int flags, int userId) {
        return getBPackageManager().getInstalledApplications(flags, userId);
    }

    public List<PackageInfo> getInstalledPackages(int flags, int userId) {
        return getBPackageManager().getInstalledPackages(flags, userId);
    }

    public void clearPackage(String packageName, int userId) {
        BPackageManager.get().clearPackage(packageName, userId);
    }

    public void stopPackage(String packageName, int userId) {
        OperationLog.call(TAG, "stopApp", "package=" + packageName + " userId=" + userId, () -> {
            BPackageManager.get().stopPackage(packageName, userId);
            return null;
        });
    }

    public List<BUserInfo> getUsers() {
        return BUserManager.get().getUsers();
    }

    public BUserInfo createUser(int userId) {
        return BUserManager.get().createUser(userId);
    }

    public void deleteUser(int userId) {
        BUserManager.get().deleteUser(userId);
    }

    public List<AppLifecycleCallback> getAppLifecycleCallbacks() {
        return mAppLifecycleCallbacks;
    }

    public void removeAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.remove(appLifecycleCallback);
    }

    public void addAppLifecycleCallback(AppLifecycleCallback appLifecycleCallback) {
        mAppLifecycleCallbacks.add(appLifecycleCallback);
    }

    public boolean isSupportGms() {
        return GmsCore.isSupportGms();
    }

    public boolean isInstallGms(int userId) {
        return GmsCore.isInstalledGoogleService(userId);
    }

    public boolean hasGmsTraces(int userId) {
        return GmsCore.hasGmsTraces(userId);
    }

    public InstallResult installGms(int userId) {
        return GmsCore.installGApps(userId);
    }

    public InstallResult installGms(int userId, long expectedUserCreateTime) {
        return GmsCore.installGApps(userId, expectedUserCreateTime);
    }

    public boolean uninstallGms(int userId) {
        GmsCore.uninstallGApps(userId);
        return true;
    }

    public boolean uninstallGms(int userId, long expectedUserCreateTime) {
        return GmsCore.uninstallGApps(userId, expectedUserCreateTime);
    }

    public boolean resetGmsEnvironmentIfMatches(int userId, long userCreateTime) {
        return GmsCore.resetGmsEnvironmentIfMatches(userId, userCreateTime);
    }

    
    private enum ProcessType {
        
        Server,
        
        BAppClient,
        
        Main,
    }

    public boolean isGuestProcess() {
        return mProcessType == ProcessType.BAppClient;
    }

    public boolean isMainProcess() {
        return mProcessType == ProcessType.Main;
    }

    public boolean isServerProcess() {
        return mProcessType == ProcessType.Server;
    }

    @Override
    public boolean isHideRoot() {
        return mClientConfiguration.isHideRoot();
    }

    @Override
    public boolean isDisableFlagSecure() {
        return mClientConfiguration.isDisableFlagSecure();
    }



    @Override
    public String getHostPackageName() {
        return mClientConfiguration.getHostPackageName();
    }

    @Override
    public boolean requestInstallPackage(File file, int userId) {
        return mClientConfiguration.requestInstallPackage(file, userId);
    }

    private void logDeviceInfo() {
        try {
            Slog.i(TAG, "╔══════════════════════════════════════════════════════════════╗");
            Slog.i(TAG, "║                    DEVICE INFORMATION                        ║");
            Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
            
            
            Slog.i(TAG, "║ Android Version: " + Build.VERSION.RELEASE);
            Slog.i(TAG, "║ SDK Level: " + Build.VERSION.SDK_INT);
            Slog.i(TAG, "║ Build ID: " + Build.ID);
            Slog.i(TAG, "║ Build Display: " + Build.DISPLAY);
            
            
            Slog.i(TAG, "║ Security Patch: " + Build.VERSION.SECURITY_PATCH);
            
            
            Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
            Slog.i(TAG, "║ Manufacturer: " + Build.MANUFACTURER);
            Slog.i(TAG, "║ Brand: " + Build.BRAND);
            Slog.i(TAG, "║ Model: " + Build.MODEL);
            Slog.i(TAG, "║ Device: " + Build.DEVICE);
            Slog.i(TAG, "║ Product: " + Build.PRODUCT);
            Slog.i(TAG, "║ Board: " + Build.BOARD);
            Slog.i(TAG, "║ Hardware: " + Build.HARDWARE);
            
            
            Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
            Slog.i(TAG, "║ Supported ABIs: " + String.join(", ", Build.SUPPORTED_ABIS));
            if (Build.SUPPORTED_32_BIT_ABIS.length > 0) {
                Slog.i(TAG, "║ 32-bit ABIs: " + String.join(", ", Build.SUPPORTED_32_BIT_ABIS));
            }
            if (Build.SUPPORTED_64_BIT_ABIS.length > 0) {
                Slog.i(TAG, "║ 64-bit ABIs: " + String.join(", ", Build.SUPPORTED_64_BIT_ABIS));
            }
            
            
            Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
            Slog.i(TAG, "║ Fingerprint: " + Build.FINGERPRINT);
            Slog.i(TAG, "║ Type: " + Build.TYPE);
            Slog.i(TAG, "║ Tags: " + Build.TAGS);
            
            
            try {
                Runtime runtime = Runtime.getRuntime();
                long maxMem = runtime.maxMemory() / (1024 * 1024);
                long totalMem = runtime.totalMemory() / (1024 * 1024);
                long freeMem = runtime.freeMemory() / (1024 * 1024);
                Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
                Slog.i(TAG, "║ Max Heap: " + maxMem + " MB");
                Slog.i(TAG, "║ Total Heap: " + totalMem + " MB");
                Slog.i(TAG, "║ Free Heap: " + freeMem + " MB");
                Slog.i(TAG, "║ Used Heap: " + (totalMem - freeMem) + " MB");
            } catch (Exception e) {
                Slog.w(TAG, "║ Memory info unavailable: " + e.getMessage(), e);
            }
            
            
            try {
                Context context = getContext();
                if (context != null) {
                    Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
                    Slog.i(TAG, "║ Package: " + context.getPackageName());
                    android.content.pm.PackageInfo pInfo = context.getPackageManager()
                            .getPackageInfo(context.getPackageName(), 0);
                    Slog.i(TAG, "║ App Version: " + pInfo.versionName);
                    Slog.i(TAG, "║ Version Code: " + pInfo.getLongVersionCode());
                }
            } catch (Exception e) {
                Slog.w(TAG, "║ App info unavailable: " + e.getMessage(), e);
            }
            
            
            Slog.i(TAG, "╠══════════════════════════════════════════════════════════════╣");
            Slog.i(TAG, "║ Timestamp: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", 
                    java.util.Locale.getDefault()).format(new java.util.Date()));
            Slog.i(TAG, "╚══════════════════════════════════════════════════════════════╝");
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to log device info: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("deprecation")
    private static String getProcessName(Context context) {
        int pid = Process.myPid();
        String processName = null;
        
        
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
            if (processes != null) {
                for (ActivityManager.RunningAppProcessInfo info : processes) {
                    if (info.pid == pid) {
                        processName = info.processName;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get process name using modern API", e);
        }
        
        
        if (processName == null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes != null) {
                    for (ActivityManager.RunningAppProcessInfo info : processes) {
                        if (info.pid == pid) {
                            processName = info.processName;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to get process name using deprecated API", e);
            }
        }
        
        if (processName == null) {
            throw new RuntimeException("processName = null");
        }
        return processName;
    }

    public static boolean is64Bit() {
        return Process.is64Bit();
    }

    private void initNotificationManager() {
        NotificationManager nm = (NotificationManager) NexBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        String CHANNEL_ONE_ID = NexBoxCore.getContext().getPackageName() + ".nexbox_core";
        String CHANNEL_ONE_NAME = "nexbox_core";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ONE_ID,
                CHANNEL_ONE_NAME, NotificationManager.IMPORTANCE_HIGH);
        notificationChannel.enableLights(true);
        notificationChannel.setLightColor(Color.RED);
        notificationChannel.setShowBadge(true);
        notificationChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(notificationChannel);
    }

    public void closeCodeInit(){
        try {
            Class<?> entry = Class.forName("com.nexbox.core.closecode.Entry");
            Method attach = entry.getDeclaredMethod("attach");
            attach.invoke(null);
        } catch (Exception e) {
            Slog.w(TAG, "closeCodeInit reflection failed: " + e.getMessage(), e);
        }
    }
    public void onBeforeMainLaunchApk(String packageName,int userid) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeMainLaunchApk(packageName,userid);
        }
    }
    public void onBeforeMainApplicationAttach(Application app, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeMainApplicationAttach(app, context);
        }
    }
    public void onAfterMainApplicationAttach(Application app, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterMainApplicationAttach(app, context);
        }
    }
    public void onBeforeMainActivityOnCreate(android.app.Activity activity) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeMainActivityOnCreate(activity);
        }
    }
    public void onAfterMainActivityOnCreate(android.app.Activity activity) {
        for (AppLifecycleCallback appLifecycleCallback : NexBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterMainActivityOnCreate(activity);
        }
    }


    public static boolean isThreadInit() {
        try {
            return BActivityThread.isThreadInit();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.isThreadInit() failed, returning false", e);
            return false;
        }
    }

    public static BActivityThread currentActivityThread() {
        try {
            return BActivityThread.currentActivityThread();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.currentActivityThread() failed, returning null", e);
            return null;
        }
    }

    public static com.nexbox.core.entity.AppConfig getAppConfig() {
        try {
            return BActivityThread.getAppConfig();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getAppConfig() failed, returning null", e);
            return null;
        }
    }

    public static String getAppProcessName() {
        try {
            return BActivityThread.getAppProcessName();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getAppProcessName() failed, returning null", e);
            return null;
        }
    }

    public static String getAppPackageName() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getAppPackageName() failed, returning null", e);
            return null;
        }
    }

    public static Application getApplication() {
        try {
            return BActivityThread.getApplication();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getApplication() failed, returning null", e);
            return null;
        }
    }

    public static int getAppPid() {
        try {
            return BActivityThread.getAppPid();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getAppPid() failed, returning -1", e);
            return -1;
        }
    }

    public static int getBUid() {
        try {
            return BActivityThread.getBUid();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getBUid() failed, returning -1", e);
            return -1;
        }
    }

    public static int getCallingBUid() {
        try {
            return BActivityThread.getCallingBUid();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getCallingBUid() failed, returning -1", e);
            return -1;
        }
    }

    public static int getUid() {
        try {
            return BActivityThread.getUid();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getUid() failed, returning -1", e);
            return -1;
        }
    }

    public static int getUserId() {
        try {
            return BActivityThread.getUserId();
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.getUserId() failed, returning -1", e);
            return -1;
        }
    }

    public static void ensureActivityContext(android.app.Activity activity) {
        try {
            BActivityThread.ensureActivityContext(activity);
        } catch (Exception e) {
            Slog.w(TAG, "BActivityThread.ensureActivityContext() failed", e);
        }
    }
    
    
    private void setEssentialProperties(Context context, ClientConfiguration clientConfiguration, boolean isHostMainProcess) {
        if (clientConfiguration == null) {
            throw new IllegalArgumentException("ClientConfiguration is null!");
        }

        // Avoid loading the native "nexbox" library in the host main/UI process.
        // On some devices this can destabilize rendering (Surface/GraphicBuffer/EGL).
        if (isHostMainProcess || Build.VERSION.SDK_INT >= 35) {
            // Android 15 ART no longer exposes the private native symbols used by the
            // legacy path. Use the existing managed bootstrap, without resolving libart ABI.
            try {
                int result = Reflection.unseal(context);
                if (result != 0) Slog.w(TAG, "Managed hidden API bootstrap unavailable: " + result);
            } catch (Throwable t) {
                Slog.w(TAG, "Reflection.unseal failed: " + t.getMessage(), t);
            }
        } else {
            if (!NativeCore.disableHiddenApi()) {
                try {
                    Reflection.unseal(context);
                } catch (Throwable t) {
                    Slog.w(TAG, "Reflection.unseal failed: " + t.getMessage(), t);
                }
            }

            try {
                NativeCore.disableResourceLoading();
            } catch (Exception e) {
                Slog.w(TAG, "Failed to call native resource disabling: " + e.getMessage(), e);
            }
        }
        com.nexbox.reflection.NexReflection.clearCaches();
        
        
        try {
            
            System.setProperty("android.view.WindowManager.IGNORE_WINDOW_LEAKS", "true");
            System.setProperty("android.app.Activity.IGNORE_WINDOW_LEAKS", "true");
            System.setProperty("android.view.WindowManager.SUPPRESS_WINDOW_LEAK_WARNINGS", "true");
            
            
            // No mDisableOverlayLoading field exists on Android 15. This former read-only
            // probe did not disable overlays and is not a prerequisite for guest startup.
        } catch (Exception e) {
            Slog.w(TAG, "Failed to set essential properties: " + e.getMessage(), e);
        }
    }
    
    
    private void initVpnService() {
        try {
            
            if (mClientConfiguration == null || !mClientConfiguration.isUseVpnNetwork()) {
                Slog.d(TAG, "VPN network mode disabled, using normal network");
                return;
            }

            // Safety: the current ProxyVpnService implementation does not implement packet
            // forwarding (TUN read/write + TCP/UDP handling). On modern Android (including API 36),
            // establishing a VPN with a default route will black-hole the app’s traffic.
            // Until a real tunnel implementation exists, skip starting the VPN to preserve
            // normal internet connectivity.
            if (Build.VERSION.SDK_INT >= 34) {
                Slog.w(TAG, "VPN network mode requested but disabled on this Android version (stub VPN would break connectivity). Falling back to normal network.");
                try {
                    // Best-effort: stop any previously started instance after upgrade.
                    Intent stopIntent = new Intent(getContext(), com.nexbox.core.proxy.ProxyVpnService.class);
                    getContext().stopService(stopIntent);
                } catch (Throwable ignored) {
                }
                return;
            }
            
            
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        
                        Intent vpnIntent = new Intent(getContext(), com.nexbox.core.proxy.ProxyVpnService.class);
                        vpnIntent.setAction("android.net.VpnService");
                        
                        getContext().startForegroundService(vpnIntent);
                        
                        Slog.d(TAG, "VPN service started successfully for internet access");
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed to start VPN service: " + e.getMessage(), e);
                        
                        
                    }
                }
            }, "VPNServiceInit").start();
            
        } catch (Exception e) {
            Slog.w(TAG, "Failed to initialize VPN service: " + e.getMessage(), e);
            
        }
    }
    
    
    private static void ensureProperInitialization() {
        try {
            // Keep the host main/UI process as “clean” as possible: no native init or
            // ActivityThread hooking. These are intended for virtualized app/server processes
            // and may destabilize rendering on some OEM builds.
            if (NexBoxCore.get().isMainProcess()) {
                Slog.d(TAG, "Main process detected; skipping NativeCore/BActivityThread initialization");
                return;
            }

            
            Slog.d(TAG, "Ensuring proper initialization order...");
            
            
            try {
                NativeCore.init(android.os.Build.VERSION.SDK_INT);
                Slog.d(TAG, "NativeCore initialized successfully");
            } catch (Exception e) {
                Slog.w(TAG, "NativeCore initialization failed: " + e.getMessage(), e);
            }
            
            
            try {
                if (!NexBoxCore.get().waitForServicesAvailable(SERVICE_INIT_TIMEOUT_MS)) {
                    Slog.w(TAG, "Service initialization unavailable after bounded wait");
                }
            } catch (Exception e) {
                Slog.w(TAG, "ServiceManager initialization failed: " + e.getMessage(), e);
            }
            
            
            try {
                BActivityThread.hookActivityThread();
                Slog.d(TAG, "BActivityThread hooks initialized successfully");
            } catch (Exception e) {
                Slog.w(TAG, "BActivityThread hooks initialization failed: " + e.getMessage(), e);
            }
            
            Slog.d(TAG, "Proper initialization order ensured");
        } catch (Exception e) {
            Slog.e(TAG, "Failed to ensure proper initialization order", e);
        }
    }

    
    public static boolean isRunningApplication(String packageName, int userId) {
        
        try {
            
            android.app.ActivityManager am = (android.app.ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            
            
            
            ServiceManager.get();
            com.nexbox.core.core.system.am.ActivityStack stack =
                (com.nexbox.core.core.system.am.ActivityStack) ServiceManager.getService(ServiceManager.ACTIVITY_MANAGER);
            if (stack == null) return false;
            java.util.Map<Integer, com.nexbox.core.core.system.am.TaskRecord> tasks =
                    com.nexbox.core.utils.Reflector.with(stack).field("mTasks").get();
            if (tasks == null) return false;
            for (com.nexbox.core.core.system.am.TaskRecord task : tasks.values()) {
                if (task.userId == userId && task.taskAffinity != null && task.taskAffinity.contains(packageName)) {
                    
                    for (com.nexbox.core.core.system.am.ActivityRecord activity : task.activities) {
                        if (!activity.finished) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "isRunningApplication failed: " + e.getMessage(), e);
        }
        return false;
    }

    
    public void addServiceAvailableCallback(Runnable callback) {
        if (callback == null) return;
        synchronized (mServiceCallbackLock) {
            if (mServiceAvailableCallbacks.containsKey(callback)) return;
            Runnable delivery = new Runnable() {
                @Override
                public void run() {
                    synchronized (mServiceCallbackLock) {
                        if (mServiceAvailableCallbacks.get(callback) != this) return;
                        if (!isServicesAvailable()) return;
                        mServiceAvailableCallbacks.remove(callback);
                    }
                    try {
                        callback.run();
                    } catch (Exception failure) {
                        Slog.e(TAG, "Error in service available callback", failure);
                    }
                }
            };
            mServiceAvailableCallbacks.put(callback, delivery);
        }
        if (isServicesAvailable()) notifyServiceAvailableCallbacks();
        else requestServiceInitialization();
    }

    public void removeServiceAvailableCallback(Runnable callback) {
        Runnable delivery;
        synchronized (mServiceCallbackLock) {
            delivery = mServiceAvailableCallbacks.remove(callback);
        }
        if (delivery != null) mHandler.removeCallbacks(delivery);
    }

    private void notifyServiceAvailableCallbacks() {
        List<Runnable> callbacks;
        synchronized (mServiceCallbackLock) {
            callbacks = new ArrayList<>(mServiceAvailableCallbacks.values());
        }
        for (Runnable callback : callbacks) {
            mHandler.removeCallbacks(callback);
            mHandler.post(callback);
        }
    }

    /** Call from a worker thread; interrupting one waiter does not cancel other callers. */
    public boolean waitForServicesAvailable(long timeoutMs) {
        if (sContext == null) return false;
        if (isServicesAvailable()) return true;
        boolean ready = mServiceReadiness.await(timeoutMs) && isServicesAvailable();
        if (ready) notifyServiceAvailableCallbacks();
        return ready;
    }

    public boolean isServicesAvailable() {
        if (mServiceReadiness.getState() != ServiceReadiness.State.READY) return false;
        if (areRequiredServicesCached()) return true;
        mServiceReadiness.invalidate();
        return false;
    }

    
    public boolean isNexBoxApp(File apkFile) {
        try {
            if (apkFile == null || !apkFile.exists()) {
                return false;
            }
            
            PackageInfo packageInfo = getPackageManager().getPackageArchiveInfo(apkFile.getAbsolutePath(), 0);
            if (packageInfo != null) {
                String packageName = packageInfo.packageName;
                return packageName.equals(getHostPkg());
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error checking if APK is NexBox app: " + e.getMessage(), e);
        }
        return false;
    }
    
    
    public boolean isNexBoxApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        return packageName.equals(getHostPkg());
    }
    
    
    public interface LogSendListener {
        void onSuccess();
        void onFailure(String error);
    }

    /** @deprecated Use the host's explicit, redacted diagnostic export/share UI. */
    @Deprecated
    public void sendLogs(String caption, boolean async) {
        sendLogs(caption, async, null);
    }

    /** Kept for binary compatibility; never dumps Logcat or uploads private diagnostics. */
    @Deprecated
    public void sendLogs(String caption, boolean async, LogSendListener listener) {
        String reason = "Automatic log upload is disabled; export diagnostics from the host settings";
        Slog.w(TAG, reason);
        if (listener != null) {
            getHandler().post(() -> listener.onFailure(reason));
        }
    }
}
