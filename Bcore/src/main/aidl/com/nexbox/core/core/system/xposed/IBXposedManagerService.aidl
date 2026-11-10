package com.nexbox.core.core.system.xposed;

import android.os.IBinder;
import android.os.Bundle;
import com.nexbox.core.entity.pm.InstalledModule;
import com.nexbox.core.entity.pm.ModuleLoadState;
import com.nexbox.core.entity.pm.XposedRuntimeStatus;

interface IBXposedManagerService {
    List<InstalledModule> getInstalledModules(int userId);
    Bundle setModuleConfig(int userId, String modulePackage, boolean enabled, in List<String> scope,
        long expectedUserCreateTime);
    List<String> getPendingLegacySelections();
    XposedRuntimeStatus getRuntimeStatus();
    List<ModuleLoadState> getModuleStates(int userId);

    // Guest methods derive identity from the Binder caller, never from input arguments.
    List<InstalledModule> getModulesForCurrentProcess();
    void reportModuleLoad(String modulePackage, boolean loaded, String detail);
    IBinder getModuleServiceForCurrentProcess();
}
