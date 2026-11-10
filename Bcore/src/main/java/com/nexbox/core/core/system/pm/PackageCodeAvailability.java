package com.nexbox.core.core.system.pm;

import android.content.pm.ApplicationInfo;

import java.io.File;

/** Source availability is separate from the validity of stored identities and user data. */
public final class PackageCodeAvailability {
    private PackageCodeAvailability() {}

    public static String unavailableReason(ApplicationInfo application) {
        if (application == null || !readableFile(application.sourceDir)) return "base_unavailable";
        if (application.splitSourceDirs != null) {
            for (String split : application.splitSourceDirs) {
                if (!readableFile(split)) return "split_unavailable";
            }
        }
        return null;
    }

    public static boolean isAvailable(ApplicationInfo application) {
        return unavailableReason(application) == null;
    }

    public static void requireAvailable(ApplicationInfo application) {
        String reason = unavailableReason(application);
        if (reason != null) throw new SourceUnavailableException(reason);
    }

    private static boolean readableFile(String path) {
        if (path == null || path.isEmpty()) return false;
        File file = new File(path);
        return file.isFile() && file.canRead();
    }

    public static final class SourceUnavailableException extends IllegalStateException {
        private SourceUnavailableException(String reason) {
            super("Cloned app source files are unavailable: " + reason);
        }
    }
}
