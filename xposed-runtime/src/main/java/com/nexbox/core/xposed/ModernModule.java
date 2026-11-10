package com.nexbox.core.xposed;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import de.robv.android.xposed.XposedBridge;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One module generation, with independent entry detachment and package callback deduplication. */
final class ModernModule {
    private static final class Entry {
        volatile XposedModule instance;
        Entry(XposedModule instance) { this.instance = instance; }
        void detach() { instance = null; }
    }

    private final ModernXposedInterface api;
    private final List<Entry> entries = new ArrayList<>();
    private final List<de.robv.android.xposed.XC_MethodHook.Unhook> legacyHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> loaded = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> ready = Collections.synchronizedSet(new HashSet<>());
    private volatile Throwable failure;

    ModernModule(ApplicationInfo info, ModuleMetadata metadata, ClassLoader loader, String processName) throws Throwable {
        api = new ModernXposedInterface(info, metadata);
        try {
            for (String name : metadata.entries) {
                Class<?> type = Class.forName(name, false, loader);
                if (!XposedModule.class.isAssignableFrom(type))
                    throw new IOException("Modern entry must extend io.github.libxposed.api.XposedModule: " + name);
                XposedModule module = (XposedModule) type.getDeclaredConstructor().newInstance();
                Entry entry = new Entry(module);
                entries.add(entry);
                module.attachFramework(api, entry::detach);
                invoke(() -> module.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                    @Override public boolean isSystemServer() { return false; }
                    @Override public String getProcessName() { return processName; }
                }));
            }
        } catch (Throwable error) { fail(error); throw error; }
    }

    void packageLoaded(ApplicationInfo info, ClassLoader defaultLoader, boolean first) {
        if (failure != null || !loaded.add(info.packageName)) return;
        PackageParam param = new PackageParam(info, defaultLoader, defaultLoader, null, first);
        for (Entry entry : entries) {
            XposedModule module = entry.instance;
            if (module == null) continue;
            try { invoke(() -> module.onPackageLoaded(param)); }
            catch (Throwable error) { fail(error); return; }
        }
    }

    void packageReady(ApplicationInfo info, ClassLoader defaultLoader, ClassLoader loader,
                      AppComponentFactory factory, boolean first) {
        if (failure != null || !ready.add(info.packageName)) return;
        if (!loaded.contains(info.packageName)) {
            fail(new IllegalStateException("Package was initialized before its libxposed onPackageLoaded callback: " + info.packageName));
            return;
        }
        PackageParam param = new PackageParam(info, defaultLoader, loader, factory, first);
        for (Entry entry : entries) {
            XposedModule module = entry.instance;
            if (module == null) continue;
            try { invoke(() -> module.onPackageReady(param)); }
            catch (Throwable error) { fail(error); return; }
        }
    }

    private void fail(Throwable error) {
        failure = error;
        api.hooks.retire();
        legacyHooks.forEach(de.robv.android.xposed.XC_MethodHook.Unhook::unhook);
        legacyHooks.clear();
        entries.forEach(Entry::detach);
        XposedBridge.log(error);
    }

    private void invoke(Runnable callback) {
        XposedBridge.beginModuleLoad();
        boolean success = false;
        try { callback.run(); success = true; }
        finally {
            for (var hook : XposedBridge.finishModuleLoadAndCollect(success)) {
                // Modern handles are owned by ModernHooks. Do not keep stale callbacks alive here.
                if (!(hook.getCallback() instanceof XposedBridge.Interceptor)) legacyHooks.add(hook);
            }
        }
    }

    Throwable failure() { return failure; }

    /** API parameters are immutable snapshots, not views of mutable process-global fields. */
    static final class PackageParam implements XposedModuleInterface.PackageReadyParam {
        private final ApplicationInfo info;
        private final ClassLoader defaultLoader, loader;
        private final AppComponentFactory factory;
        private final boolean first;
        PackageParam(ApplicationInfo info, ClassLoader defaultLoader, ClassLoader loader,
                     AppComponentFactory factory, boolean first) {
            this.info = new ApplicationInfo(info);
            this.defaultLoader = defaultLoader;
            this.loader = loader;
            this.factory = factory;
            this.first = first;
        }
        @Override public String getPackageName() { return info.packageName; }
        @Override public ApplicationInfo getApplicationInfo() { return new ApplicationInfo(info); }
        @Override public boolean isFirstPackage() { return first; }
        @Override public ClassLoader getDefaultClassLoader() { return defaultLoader; }
        @Override public ClassLoader getClassLoader() { return loader; }
        @Override public AppComponentFactory getAppComponentFactory() { return factory; }
    }
}
