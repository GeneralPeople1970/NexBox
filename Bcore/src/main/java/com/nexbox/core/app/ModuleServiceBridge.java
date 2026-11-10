package com.nexbox.core.app;

import android.app.Activity;
import android.app.Application;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.IBinder;

import java.util.Arrays;
import java.util.List;

import io.github.libxposed.service.IXposedService;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.utils.Slog;

/** Deliver the official service through the module's own declared XposedProvider, without hooks. */
final class ModuleServiceBridge implements Application.ActivityLifecycleCallbacks {
    private final Application application;
    private final String authority;
    private IBinder delivered;

    private ModuleServiceBridge(Application application, String authority) {
        this.application = application;
        this.authority = authority;
    }

    static void install(Application application, List<ProviderInfo> providers) {
        String packageName = BActivityThread.getAppPackageName();
        if (packageName == null || providers == null) return;
        String authority = packageName + IXposedService.AUTHORITY_SUFFIX;
        boolean declared = providers.stream().anyMatch(provider -> provider != null && provider.enabled
                && packageName.equals(provider.packageName) && provider.authority != null
                && Arrays.asList(provider.authority.split(";")).contains(authority));
        if (!declared) return; // Ordinary apps and legacy modules without the service are untouched.
        ModuleServiceBridge bridge = new ModuleServiceBridge(application, authority);
        application.registerActivityLifecycleCallbacks(bridge);
        // Providers have been installed; a listener registered later in Application.onCreate is
        // supported by XposedServiceHelper's official pending-binder cache.
        bridge.refresh();
    }

    private void refresh() {
        try {
            IBinder binder = NexBoxCore.getBXposedManager().getModuleServiceForCurrentProcess();
            if (binder == null) { delivered = null; return; }
            if (binder.equals(delivered)) return;
            Bundle extras = new Bundle();
            extras.putBinder("binder", binder);
            Bundle reply = application.getContentResolver().call(authority, IXposedService.SEND_BINDER, null, extras);
            if (reply != null) {
                delivered = binder;
                Slog.i("ModuleServiceBridge", "Module service delivered package="
                        + BActivityThread.getAppPackageName() + " userId=" + BActivityThread.getUserId());
            } else Slog.w("ModuleServiceBridge", "Module did not accept the Xposed service binder");
        } catch (RuntimeException failure) {
            // A broken optional module provider must not prevent its configuration app starting.
            Slog.w("ModuleServiceBridge", "Cannot deliver module service", failure);
        }
    }

    @Override public void onActivityPreCreated(Activity activity, Bundle state) { refresh(); }
    @Override public void onActivityResumed(Activity activity) { refresh(); }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
