package com.nexbox.core.utils.compat;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.concurrent.atomic.AtomicBoolean;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.IBUserManagerService;
import com.nexbox.core.core.system.user.UserDisplayNames;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BUserManager;
import com.nexbox.core.utils.DrawableUtils;
import com.nexbox.core.utils.Slog;

public final class TaskDescriptionCompat {
    private static final String TAG = "TaskDescription";
    private static final AtomicBoolean sReportedFailure = new AtomicBoolean();
    private static String sFallbackIconPackage;
    private static Bitmap sFallbackIcon;

    private TaskDescriptionCompat() {}

    public static ActivityManager.TaskDescription fix(ActivityManager.TaskDescription description) {
        AppConfig owner = BActivityThread.getAppConfig();
        if (description == null || owner == null) return description;
        try {
            ApplicationInfo application = BActivityThread.getApplication() == null
                    ? NexBoxCore.getBPackageManager().getApplicationInfo(owner.packageName, 0, owner.userId)
                    : BActivityThread.getApplication().getApplicationInfo();
            ActivityManager.TaskDescription updated = withLabel(description, label(application, currentUser(owner)));
            try {
                if (!hasIcon(updated)) setIcon(updated, applicationIcon(application));
            } catch (Exception failure) {
                reportFailure(owner, failure);
            }
            return updated;
        } catch (Exception failure) {
            reportFailure(owner, failure);
            return description;
        }
    }

    /** Re-read the persisted name on resume, including processes predating a host rename. */
    public static void update(Activity activity, boolean ensureIcon) {
        AppConfig owner = BActivityThread.getAppConfig();
        if (owner == null || activity == null || activity.isFinishing()) return;
        try {
            ActivityManager.TaskDescription previous = (ActivityManager.TaskDescription)
                    FrameworkMemberAccess.Member.ACTIVITY_TASK.read(activity);
            ActivityManager.TaskDescription updated = withLabel(previous,
                    label(activity.getApplicationInfo(), currentUser(owner)));
            if (ensureIcon && activity.isTaskRoot()) {
                try {
                    if (!hasIcon(updated)) {
                        PackageManager pm = activity.getPackageManager();
                        Drawable drawable;
                        try {
                            drawable = pm.getActivityIcon(activity.getComponentName());
                        } catch (PackageManager.NameNotFoundException absent) {
                            drawable = activity.getApplicationInfo().loadIcon(pm);
                        }
                        if (drawable != null) {
                            ActivityManager manager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
                            int size = manager.getLauncherLargeIconSize();
                            Bitmap bitmap = DrawableUtils.drawableToBitmap(drawable, size, size);
                            setIcon(updated, bitmap);
                        }
                    }
                } catch (Exception failure) {
                    reportFailure(owner, failure);
                }
            }
            activity.setTaskDescription(updated);
        } catch (Exception failure) {
            reportFailure(owner, failure);
        }
    }

    /** Keep the complete platform object: label/icon-only constructors lose hidden colours. */
    public static ActivityManager.TaskDescription withLabel(ActivityManager.TaskDescription description, String label) {
        if (description == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return new ActivityManager.TaskDescription.Builder().setLabel(label).build();
        }
        ActivityManager.TaskDescription updated = description == null
                ? emptyLegacyDescription() : new ActivityManager.TaskDescription(description);
        try {
            FrameworkMemberAccess.Member.TASK_LABEL.write(updated, label);
            if (!java.util.Objects.equals(label, updated.getLabel())) {
                throw new IllegalStateException("Task description label update was not retained");
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Task description label is not writable", failure);
        }
        return updated;
    }

    // The Builder does not exist on API 31-32; callers use it on API 33 and later.
    @SuppressWarnings("deprecation")
    private static ActivityManager.TaskDescription emptyLegacyDescription() {
        return new ActivityManager.TaskDescription();
    }

    public static String label(ApplicationInfo application, BUserInfo user) {
        if (application == null || application.packageName == null) {
            throw new IllegalStateException("Task application is unavailable");
        }
        CharSequence name = application.loadLabel(NexBoxCore.getPackageManager());
        String applicationName = name == null || name.toString().trim().isEmpty()
                ? application.packageName : name.toString();
        // Start from app metadata, never a previously decorated or guest-supplied task label.
        return applicationName + "[" + UserDisplayNames.displayName(NexBoxCore.getContext(), user) + "]";
    }

    private static BUserInfo currentUser(AppConfig owner) throws Exception {
        IBUserManagerService service = BUserManager.get().getService();
        if (service == null) throw new IllegalStateException("User service unavailable");
        BUserInfo user = service.getUserInfo(owner.userId);
        if (user == null || owner.userGeneration <= 0 || user.createTime != owner.userGeneration) {
            throw new IllegalStateException("Task user generation changed");
        }
        return user;
    }

    private static boolean hasIcon(ActivityManager.TaskDescription description) throws Exception {
        Object icon = FrameworkMemberAccess.Member.TASK_ICON.read(description);
        String filename = (String) FrameworkMemberAccess.Member.TASK_ICON_FILE.read(description);
        return icon != null || filename != null;
    }

    private static void setIcon(ActivityManager.TaskDescription description, Bitmap bitmap) throws Exception {
        if (bitmap != null) FrameworkMemberAccess.Member.TASK_ICON.write(description, Icon.createWithBitmap(bitmap));
    }

    private static synchronized Bitmap applicationIcon(ApplicationInfo application) {
        if (sFallbackIcon != null && application.packageName.equals(sFallbackIconPackage)) return sFallbackIcon;
        Drawable drawable = application.loadIcon(NexBoxCore.getPackageManager());
        if (drawable == null) return null;
        ActivityManager manager = (ActivityManager) NexBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        int size = manager.getLauncherLargeIconSize();
        sFallbackIcon = DrawableUtils.drawableToBitmap(drawable, size, size);
        sFallbackIconPackage = application.packageName;
        return sFallbackIcon;
    }

    private static void reportFailure(AppConfig owner, Exception failure) {
        if (Slog.isLoggable(TAG, android.util.Log.WARN) && sReportedFailure.compareAndSet(false, true)) {
            Slog.w(TAG, "stage=taskDescriptionUpdateFailed userId=" + owner.userId
                    + " generation=" + owner.userGeneration + " package=" + owner.packageName, failure);
        }
    }
}
