package com.nexbox.core.utils.compat;

import com.nexbox.core.utils.Slog;


import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.view.WindowManager;


public class ActivityCompat {
    private static final int[] WINDOW_ATTRIBUTES = {
            android.R.attr.windowShowWallpaper, android.R.attr.windowFullscreen
    };

    public static void fix(Activity activity) {
        try {
            applyWindowTheme(activity);
        } catch (Throwable e) {
            Slog.e("ActivityCompat", "action=applyWindowTheme stage=themeAttributes result=failed", e);
        }

        TaskDescriptionCompat.update(activity, true);
    }

    static void applyWindowTheme(Activity activity) {
        int flags = themeWindowFlags(activity);
        if (flags != 0) activity.getWindow().addFlags(flags);
    }

    // Preserve the legacy theme flag's layout semantics, not only insets visibility.
    @SuppressWarnings("deprecation")
    static int themeWindowFlags(Context context) {
        TypedArray attributes = context.obtainStyledAttributes(WINDOW_ATTRIBUTES);
        try {
            int flags = attributes.getBoolean(0, false) ? WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER : 0;
            if (attributes.getBoolean(1, false)) flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
            return flags;
        } finally {
            attributes.recycle();
        }
    }
}
