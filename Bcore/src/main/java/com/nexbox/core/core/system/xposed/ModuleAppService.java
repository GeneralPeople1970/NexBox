package com.nexbox.core.core.system.xposed;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedScopeCallback;
import io.github.libxposed.service.IXposedService;
import com.nexbox.core.core.system.ProcessRecord;

/**
 * Official libxposed service for a module's own app process. A Binder is evidence that this
 * module is enabled, not a claim that every target has already loaded it. Scope and identity are
 * always resolved from the live guest ProcessRecord; another user/module cannot reuse the Binder.
 *
 * @see <a href="https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API">LSPosed module-app protocol</a>
 */
final class ModuleAppService extends IXposedService.Stub {
    private final ProcessRecord owner;
    private final BXposedManagerService manager;

    ModuleAppService(ProcessRecord owner, BXposedManagerService manager) {
        this.owner = owner;
        this.manager = manager;
    }

    private XposedConfigState.ModuleConfig check() { return manager.requireModuleApp(owner); }

    // Only advertise implemented service APIs, independently of the hook runtime's API 102.
    @Override public int getApiVersion() { check(); return API_101; }
    @Override public String getFrameworkName() { check(); return "NexBox"; }
    @Override public String getFrameworkVersion() { check(); return "102.0.0-embedded"; }
    @Override public long getFrameworkVersionCode() { check(); return 102000; }
    @Override public long getFrameworkProperties() { check(); return 0; }
    @Override public List<String> getScope() { return new ArrayList<>(check().scope); }

    @Override public void requestScope(List<String> packages, IXposedScopeCallback callback) throws RemoteException {
        XposedConfigState.ModuleConfig config = check();
        if (callback == null) return;
        if (packages == null || packages.size() > 1000) {
            callback.onScopeRequestFailed("Invalid scope");
            return;
        }
        // Already approved scope can be acknowledged. A module must not authorize new targets.
        if (config.scope.containsAll(packages)) callback.onScopeRequestApproved(new ArrayList<>(packages));
        else callback.onScopeRequestFailed("Select the apps in NexBox > Xposed modules");
    }

    @Override public void removeScope(List<String> packages) { manager.removeModuleAppScope(owner, packages); }

    @Override public List<HookedProcess> getRunningTargets() {
        check();
        throw new UnsupportedOperationException("NexBox provides module service API 101");
    }

    @Override public void hotReloadModule(long targetId, Bundle data, IHotReloadCallback callback) throws RemoteException {
        check();
        if (callback != null) callback.onHotReloadResult(HOT_RELOAD_UNSUPPORTED, "Restart the target app to apply module changes");
    }

    private UnsupportedOperationException noRemote() {
        check();
        return new UnsupportedOperationException("NexBox has no remote preferences/files service (PROP_CAP_REMOTE is unset)");
    }

    @Override public Bundle requestRemotePreferences(String group) { throw noRemote(); }
    @Override public void updateRemotePreferences(String group, Bundle diff) { throw noRemote(); }
    @Override public void deleteRemotePreferences(String group) { throw noRemote(); }
    @Override public String[] listRemoteFiles() { throw noRemote(); }
    @Override public ParcelFileDescriptor openRemoteFile(String name) { throw noRemote(); }
    @Override public boolean deleteRemoteFile(String name) { throw noRemote(); }
}
