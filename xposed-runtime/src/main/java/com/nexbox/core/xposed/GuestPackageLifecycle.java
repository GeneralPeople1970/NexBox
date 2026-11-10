package com.nexbox.core.xposed;

import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Observe real LoadedApk phases; never instantiate an app factory or classloader a second time. */
final class GuestPackageLifecycle {
    private GuestPackageLifecycle() {}

    static void install(String hostPackage) throws ReflectiveOperationException {
        Class<?> loadedApk = Class.forName("android.app.LoadedApk");
        Method createFactory = loadedApk.getDeclaredMethod("createAppFactory", ApplicationInfo.class, ClassLoader.class);
        Method createLoader = loadedApk.getDeclaredMethod("createOrUpdateClassLoaderLocked", List.class);
        Field info = field(loadedApk, "mApplicationInfo");
        Field includeCode = field(loadedApk, "mIncludeCode");
        Field defaultLoader = field(loadedApk, "mDefaultClassLoader");
        Field effectiveLoader = field(loadedApk, "mClassLoader");
        Field factory = field(loadedApk, "mAppComponentFactory");
        XC_MethodHook.Unhook first = XposedBridge.hookMethod(createFactory, new XC_MethodHook(Integer.MAX_VALUE) {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ApplicationInfo app = (ApplicationInfo) param.args[0];
                ClassLoader loader = (ClassLoader) param.args[1];
                // LoadedApk's constructor initially calls createAppFactory with a null base loader.
                // The real event is later, after mDefaultClassLoader has been assigned.
                if (loader == null || loader != defaultLoader.get(param.thisObject)
                        || !includeCode.getBoolean(param.thisObject) || !eligible(app, hostPackage)) return;
                XposedRuntime.dispatchPackageLoaded(app, loader);
            }
        });
        try {
            XposedBridge.hookMethod(createLoader, new XC_MethodHook(Integer.MIN_VALUE) {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.hasThrowable() || !includeCode.getBoolean(param.thisObject)) return;
                    ApplicationInfo app = (ApplicationInfo) info.get(param.thisObject);
                    if (!eligible(app, hostPackage)) return;
                    ClassLoader defaultCl = (ClassLoader) defaultLoader.get(param.thisObject);
                    ClassLoader cl = (ClassLoader) effectiveLoader.get(param.thisObject);
                    AppComponentFactory appFactory = (AppComponentFactory) factory.get(param.thisObject);
                    if (defaultCl != null && cl != null && appFactory != null)
                        XposedRuntime.dispatchPackageReady(app, defaultCl, cl, appFactory);
                }
            });
        } catch (Throwable error) { first.unhook(); throw error; }
    }

    private static boolean eligible(ApplicationInfo app, String hostPackage) {
        return app != null && app.packageName != null && !app.packageName.equals(hostPackage)
                && !app.packageName.equals("android") && (app.flags & ApplicationInfo.FLAG_HAS_CODE) != 0;
    }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
