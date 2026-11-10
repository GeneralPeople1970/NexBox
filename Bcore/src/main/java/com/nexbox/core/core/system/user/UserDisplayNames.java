package com.nexbox.core.core.system.user;

import android.content.Context;

import com.nexbox.core.R;

/** Shared by the host and task descriptions; numeric IDs are not display names. */
public final class UserDisplayNames {
    private UserDisplayNames() {}

    public static String displayName(Context context, BUserInfo user) {
        if (user == null) throw new IllegalArgumentException("Missing user");
        String name = user.name;
        return isBlank(name) ? defaultName(context, user.id) : name;
    }

    public static String defaultName(Context context, int userId) {
        return context.getString(R.string.nexbox_user_display_name, userId);
    }

    private static boolean isBlank(String value) {
        if (value == null) return true;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return false;
        }
        return true;
    }
}
