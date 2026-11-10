package com.nexbox.core.core.system.user;

/** Pure ownership rules shared by OS-resource cleanup and regression fixtures. */
public final class UserResourceNamespace {
    // Android persists channel IDs (including deleted channels) with user notification settings.
    // Keep these legacy storage markers; changing them would silently reset those settings.
    public static final String CHANNEL_MARKER = "@black-";
    public static final String GROUP_MARKER = "@black-group-";
    private static final String NOTIFICATION_TAG_PREFIX = "nexbox-user:";

    private UserResourceNamespace() { }

    public static String scopedId(String originalId, String marker, int userId, long createTime) {
        if (originalId == null) return null;
        if (marker == null || marker.isEmpty()) throw new IllegalArgumentException("A namespace marker is required");
        requireIdentity(userId, createTime);
        // Always append: an application-controlled ID containing a marker is still an original ID.
        return originalId + marker + userId + "." + createTime;
    }

    public static String originalId(String id, String marker) {
        int index = namespaceStart(id, marker);
        if (index < 0 || parseOwner(id.substring(index + marker.length()), true) == null) return id;
        return id.substring(0, index);
    }

    /** Legacy numeric suffixes are accepted only for cleanup, never for newly created IDs. */
    public static boolean belongsToUser(String id, String marker, int userId) {
        if (userId < 0) return false;
        int index = namespaceStart(id, marker);
        if (index < 0) return false;
        Integer owner = parseOwner(id.substring(index + marker.length()), true);
        return owner != null && owner == userId;
    }

    public static String notificationTag(int userId, long createTime) {
        requireIdentity(userId, createTime);
        return NOTIFICATION_TAG_PREFIX + userId + "." + createTime;
    }

    public static boolean notificationTagBelongsToUser(String tag, int userId) {
        if (tag == null || userId < 0 || !tag.startsWith(NOTIFICATION_TAG_PREFIX)) return false;
        String identity = tag.substring(NOTIFICATION_TAG_PREFIX.length());
        int detail = identity.indexOf('|');
        if (detail >= 0) identity = identity.substring(0, detail);
        Integer owner = parseOwner(identity, false);
        return owner != null && owner == userId;
    }

    public static boolean validOwner(int ownerUserId, long ownerGeneration, int targetUserId) {
        return ownerUserId >= 0 && ownerGeneration > 0 && ownerUserId == targetUserId;
    }

    private static int namespaceStart(String id, String marker) {
        return id == null || marker == null || marker.isEmpty() ? -1 : id.lastIndexOf(marker);
    }

    private static Integer parseOwner(String identity, boolean allowLegacy) {
        int dot = identity.indexOf('.');
        if (dot < 0 && !allowLegacy) return null;
        String user = dot < 0 ? identity : identity.substring(0, dot);
        if (!canonicalDigits(user)) return null;
        try {
            int parsedUser = Integer.parseInt(user);
            if (dot >= 0) {
                String generation = identity.substring(dot + 1);
                if (!canonicalDigits(generation) || Long.parseLong(generation) <= 0) return null;
            }
            return parsedUser;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean canonicalDigits(String value) {
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) return false;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') return false;
        }
        return true;
    }

    private static void requireIdentity(int userId, long createTime) {
        if (userId < 0 || createTime <= 0) throw new IllegalArgumentException("A live user identity is required");
    }
}
