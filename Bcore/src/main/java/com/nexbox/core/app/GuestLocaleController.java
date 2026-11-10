package com.nexbox.core.app;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.LocaleList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.BRActivity;
import black.android.app.BRActivityThread;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.utils.Slog;

/** One guest process has one package/user identity. System Resources are never modified. */
public final class GuestLocaleController {
    private static final Map<Context, Boolean> CONTEXTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final AtomicBoolean REFRESH_QUEUED = new AtomicBoolean();
    private static volatile String languageTags;
    private static Application attached;

    private GuestLocaleController() { }

    public static void initialize(Context context) {
        languageTags = readTags();
        LocaleList.setDefault(mergedLocales());
        apply(context);
    }

    public static void attach(Application application) {
        if (application == null || attached == application) return;
        attached = application;
        apply(application);
        application.registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration configuration) {
                LocaleList.setDefault(mergedLocales());
                applyKnownContexts();
            }
            // ComponentCallbacks still requires this legacy slot; locale tracking has no work here.
            @Override
            @SuppressWarnings("deprecation")
            public void onLowMemory() { }
        });
    }

    @SuppressWarnings("deprecation")
    public static void apply(Context context) {
        if (context == null || languageTags == null || !NexBoxCore.get().isGuestProcess()) return;
        String packageName = BActivityThread.getAppPackageName();
        if (packageName == null || !packageName.equals(context.getPackageName())) return;
        Resources resources = context.getResources();
        if (resources == Resources.getSystem()) return;
        CONTEXTS.put(context, Boolean.TRUE);
        LocaleList locales = mergedLocales();
        Configuration configuration = new Configuration(resources.getConfiguration());
        if (configuration.getLocales().equals(locales)) return;
        configuration.setLocales(locales);
        // Existing guest contexts already hold this Resources instance. Creating a new configured
        // context would not update them; the system Resources exclusion above must remain in place.
        resources.updateConfiguration(configuration, resources.getDisplayMetrics());
    }

    public static void requestRefresh() {
        if (!REFRESH_QUEUED.compareAndSet(false, true)) return;
        NexBoxCore.get().getHandler().post(() -> {
            REFRESH_QUEUED.set(false);
            try {
                String current = readTags();
                if (current.equals(languageTags)) return;
                languageTags = current;
                LocaleList.setDefault(mergedLocales());
                applyKnownContexts();
                Application application = BActivityThread.getApplication();
                if (application != null) {
                    apply(application);
                    application.onConfigurationChanged(new Configuration(application.getResources().getConfiguration()));
                }
                Map<IBinder, Object> records = BRActivityThread.getWithException(NexBoxCore.mainThread()).mActivities();
                for (IBinder token : new ArrayList<>(records.keySet())) {
                    Activity activity = BActivityThread.getActivityByToken(token);
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()
                            || !BActivityThread.getAppPackageName().equals(activity.getPackageName())) continue;
                    apply(activity);
                    ActivityInfo info = BRActivity.get(activity).mActivityInfo();
                    int required = ActivityInfo.CONFIG_LOCALE | ActivityInfo.CONFIG_LAYOUT_DIRECTION;
                    if (info != null && (info.configChanges & required) == required) {
                        activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
                    } else activity.recreate();
                }
            } catch (Exception failure) {
                Slog.w("GuestLocales", "Could not apply stored application locales", failure);
            }
        });
    }

    private static String readTags() {
        try {
            IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service is unavailable");
            return packages.getApplicationLocaleState(BActivityThread.getAppPackageName(), BActivityThread.getUserId())
                    .getString("locales", "");
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Cannot read application locales", failure);
        }
    }

    private static LocaleList mergedLocales() {
        LinkedHashSet<Locale> locales = new LinkedHashSet<>();
        LocaleList requested = LocaleList.forLanguageTags(languageTags == null ? "" : languageTags);
        LocaleList system = Resources.getSystem().getConfiguration().getLocales();
        for (int i = 0; i < requested.size(); i++) locales.add(requested.get(i));
        for (int i = 0; i < system.size(); i++) locales.add(system.get(i));
        return new LocaleList(locales.toArray(new Locale[0]));
    }

    private static void applyKnownContexts() {
        ArrayList<Context> contexts;
        synchronized (CONTEXTS) { contexts = new ArrayList<>(CONTEXTS.keySet()); }
        for (Context context : contexts) apply(context);
    }
}
