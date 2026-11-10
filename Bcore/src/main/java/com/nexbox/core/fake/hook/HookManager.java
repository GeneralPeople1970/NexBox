package com.nexbox.core.fake.hook;


import java.util.LinkedHashMap;
import java.util.Map;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.fake.delegate.AppInstrumentation;

import com.nexbox.core.fake.service.HCallbackProxy;
import com.nexbox.core.fake.service.IAccessibilityManagerProxy;
import com.nexbox.core.fake.service.IAccountManagerProxy;
import com.nexbox.core.fake.service.IActivityClientProxy;
import com.nexbox.core.fake.service.IActivityManagerProxy;
import com.nexbox.core.fake.service.IActivityTaskManagerProxy;
import com.nexbox.core.fake.service.IAlarmManagerProxy;
import com.nexbox.core.fake.service.IAppOpsManagerProxy;
import com.nexbox.core.fake.service.IAppWidgetManagerProxy;
import com.nexbox.core.fake.service.IAutofillManagerProxy;
import com.nexbox.core.fake.service.IBlobStoreManagerProxy;
import com.nexbox.core.fake.service.IInputMethodManagerProxy;
import com.nexbox.core.fake.service.IClipboardStub;
import com.nexbox.core.fake.service.ICredentialManagerProxy;
import com.nexbox.core.fake.service.ISensitiveContentProtectionManagerProxy;
import com.nexbox.core.fake.service.IConnectivityManagerProxy;
import com.nexbox.core.fake.service.IDnsResolverProxy;
import com.nexbox.core.fake.service.IContextHubServiceProxy;
import com.nexbox.core.fake.service.IDeviceIdentifiersPolicyProxy;
import com.nexbox.core.fake.service.IDevicePolicyManagerProxy;
import com.nexbox.core.fake.service.IDisplayManagerProxy;
import com.nexbox.core.fake.service.IFingerprintManagerProxy;
import com.nexbox.core.fake.service.IGraphicsStatsProxy;
import com.nexbox.core.fake.service.IJobServiceProxy;
import com.nexbox.core.fake.service.ILauncherAppsProxy;
import com.nexbox.core.fake.service.ILocaleManagerProxy;
import com.nexbox.core.fake.service.ILocationManagerProxy;
import com.nexbox.core.fake.service.IMediaRouterServiceProxy;
import com.nexbox.core.fake.service.IMediaSessionManagerProxy;
import com.nexbox.core.fake.service.IAudioServiceProxy;
import com.nexbox.core.fake.service.ISensorPrivacyManagerProxy;
import com.nexbox.core.fake.service.ContentResolverProxy;
import com.nexbox.core.fake.service.IMiuiSecurityManagerProxy;
import com.nexbox.core.fake.service.AudioPermissionProxy;

import com.nexbox.core.fake.service.INetworkManagementServiceProxy;
import com.nexbox.core.fake.service.INotificationManagerProxy;
import com.nexbox.core.fake.service.IPackageManagerProxy;
import com.nexbox.core.fake.service.IPermissionManagerProxy;
import com.nexbox.core.fake.service.IPersistentDataBlockServiceProxy;
import com.nexbox.core.fake.service.IPhoneSubInfoProxy;
import com.nexbox.core.fake.service.IPhoneSubInfoServiceProxy;
import com.nexbox.core.fake.service.IPowerManagerProxy;
import com.nexbox.core.fake.service.IRoleManagerProxy;
import com.nexbox.core.fake.service.IShortcutManagerProxy;
import com.nexbox.core.fake.service.IStorageManagerProxy;
import com.nexbox.core.fake.service.IStorageStatsManagerProxy;
import com.nexbox.core.fake.service.ISystemUpdateProxy;
import com.nexbox.core.fake.service.ISubServiceProxy;
import com.nexbox.core.fake.service.ITelephonyManagerProxy;
import com.nexbox.core.fake.service.ITelephonyRegistryProxy;
import com.nexbox.core.fake.service.IUiModeManagerProxy;
import com.nexbox.core.fake.service.IUserManagerProxy;
import com.nexbox.core.fake.service.IVibratorServiceProxy;
import com.nexbox.core.fake.service.IVpnManagerProxy;
import com.nexbox.core.fake.service.IWifiManagerProxy;
import com.nexbox.core.fake.service.IWifiScannerProxy;
import com.nexbox.core.fake.service.IWindowManagerProxy;
import com.nexbox.core.fake.service.context.ContentServiceStub;
import com.nexbox.core.fake.service.context.RestrictionsManagerStub;
import com.nexbox.core.fake.service.libcore.OsStub;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.fake.service.StatsManagerAvailabilityHook;
import com.nexbox.core.fake.service.SystemTextServicesHook;
import com.nexbox.core.fake.service.ISafetyCenterManagerProxy;



public class HookManager {
    public static final String TAG = "HookManager";

    private static final HookManager sHookManager = new HookManager();

    private final Map<Class<?>, IInjectHook> mInjectors = new LinkedHashMap<>();

    public static HookManager get() {
        return sHookManager;
    }

    public void init() {
        if (NexBoxCore.get().isGuestProcess() || NexBoxCore.get().isServerProcess()) {
            if (NexBoxCore.get().isGuestProcess()) {
                addInjector(new StatsManagerAvailabilityHook());
                addInjector(new SystemTextServicesHook());
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    addInjector(new IBlobStoreManagerProxy());
                }
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    addInjector(new ISafetyCenterManagerProxy());
                }
            }
            addInjector(new IDisplayManagerProxy());
            addInjector(new OsStub());
            addInjector(new IActivityManagerProxy());
            addInjector(new IPackageManagerProxy());
            addInjector(new ITelephonyManagerProxy());
            addInjector(new HCallbackProxy());
            addInjector(new IAppOpsManagerProxy());
            addInjector(new INotificationManagerProxy());
            addInjector(new IAlarmManagerProxy());
            if (NexBoxCore.get().isGuestProcess() && android.os.Build.VERSION.SDK_INT >= 29)
                addInjector(new com.nexbox.core.fake.service.IUriGrantsManagerProxy());
            addInjector(new IAppWidgetManagerProxy());
            addInjector(new IInputMethodManagerProxy());
            addInjector(new IClipboardStub());
            addInjector(new ContentServiceStub());
            addInjector(new IWindowManagerProxy());
            addInjector(new IUserManagerProxy());
            addInjector(new RestrictionsManagerStub());
            addInjector(new IMediaSessionManagerProxy());
            addInjector(new IAudioServiceProxy());
            addInjector(new ISensorPrivacyManagerProxy());
            addInjector(new ContentResolverProxy());
            addInjector(new IMiuiSecurityManagerProxy());
            addInjector(new AudioPermissionProxy());
            addInjector(new ILocationManagerProxy());
            addInjector(new IStorageManagerProxy());
            addInjector(new ILauncherAppsProxy());
            addInjector(new ILocaleManagerProxy());
            addInjector(new IJobServiceProxy());
            addInjector(new IAccessibilityManagerProxy());
            addInjector(new ITelephonyRegistryProxy());
            addInjector(new IDevicePolicyManagerProxy());
            addInjector(new IAccountManagerProxy());
            addInjector(new IConnectivityManagerProxy());
            addInjector(new IDnsResolverProxy());

            if (android.os.Build.VERSION.SDK_INT >= 34) {
                addInjector(new ICredentialManagerProxy());
            }
            addInjector(new IPhoneSubInfoServiceProxy());
            addInjector(new ISubServiceProxy());
            addInjector(new IPhoneSubInfoProxy());
            addInjector(new IMediaRouterServiceProxy());
            addInjector(new IPowerManagerProxy());
            addInjector(new IContextHubServiceProxy());
            
            addInjector(new IVibratorServiceProxy());
            addInjector(new IPersistentDataBlockServiceProxy());
            addInjector(AppInstrumentation.get());
            
            addInjector(new IWifiManagerProxy());
            addInjector(new IWifiScannerProxy());
            
            addInjector(new IActivityClientProxy(null));
            addInjector(new IVpnManagerProxy());
            
            addInjector(new ISensitiveContentProtectionManagerProxy());
            
            addInjector(new IPermissionManagerProxy());
            
            addInjector(new IActivityTaskManagerProxy());
            addInjector(new IRoleManagerProxy());
            
            addInjector(new ISystemUpdateProxy());
            
            addInjector(new IAutofillManagerProxy());
            addInjector(new IDeviceIdentifiersPolicyProxy());
            addInjector(new IStorageStatsManagerProxy());
            
            addInjector(new IUiModeManagerProxy());
            addInjector(new IShortcutManagerProxy());
            
            addInjector(new INetworkManagementServiceProxy());
            
            addInjector(new IFingerprintManagerProxy());
            addInjector(new IGraphicsStatsProxy());
        }
        injectAll();
    }

    public void checkEnv(Class<?> clazz) {
        IInjectHook iInjectHook = mInjectors.get(clazz);
        if (iInjectHook != null && iInjectHook.isBadEnv()) {
            Slog.d(TAG, "checkEnv: " + clazz.getSimpleName() + " is bad env");
            iInjectHook.injectHook();
        }
    }

    public void checkAll() {
        for (Class<?> aClass : mInjectors.keySet()) {
            IInjectHook iInjectHook = mInjectors.get(aClass);
            if (iInjectHook != null && iInjectHook.isBadEnv()) {
                Slog.d(TAG, "checkEnv: " + aClass.getSimpleName() + " is bad env");
                iInjectHook.injectHook();
            }
        }
    }

    void addInjector(IInjectHook injectHook) {
        mInjectors.put(injectHook.getClass(), injectHook);
    }

    void injectAll() {
        for (IInjectHook value : mInjectors.values()) {
            try {
                Slog.d(TAG, "hook: " + value);
                value.injectHook();
                if (value instanceof IAccountManagerProxy && value.isBadEnv()) {
                    throw new IllegalStateException("Account service isolation is not active");
                }
            } catch (Exception e) {
                if (value instanceof IAccountManagerProxy) {
                    throw new IllegalStateException("Cannot install the virtual account service", e);
                }
                Slog.d(TAG, "hook error: " + value);
                
                handleHookError(value, e);
            }
        }
    }

    
    private void handleHookError(IInjectHook hook, Exception e) {
        String hookName = hook.getClass().getSimpleName();
        
        
        Slog.e(TAG, "Hook failed: " + hookName + " - " + e.getMessage(), e);
        
        
        if (hookName.contains("ActivityManager") || 
            hookName.contains("PackageManager") ||
            hookName.contains("Clipboard") ||
            hookName.contains("WebView") ||
            hookName.contains("ContentProvider")) {
            
            Slog.w(TAG, "Critical hook failed: " + hookName + ", attempting recovery");
            
            try {
                
                if (hook.isBadEnv()) {
                    Slog.d(TAG, "Attempting to recover hook: " + hookName);
                    hook.injectHook();
                }
            } catch (Exception recoveryException) {
                Slog.e(TAG, "Hook recovery failed: " + hookName, recoveryException);
            }
        }
    }

    
    public boolean areCriticalHooksInstalled() {
        Class<?>[] criticalHooks = {
            IActivityManagerProxy.class,
            IAccountManagerProxy.class,
            IPackageManagerProxy.class,
            IClipboardStub.class
        };

        for (Class<?> hookClass : criticalHooks) {
            IInjectHook hook = mInjectors.get(hookClass);
            if (hook == null) {
                Slog.w(TAG, "Critical hook missing: " + hookClass.getSimpleName());
                return false;
            }
            if (hookClass == IAccountManagerProxy.class && hook.isBadEnv()) return false;
        }
        
        Slog.d(TAG, "All critical hooks are installed");
        return true;
    }

    
    public void reinitializeHooks() {
        Slog.d(TAG, "Reinitializing all hooks");
        
        
        mInjectors.clear();
        
        
        init();
        
        Slog.d(TAG, "Hook reinitialization completed");
    }
}
