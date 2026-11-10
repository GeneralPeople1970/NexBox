package com.nexbox.core.fake.frameworks;

import com.nexbox.core.utils.Slog;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.nexbox.core.fake.hook.HookManager;
import com.nexbox.core.fake.hook.IInjectHook;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;


public class BResourcesManager implements IInjectHook {
    private static final String TAG = "BResourcesManager";

    @Override
    public void injectHook() {
        
        
        Slog.d(TAG, "BResourcesManager hook initialized");
    }

    @Override
    public boolean isBadEnv() {
        return false; 
    }

    
    public static String safeLoadAppLabel(Object applicationInfo) {
        if (applicationInfo == null) {
            return "Unknown App";
        }
        
        
        String packageName = getPackageNameSafely(applicationInfo);
        
        
        if (packageName != null && !packageName.isEmpty()) {
            return packageName;
        }
        
        
        try {
            
            Method getLabelResMethod = applicationInfo.getClass().getMethod("getLabelRes");
            Integer labelRes = (Integer) getLabelResMethod.invoke(applicationInfo);
            
            if (labelRes != null && labelRes != 0) {
                
                Object packageManager = getPackageManager();
                if (packageManager != null) {
                    Method getTextMethod = packageManager.getClass().getMethod("getText", String.class, int.class, android.content.pm.ApplicationInfo.class);
                    Object label = getTextMethod.invoke(packageManager, packageName, labelRes, applicationInfo);
                    if (label != null) {
                        return label.toString();
                    }
                }
            }
            
            
            Method loadLabelMethod = applicationInfo.getClass().getMethod("loadLabel", android.content.pm.PackageManager.class);
            Object packageManager = getPackageManager();
            if (packageManager != null) {
                Object label = loadLabelMethod.invoke(applicationInfo, packageManager);
                if (label != null) {
                    return label.toString();
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to load app label: " + e.getMessage(), e);
        }
        
        return "Unknown App";
    }
    
    
    private static String getPackageNameSafely(Object applicationInfo) {
        
        try {
            Field packageNameField = applicationInfo.getClass().getDeclaredField("packageName");
            packageNameField.setAccessible(true);
            Object packageName = packageNameField.get(applicationInfo);
            if (packageName != null) {
                return packageName.toString();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package name via field: " + e.getMessage(), e);
        }
        
        
        try {
            Method getPackageNameMethod = applicationInfo.getClass().getMethod("getPackageName");
            Object packageName = getPackageNameMethod.invoke(applicationInfo);
            if (packageName != null) {
                return packageName.toString();
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package name via method: " + e.getMessage(), e);
        }
        
        
        try {
            String toString = applicationInfo.toString();
            if (toString.contains("packageName=")) {
                int start = toString.indexOf("packageName=") + 12;
                int end = toString.indexOf(" ", start);
                if (end == -1) end = toString.length();
                return toString.substring(start, end);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package name via toString: " + e.getMessage(), e);
        }
        
        return null;
    }

    
    public static Object safeLoadAppIcon(Object applicationInfo) {
        try {
            if (applicationInfo != null) {
                
                Method getIconMethod = applicationInfo.getClass().getMethod("getIcon");
                Integer iconRes = (Integer) getIconMethod.invoke(applicationInfo);
                
                if (iconRes != null && iconRes != 0) {
                    
                    Object packageManager = getPackageManager();
                    if (packageManager != null) {
                        try {
                            Method getDrawableMethod = packageManager.getClass().getMethod("getDrawable", String.class, int.class, android.content.pm.ApplicationInfo.class);
                            Object drawable = getDrawableMethod.invoke(packageManager, getPackageName(applicationInfo), iconRes, applicationInfo);
                            if (drawable != null) {
                                return drawable;
                            }
                        } catch (Exception e) {
                            Slog.w(TAG, "Failed to load icon via getDrawable: " + e.getMessage(), e);
                        }
                    }
                }
                
                
                Method loadIconMethod = applicationInfo.getClass().getMethod("loadIcon", android.content.pm.PackageManager.class);
                Object packageManager = getPackageManager();
                if (packageManager != null) {
                    return loadIconMethod.invoke(applicationInfo, packageManager);
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to load app icon: " + e.getMessage(), e);
        }
        return null;
    }
    
    
    private static String getPackageName(Object applicationInfo) {
        String packageName = getPackageNameSafely(applicationInfo);
        return packageName != null ? packageName : "";
    }

    
    private static Object getPackageManager() {
        try {
            Class<?> nexBoxCoreClass = Class.forName("com.nexbox.core.NexBoxCore");
            Method getPackageManagerMethod = nexBoxCoreClass.getMethod("getPackageManager");
            return getPackageManagerMethod.invoke(null);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get PackageManager: " + e.getMessage(), e);
            return null;
        }
    }
    
    
    public static Object createSafeResourceManager(Context context) {
        try {
            
            Class<?> resourcesManagerClass = Class.forName("android.app.ResourcesManager");
            // Keep direct constructor failures for this hidden type; only Exceptions are caught below.
            @SuppressWarnings("deprecation")
            Object resourcesManager = resourcesManagerClass.newInstance();
            
            
            try {
                Field disableOverlayField = resourcesManagerClass.getDeclaredField("mDisableOverlayLoading");
                disableOverlayField.setAccessible(true);
                disableOverlayField.setBoolean(resourcesManager, true);
            } catch (Exception e) {
                Slog.w(TAG, "Could not set overlay loading flag: " + e.getMessage(), e);
            }
            
            return resourcesManager;
        } catch (Exception e) {
            Slog.w(TAG, "Failed to create safe resource manager: " + e.getMessage(), e);
            return null;
        }
    }
    
    
    public static boolean isProblematicOverlayPath(String path) {
        return path != null && path.contains("/data/resource-cache/") && path.contains(".frro");
    }
}
