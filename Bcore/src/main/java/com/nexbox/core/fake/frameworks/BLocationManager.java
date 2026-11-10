package com.nexbox.core.fake.frameworks;

import com.nexbox.core.utils.Slog;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.List;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.location.IBLocationManagerService;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.core.system.xposed.XposedModulePolicy;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.entity.location.BCell;
import com.nexbox.core.entity.location.BLocation;
import com.nexbox.core.entity.location.BLocationState;


public class BLocationManager extends CoreServiceManager<IBLocationManagerService> {
    private static final BLocationManager sLocationManager = new BLocationManager();
    private static volatile ScopeTarget sScopeTarget;

    public static final int CLOSE_MODE = 0;
    public static final int GLOBAL_MODE = 1;
    public static final int OWN_MODE = 2;

    public static BLocationManager get() {
        return sLocationManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.LOCATION_MANAGER;
    }

    public static boolean isFakeLocationEnable() {
        AppConfig owner = BActivityThread.getAppConfig();
        if (owner == null || owner.packageName == null) return false;
        try {
            ScopeTarget target = sScopeTarget;
            if (target == null || target.owner != owner) {
                IBPackageManagerService packages = BPackageManager.get().getService();
                if (packages == null) return false;
                ApplicationInfo info = packages.getApplicationInfo(owner.packageName,
                        PackageManager.GET_META_DATA, owner.userId);
                if (info == null) return false;
                // Package replacement stops the guest. Cache only this bound AppConfig, never
                // a recyclable user id/PID, and retry rather than caching service failures.
                target = new ScopeTarget(owner, !XposedModulePolicy.isCandidate(info));
                sScopeTarget = target;
            }
            // Old saved patterns cannot opt a module app into location simulation.
            return target.eligible && get().getPattern(owner.userId, owner.packageName) != CLOSE_MODE;
        } catch (RemoteException | RuntimeException error) {
            Slog.w("BLocationManager", "Could not resolve the current location scope", error);
            return false;
        }
    }

    private static final class ScopeTarget {
        final AppConfig owner;
        final boolean eligible;

        ScopeTarget(AppConfig owner, boolean eligible) {
            this.owner = owner;
            this.eligible = eligible;
        }
    }

    public static void disableFakeLocation(int userId,String pkg){
        get().setPattern(userId,pkg,CLOSE_MODE);
    }

    public void setPattern(int userId, String pkg, int pattern) {
        try {
            getService().setPattern(userId, pkg, pattern);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public int getPattern(int userId, String pkg) {
        try {
            return getService().getPattern(userId, pkg);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return CLOSE_MODE;
    }

    public void setCell(int userId, String pkg, BCell cell) {
        try {
            getService().setCell(userId, pkg, cell);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public void setAllCell(int userId, String pkg, List<BCell> cells) {
        try {
            getService().setAllCell(userId, pkg, cells);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public List<BCell> getNeighboringCell(int userId, String pkg) {
        try {
            return getService().getNeighboringCell(userId, pkg);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return null;
    }

    public List<BCell> getGlobalNeighboringCell() {
        try {
            return getService().getGlobalNeighboringCell();
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return null;
    }

    public void setNeighboringCell(int userId, String pkg, List<BCell> cells) {
        try {
            getService().setNeighboringCell(userId, pkg, cells);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public void setGlobalCell(BCell cell) {
        try {
            getService().setGlobalCell(cell);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public void setGlobalAllCell(List<BCell> cells) {
        try {
            getService().setGlobalAllCell(cells);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public void setGlobalNeighboringCell(List<BCell> cells) {
        try {
            getService().setGlobalNeighboringCell(cells);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public BCell getCell(int userId, String pkg) {
        try {
            return getService().getCell(userId, pkg);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return null;
    }

    public List<BCell> getAllCell(int userId, String pkg) {
        try {
            return getService().getAllCell(userId, pkg);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return new ArrayList<>();
    }

    public void setLocation(int userId, String pkg, BLocation location) {
        try {
            getService().setLocation(userId, pkg, location);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public BLocation getLocation(int userId, String pkg) {
        try {
            return getService().getLocation(userId, pkg);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return null;
    }

    /** Strict host APIs: failures never become an empty snapshot or an unconfirmed success. */
    public List<BLocationState> getLocationStates(int userId, long generation, List<String> packages) throws RemoteException {
        IBLocationManagerService service = getService();
        if (service == null) throw new IllegalStateException("Location service unavailable");
        return service.getLocationStates(userId, generation, packages);
    }

    public List<BLocationState> setLocationStates(int userId, long generation, List<BLocationState> states) throws RemoteException {
        IBLocationManagerService service = getService();
        if (service == null) throw new IllegalStateException("Location service unavailable");
        return service.setLocationStates(userId, generation, states);
    }

    public void setGlobalLocation(BLocation location) {
        try {
            getService().setGlobalLocation(location);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public BLocation getGlobalLocation() {
        try {
            return getService().getGlobalLocation();
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
        return null;
    }

    public void requestLocationUpdates(IBinder listener) {
        try {
            getService().requestLocationUpdates(listener, BActivityThread.getAppPackageName(), BActivityThread.getUserId());
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }

    public void removeUpdates(IBinder listener) {
        try {
            getService().removeUpdates(listener);
        } catch (RemoteException e) {
            Slog.e("BLocationManager", "Container operation failed", e);
        }
    }
}
