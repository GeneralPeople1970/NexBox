package com.nexbox.core.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.R;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;
import android.widget.ImageView;
import android.widget.TextView;
import android.view.animation.OvershootInterpolator;


public class LauncherActivity extends Activity {
    public static final String TAG = "SplashScreen";

    public static final String KEY_INTENT = "launch_intent";
    public static final String KEY_PKG = "launch_pkg";
    public static final String KEY_USER_ID = "launch_user_id";
    public static final String KEY_USER_GENERATION = "launch_user_generation";
    private boolean isRunning = false;

    @Nullable
    static String getLaunchPackage(@Nullable Intent intent) {
        if (intent == null) return null;
        // Explicit component intents need not carry an Intent.package restriction.
        // The component is authoritative when both are present, just as in Android resolution.
        return intent.getComponent() != null
                ? intent.getComponent().getPackageName() : intent.getPackage();
    }

    public static void launch(Intent intent, int userId) {
        try {
            com.nexbox.core.core.system.user.IBUserManagerService users =
                    com.nexbox.core.fake.frameworks.BUserManager.get().getService();
            if (users == null) throw new IllegalStateException("User service unavailable");
            com.nexbox.core.core.system.user.BUserInfo user = users.getUserInfo(userId);
            if (user == null || user.createTime <= 0L) throw new IllegalStateException("Launch target unavailable");
            launch(intent, userId, user.createTime);
        } catch (android.os.RemoteException failure) {
            throw new IllegalStateException("Launch target could not be verified", failure);
        }
    }

    public static void launch(Intent intent, int userId, long expectedUserCreateTime) {
        if (userId < 0 || expectedUserCreateTime <= 0L) {
            throw new IllegalArgumentException("A captured launch target is required");
        }
        try {
            String packageName = getLaunchPackage(intent);
            Intent splash = new Intent();
            splash.setClass(NexBoxCore.getContext(), LauncherActivity.class);
            
            splash.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            splash.putExtra(LauncherActivity.KEY_INTENT, intent);
            splash.putExtra(LauncherActivity.KEY_PKG, packageName);
            splash.putExtra(LauncherActivity.KEY_USER_ID, userId);
            splash.putExtra(LauncherActivity.KEY_USER_GENERATION, expectedUserCreateTime);
            NexBoxCore.getContext().startActivity(splash);
            Slog.i(TAG, "stage=requestDispatched package=" + packageName + " userId=" + userId
                    + " generation=" + expectedUserCreateTime
                    + " startupComplete=unknown");
        } catch (Exception e) {
            Slog.e(TAG, "stage=requestRejected userId=" + userId, e);
            throw new IllegalStateException("Launcher request was not dispatched", e);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
            
            Intent intent = getIntent();
            if (intent == null) {
                Slog.w(TAG, "Intent is null, finishing activity");
                finish();
                return;
            }
            
            Intent launchIntent = BundleCompat.getParcelable(intent, KEY_INTENT, Intent.class);
            String packageName = intent.getStringExtra(KEY_PKG);
            if (packageName == null) packageName = getLaunchPackage(launchIntent);
            int userId = intent.getIntExtra(KEY_USER_ID, 0);
            long userGeneration = intent.getLongExtra(KEY_USER_GENERATION, 0L);

            if (launchIntent == null || packageName == null || userId < 0 || userGeneration <= 0L) {
                Slog.w(TAG, "Missing launch intent, package or user identity, finishing activity");
                finish();
                return;
            }

            Slog.d(TAG, "LauncherActivity.onCreate() for package: " + packageName + ", userId: " + userId);

            
            PackageInfo packageInfo = getPackageInfoWithFallback(packageName, userId);
            
            if (packageInfo == null) {
                Slog.w(TAG, "Package info not available for " + packageName + ", but proceeding with launch");
                
            } else {
                Slog.d(TAG, "Successfully retrieved package info for " + packageName);
            }
            
            
            Drawable drawable = null;
            String appName = packageName;
            try {
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    PackageManager pm = getPackageManager();
                    drawable = pm.getApplicationIcon(packageInfo.applicationInfo);
                    CharSequence label = pm.getApplicationLabel(packageInfo.applicationInfo);
                    if (label != null) appName = label.toString();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to load app icon or name for " + packageName + ": " + e.getMessage(), e);
            }
            setContentView(R.layout.activity_launcher);
            ImageView iconView = findViewById(R.id.iv_icon);
            TextView nameView = findViewById(R.id.tv_app_name);
            if (nameView != null) {
                nameView.setText(appName);
                nameView.setAlpha(0f);
                nameView.animate()
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(200)
                    .start();
            }
            if (iconView != null && drawable != null) {
                iconView.setImageDrawable(drawable);
                iconView.setScaleX(0.7f);
                iconView.setScaleY(0.7f);
                iconView.setAlpha(0f);
                iconView.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .alpha(1f)
                    .setDuration(350)
                    .setInterpolator(new OvershootInterpolator())
                    .withEndAction(() -> iconView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .start())
                    .start();
            }
            
            
            launchAppAsync(launchIntent, userId, userGeneration);
            
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in LauncherActivity.onCreate()", e);
            finish();
        }
    }

    
    private PackageInfo getPackageInfoWithFallback(String packageName, int userId) {
        try {
            
            return NexBoxCore.getBPackageManager().getPackageInfo(packageName, 0, userId);
        } catch (Exception e) {
            Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 1): " + e.getMessage(), e);
            
            try {
                
                return NexBoxCore.getBPackageManager().getPackageInfo(packageName,
                    android.content.pm.PackageManager.GET_META_DATA, userId);
            } catch (Exception e2) {
                Slog.w(TAG, "Failed to get package info for " + packageName + " (attempt 2): " + e2.getMessage(), e2);
                
                try {
                    
                    android.content.pm.ApplicationInfo appInfo = NexBoxCore.getBPackageManager()
                        .getApplicationInfo(packageName, 0, userId);
                    
                    if (appInfo != null) {
                        
                        PackageInfo fallbackInfo = new PackageInfo();
                        fallbackInfo.packageName = packageName;
                        fallbackInfo.applicationInfo = appInfo;
                        fallbackInfo.setLongVersionCode(1L);
                        fallbackInfo.versionName = "1.0";
                        fallbackInfo.firstInstallTime = System.currentTimeMillis();
                        fallbackInfo.lastUpdateTime = System.currentTimeMillis();
                        
                        Slog.d(TAG, "Created fallback PackageInfo for " + packageName);
                        return fallbackInfo;
                    }
                } catch (Exception e3) {
                    Slog.w(TAG, "Failed to get application info for " + packageName + ": " + e3.getMessage(), e3);
                }
            }
        }
        
        return null;
    }

    
    private void launchAppAsync(final Intent launchIntent, final int userId, final long userGeneration) {
        new Thread(() -> {
            try {
                Slog.d(TAG, "Starting app launch in background thread");
                if (!NexBoxCore.get().waitForServicesAvailable(10_000L)) {
                    throw new IllegalStateException("Container services are not ready");
                }
                NexBoxCore.getBActivityManager().startActivityIfMatches(launchIntent, userId, userGeneration);
                
                Slog.d(TAG, "App launch initiated successfully");
            } catch (Exception e) {
                Slog.e(TAG, "Error launching app", e);
                
                
                runOnUiThread(() -> {
                    try {
                        
                        Slog.e(TAG, "Failed to launch app: " + e.getMessage(), e);
                        finish();
                    } catch (Exception uiException) {
                        Slog.e(TAG, "Error showing error message", uiException);
                    }
                });
            }
        }, "AppLaunchThread").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRunning = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isRunning) {
            finish();
        }
    }
}
