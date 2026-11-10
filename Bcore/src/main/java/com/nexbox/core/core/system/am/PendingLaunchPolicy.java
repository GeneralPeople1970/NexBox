package com.nexbox.core.core.system.am;

import java.util.Objects;

/** Identity-only checks shared by deferred activity/service dispatch; no Android side effects. */
final class PendingLaunchPolicy {
    private PendingLaunchPolicy() { }

    static boolean isSameResolvedTarget(String expectedPackage, String expectedName, String expectedProcess,
                                         String currentPackage, String currentName, String currentProcess) {
        return Objects.equals(expectedPackage, currentPackage) && Objects.equals(expectedName, currentName)
                && Objects.equals(expectedProcess, currentProcess);
    }

    static boolean isCurrentSpace(long expectedGeneration, long currentGeneration,
                                  Object expectedSpace, Object currentSpace) {
        return expectedGeneration > 0L && expectedGeneration == currentGeneration
                && expectedSpace != null && expectedSpace == currentSpace;
    }

    static boolean isCurrentProcess(int userId, long generation, int processUserId, long processGeneration,
                                    Object expectedProcess, Object currentProcess, boolean alive) {
        return generation > 0L && processUserId == userId && processGeneration == generation
                && expectedProcess != null && expectedProcess == currentProcess && alive;
    }
}
