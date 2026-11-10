package com.nexbox.core.core.system.xposed;

/** Configuration sent to one guest. Unrelated users/modules must not make its report stale. */
final class IssuedModuleState {
    final int userId;
    final String modulePackage, targetPackage;
    private final XposedConfigState.ModuleConfig config;
    private boolean invalidated;

    IssuedModuleState(int userId, String modulePackage, String targetPackage, XposedConfigState.ModuleConfig config) {
        this.userId = userId;
        this.modulePackage = modulePackage;
        this.targetPackage = targetPackage;
        this.config = config;
    }
    void invalidate(String changedPackage, int changedUser, boolean includeTarget) {
        if ((changedUser < 0 || changedUser == userId)
                && (modulePackage.equals(changedPackage) || includeTarget && targetPackage.equals(changedPackage)))
            invalidated = true;
    }
    boolean requiresRestart(XposedConfigState state) {
        XposedConfigState.ModuleConfig current = state.modules(userId).get(modulePackage);
        boolean wasActive = config.enabled && config.scope.contains(targetPackage);
        boolean isActive = current != null && current.enabled && current.scope.contains(targetPackage);
        return invalidated || wasActive != isActive;
    }
}
