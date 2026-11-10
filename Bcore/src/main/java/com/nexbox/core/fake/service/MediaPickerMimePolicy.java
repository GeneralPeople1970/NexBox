package com.nexbox.core.fake.service;

import java.util.LinkedHashSet;
import java.util.Set;

/** Computes the caller's complete accepted set before choosing a media-only picker. */
final class MediaPickerMimePolicy {
    private MediaPickerMimePolicy() {}

    static String[] resolvePickImages(String type) {
        // The action itself limits an omitted or wildcard type to visual media.
        if (type == null || "*/*".equals(type)) return new String[]{"image/*", "video/*"};
        return resolve(type, null);
    }

    /** Zero means the platform contract requires RESULT_CANCELED, not a system fallback. */
    static int resolvePickImagesMax(boolean hasMaximum, int requestedMaximum, int platformMaximum) {
        if (!hasMaximum) return 1;
        return requestedMaximum > 1 && requestedMaximum <= platformMaximum ? requestedMaximum : 0;
    }

    static String[] resolve(String base, String[] extras) {
        if (!valid(base)) return null;
        String[] requested = extras == null || extras.length == 0
                ? new String[]{base} : extras;
        Set<String> accepted = new LinkedHashSet<>();
        for (String candidate : requested) {
            if (!valid(candidate)) return null;
            String intersection = intersect(base, candidate);
            if (intersection == null) continue;
            // Returning only the media subset would change a document picker's contract.
            if (!intersection.startsWith("image/") && !intersection.startsWith("video/")) {
                return null;
            }
            accepted.add(intersection);
        }
        return accepted.isEmpty() ? null : accepted.toArray(new String[0]);
    }

    private static String intersect(String first, String second) {
        if (first.equals(second) || "*/*".equals(second)) return first;
        if ("*/*".equals(first)) return second;
        int slash = first.indexOf('/');
        if (!first.substring(0, slash).equals(second.substring(0, second.indexOf('/')))) {
            return null;
        }
        if (first.endsWith("/*")) return second;
        if (second.endsWith("/*")) return first;
        return null;
    }

    private static boolean valid(String value) {
        if (value == null) return false;
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1 || value.indexOf('/', slash + 1) >= 0) {
            return false;
        }
        String major = value.substring(0, slash);
        String minor = value.substring(slash + 1);
        if ("*".equals(major)) return "*".equals(minor);
        return token(major) && ("*".equals(minor) || token(minor));
    }

    private static boolean token(String value) {
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || "!#$%&'+-.^_`|~".indexOf(ch) >= 0)) return false;
        }
        return !value.isEmpty();
    }
}
