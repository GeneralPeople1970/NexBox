package android.app;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import de.robv.android.xposed.XSharedPreferences;
import com.nexbox.core.xposed.XposedRuntime;

/** Legacy binary-compatible helpers reporting guest identity, not the host application. */
public final class AndroidAppHelper {
    private AndroidAppHelper() {}
    public static String currentProcessName() { return XposedRuntime.currentProcessName(); }
    public static String currentPackageName() { return XposedRuntime.currentPackageName(); }
    public static ApplicationInfo currentApplicationInfo() { return XposedRuntime.currentApplicationInfo(); }
    public static Application currentApplication() { return XposedRuntime.currentApplication(); }
    @Deprecated public static SharedPreferences getSharedPreferencesForPackage(String pkg, String name, int mode) {
        return new XSharedPreferences(pkg, name);
    }
    @Deprecated public static SharedPreferences getDefaultSharedPreferencesForPackage(String pkg) {
        return new XSharedPreferences(pkg);
    }
    @Deprecated public static void reloadSharedPreferencesIfNeeded(SharedPreferences preferences) {
        if (preferences instanceof XSharedPreferences shared) shared.reload();
    }
}
