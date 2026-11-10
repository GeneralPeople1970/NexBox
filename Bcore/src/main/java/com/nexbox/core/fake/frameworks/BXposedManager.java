package com.nexbox.core.fake.frameworks;

import android.os.RemoteException;
import android.os.IBinder;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.xposed.IBXposedManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.entity.pm.InstalledModule;
import com.nexbox.core.entity.pm.ModuleLoadState;
import com.nexbox.core.entity.pm.XposedRuntimeStatus;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.ConfigurationAppStopper;
import com.nexbox.core.utils.ConfigurationStopException;

public final class BXposedManager extends CoreServiceManager<IBXposedManagerService> {
    private static final BXposedManager INSTANCE = new BXposedManager();
    public static BXposedManager get() { return INSTANCE; }
    @Override protected String getServiceName() { return ServiceManager.XPOSED_MANAGER; }

    private IBXposedManagerService requireService() throws RemoteException {
        IBXposedManagerService service = getService();
        if (service == null) throw new RemoteException("Xposed configuration service is unavailable");
        return service;
    }

    public List<InstalledModule> getInstalledModules(int userId) {
        try { return requireService().getInstalledModules(userId); }
        catch (RemoteException | RuntimeException e) { failure(e); return new ArrayList<>(); }
    }

    public boolean setModuleConfig(int userId, String modulePackage, boolean enabled, List<String> scope) {
        try {
            BUserInfo user = BUserManager.get().getService().getUserInfo(userId);
            if (user == null || user.createTime <= 0L) return false;
            return setModuleConfig(userId, modulePackage, enabled, scope, user.createTime);
        } catch (ConfigurationStopException saved) { throw saved; }
        catch (RemoteException | RuntimeException e) { failure(e); return false; }
    }

    public boolean setModuleConfig(int userId, String modulePackage, boolean enabled, List<String> scope,
                                   long expectedCreateTime) {
        try {
            if (expectedCreateTime <= 0L) return false;
            Bundle result = requireService().setModuleConfig(userId, modulePackage, enabled, scope, expectedCreateTime);
            if (result == null || !result.getBoolean("saved")) return false;
            List<String> remaining = result.getStringArrayList("failedTargets");
            if (remaining != null && !remaining.isEmpty()) {
                List<ConfigurationAppStopper.Target> failed = new ArrayList<>();
                for (String target : remaining) failed.add(new ConfigurationAppStopper.Target(
                        target, userId, result.getLong("createTime")));
                throw new ConfigurationStopException(failed);
            }
            return true;
        } catch (ConfigurationStopException saved) { throw saved; }
        catch (RemoteException | RuntimeException e) { failure(e); return false; }
    }

    public List<String> getPendingLegacySelections() {
        try { return requireService().getPendingLegacySelections(); }
        catch (RemoteException | RuntimeException e) { failure(e); return new ArrayList<>(); }
    }

    public XposedRuntimeStatus getRuntimeStatus() {
        try { return requireService().getRuntimeStatus(); }
        catch (RemoteException | RuntimeException e) {
            failure(e);
            XposedRuntimeStatus result = new XposedRuntimeStatus();
            result.available = false;
            result.state = "service_unavailable";
            result.message = "Xposed configuration service unavailable: " + e.getMessage();
            result.backend = "LSPlant";
            result.apiVersion = com.nexbox.core.xposed.ModuleMetadata.MODERN_API;
            return result;
        }
    }

    public List<ModuleLoadState> getModuleStates(int userId) {
        try { return requireService().getModuleStates(userId); }
        catch (RemoteException | RuntimeException e) { failure(e); return new ArrayList<>(); }
    }

    public List<InstalledModule> getModulesForCurrentProcess() {
        try { return requireService().getModulesForCurrentProcess(); }
        catch (RemoteException | RuntimeException e) { failure(e); return new ArrayList<>(); }
    }

    public void reportModuleLoad(String modulePackage, boolean loaded, String detail) {
        try { requireService().reportModuleLoad(modulePackage, loaded, detail); }
        catch (RemoteException | RuntimeException e) { failure(e); }
    }

    public IBinder getModuleServiceForCurrentProcess() {
        try { return requireService().getModuleServiceForCurrentProcess(); }
        catch (RemoteException | RuntimeException e) { failure(e); return null; }
    }

    private static void failure(Exception e) { Slog.w("BXposedManager", e.toString()); }
}
