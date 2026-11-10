package com.nexbox.core.core.system.location;

import android.os.IBinder;
import android.os.Binder;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.SparseArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.location.BRILocationListenerStub;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageMonitor;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.entity.location.BCell;
import com.nexbox.core.entity.location.BLocation;
import com.nexbox.core.entity.location.BLocationConfig;
import com.nexbox.core.entity.location.BLocationState;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.core.system.xposed.XposedModulePolicy;
import com.nexbox.core.fake.frameworks.BLocationManager;
import com.nexbox.core.utils.compat.LocationListenerCompat;
import com.nexbox.core.utils.CloseUtils;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.ActiveSubscriptions;
import com.nexbox.core.utils.IdleScheduler;


public class BLocationManagerService extends IBLocationManagerService.Stub implements ISystemService {
    public static final String TAG = "BLocationManagerService";

    private static final BLocationManagerService sService = new BLocationManagerService();
    private final SparseArray<HashMap<String, BLocationConfig>> mLocationConfigs = new SparseArray<>();
    private BLocationConfig mGlobalConfig = new BLocationConfig();
    private final ScheduledThreadPoolExecutor mLocationScheduler = IdleScheduler.create("nexbox-location");
    private final ActiveSubscriptions<IBinder, Subscription> mLocationListeners =
            new ActiveSubscriptions<>(() -> {
                var task = mLocationScheduler.scheduleWithFixedDelay(this::dispatchLocations, 0, 1, TimeUnit.SECONDS);
                return () -> task.cancel(false);
            });
    private boolean mConfigLoaded;
    private RuntimeException mConfigLoadError;
    private boolean mConfigDirty;
    private final LocationTargetCache mTargetCache = new LocationTargetCache();
    private final AtomicBoolean mMonitorRegistered = new AtomicBoolean();

    public static BLocationManagerService get() {
        return sService;
    }

    /** Reads do not create records that could survive deletion/recreation of a numeric user id. */
    private BLocationConfig readConfig(int userId, String pkg) {
        if (mConfigLoadError != null) throw mConfigLoadError;
        Map<String, BLocationConfig> configs = mLocationConfigs.get(userId);
        BLocationConfig config = configs == null ? null : configs.get(pkg);
        return config == null ? new BLocationConfig() : config;
    }

    private ProcessRecord requireLocationReader(int userId, String packageName) {
        if (NexBoxCore.get().isServerProcess() && Binder.getCallingPid() == android.os.Process.myPid()
                && Binder.getCallingUid() == NexBoxCore.getHostUid()) return null;
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (caller == null) {
            XposedCallerGuard.enforceHostManager();
            return null;
        }
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller.userId != userId
                || user == null || caller.userGeneration != user.createTime
                || !caller.getPackageName().equals(packageName) || caller.bActivityThread == null
                || !caller.bActivityThread.asBinder().isBinderAlive()) {
            Slog.w(TAG, "action=readLocation stage=rejected reason=caller_or_generation userId=" + userId);
            throw new SecurityException("Location caller does not own the current target");
        }
        return caller;
    }

    public int getPattern(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                return readConfig(userId, pkg).pattern;
            }
        }
    }

    @Override
    public void setPattern(int userId, String pkg, int pattern) {
        updateConfig(userId, pkg, config -> config.pattern = pattern);
    }

    @Override
    public void setCell(int userId, String pkg, BCell cell) {
        updateConfig(userId, pkg, config -> config.cell = cell);
    }

    @Override
    public void setAllCell(int userId, String pkg, List<BCell> cells) {
        updateConfig(userId, pkg, config -> config.allCell = cells);
    }

    @Override
    public void setNeighboringCell(int userId, String pkg, List<BCell> cells) {
        updateConfig(userId, pkg, config -> config.neighboringCellInfo = cells);
    }

    @Override
    public List<BCell> getNeighboringCell(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                BLocationConfig config = readConfig(userId, pkg);
                switch (config.pattern) {
                    case BLocationManager.OWN_MODE:
                        return config.neighboringCellInfo;
                    case BLocationManager.GLOBAL_MODE:
                        return mGlobalConfig.neighboringCellInfo;
                    default:
                        return null;
                }
            }
        }
    }

    @Override
    public void setGlobalCell(BCell cell) {
        updateGlobalConfig(config -> config.cell = cell);
    }

    @Override
    public void setGlobalAllCell(List<BCell> cells) {
        updateGlobalConfig(config -> config.allCell = cells);
    }

    @Override
    public void setGlobalNeighboringCell(List<BCell> cells) {
        updateGlobalConfig(config -> config.neighboringCellInfo = cells);
    }

    @Override
    public List<BCell> getGlobalNeighboringCell() {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mLocationConfigs) {
            if (mConfigLoadError != null) throw mConfigLoadError;
            return mGlobalConfig.neighboringCellInfo;
        }
    }

    @Override
    public BCell getCell(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                BLocationConfig config = readConfig(userId, pkg);
                switch (config.pattern) {
                    case BLocationManager.OWN_MODE:
                        return config.cell;
                    case BLocationManager.GLOBAL_MODE:
                        return mGlobalConfig.cell;
                    case BLocationManager.CLOSE_MODE:
                    default:
                        return null;
                }
            }
        }
    }

    @Override
    public List<BCell> getAllCell(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                BLocationConfig config = readConfig(userId, pkg);
                switch (config.pattern) {
                    case BLocationManager.OWN_MODE:
                        return config.allCell;
                    case BLocationManager.GLOBAL_MODE:
                        return mGlobalConfig.allCell;
                    case BLocationManager.CLOSE_MODE:
                    default:
                        return null;
                }
            }
        }
    }

    @Override
    public void setLocation(int userId, String pkg, BLocation location) {
        updateConfig(userId, pkg, config -> config.location = location);
        Slog.iLazy(TAG, () -> "action=setLocation userId=" + userId + " package=" + pkg
                + " hasLocation=" + (location != null));
    }

    @Override
    public BLocation getLocation(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                BLocationConfig config = readConfig(userId, pkg);
                switch (config.pattern) {
                    case BLocationManager.OWN_MODE:
                        return config.location;
                    case BLocationManager.GLOBAL_MODE:
                        return mGlobalConfig.location;
                    case BLocationManager.CLOSE_MODE:
                    default:
                        return null;
                }
            }
        }
    }

    @Override
    public BLocation getOwnLocation(int userId, String pkg) {
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, pkg);
            synchronized (mLocationConfigs) {
                return readConfig(userId, pkg).location;
            }
        }
    }

    @Override
    public void setLocationAndPattern(int userId, long userCreateTime, String pkg,
                                      BLocation location, int pattern) {
        // Rollback/legacy host callers share the same permission, target and lifecycle
        // checks as the UI/CLI batch; they cannot restore a removed or out-of-scope app.
        setLocationStates(userId, userCreateTime,
                Collections.singletonList(new BLocationState(pkg, pattern, location, false)));
    }

    @Override
    public List<BLocationState> getLocationStates(int userId, long userCreateTime, List<String> packages) {
        XposedCallerGuard.enforceHostManager();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (BUserManagerService.get().mUserLock) {
                requireUser(userId, userCreateTime);
                Map<String, Boolean> targets = locationTargets(userId, userCreateTime);
                loadConfig();
                synchronized (mLocationConfigs) {
                    if (mConfigLoadError != null) throw mConfigLoadError;
                    Map<String, BLocationConfig> configs = mLocationConfigs.get(userId);
                    return LocationBatch.snapshot(configs == null ? Collections.emptyMap() : configs, targets, packages);
                }
            }
        }
    }

    @Override
    public List<BLocationState> setLocationStates(int userId, long userCreateTime, List<BLocationState> states) {
        XposedCallerGuard.enforceHostManager();
        if (states == null) throw new IllegalArgumentException("Location states are required");
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (BUserManagerService.get().mUserLock) {
                requireUser(userId, userCreateTime);
                Map<String, Boolean> targets = locationTargets(userId, userCreateTime);
                loadConfig();
                synchronized (mLocationConfigs) {
                    if (mConfigLoadError != null) throw mConfigLoadError;
                    return applyBatch(userId, targets, states);
                }
            }
        }
    }

    private void requireUser(int userId, long generation) {
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (user == null || generation <= 0 || user.createTime != generation) {
            throw new IllegalStateException("Location user identity changed");
        }
    }

    private void updateConfig(int userId, String packageName,
                              java.util.function.Consumer<BLocationConfig> update) {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (BUserManagerService.get().mUserLock) {
                BUserInfo user = BUserManagerService.get().getUserInfo(userId);
                if (userId < 0 || user == null || user.createTime <= 0L || packageName == null) {
                    throw new IllegalArgumentException("A current location target is required");
                }
                Map<String, Boolean> targets = locationTargets(userId, user.createTime);
                if (!targets.containsKey(packageName)) throw new IllegalStateException("Location app is no longer installed");
                loadConfig();
                synchronized (mLocationConfigs) {
                    BLocationConfig next = copyConfig(readConfig(userId, packageName));
                    update.accept(next);
                    validateConfig(next);
                    if (next.pattern != BLocationManager.CLOSE_MODE && !Boolean.TRUE.equals(targets.get(packageName))) {
                        throw new IllegalStateException("Location target is not an ordinary app");
                    }
                    HashMap<String, BLocationConfig> configs = mLocationConfigs.get(userId);
                    boolean created = configs == null;
                    if (created) configs = new HashMap<>();
                    BLocationConfig previous = configs.put(packageName, next);
                    mLocationConfigs.put(userId, configs);
                    if (!save()) {
                        if (previous == null) configs.remove(packageName); else configs.put(packageName, previous);
                        if (created) mLocationConfigs.remove(userId);
                        throw new IllegalStateException("Could not persist location configuration");
                    }
                }
            }
        }
    }

    private void updateGlobalConfig(java.util.function.Consumer<BLocationConfig> update) {
        XposedCallerGuard.enforceHostManagerOrCore();
        loadConfig();
        synchronized (mLocationConfigs) {
            if (mConfigLoadError != null) throw mConfigLoadError;
            BLocationConfig previous = mGlobalConfig;
            BLocationConfig next = copyConfig(previous);
            update.accept(next);
            validateConfig(next);
            mGlobalConfig = next;
            if (!save()) {
                mGlobalConfig = previous;
                throw new IllegalStateException("Could not persist global location configuration");
            }
        }
    }

    private static BLocationConfig copyConfig(BLocationConfig source) {
        BLocationConfig copy = new BLocationConfig();
        copy.pattern = source.pattern;
        copy.cell = source.cell;
        copy.allCell = source.allCell;
        copy.neighboringCellInfo = source.neighboringCellInfo;
        copy.location = source.location;
        return copy;
    }

    private static void validateConfig(BLocationConfig config) {
        if (config.pattern < BLocationManager.CLOSE_MODE || config.pattern > BLocationManager.OWN_MODE) {
            throw new IllegalArgumentException("Invalid location mode");
        }
        BLocation point = config.location;
        if (point != null && (!Double.isFinite(point.getLatitude()) || !Double.isFinite(point.getLongitude())
                || Math.abs(point.getLatitude()) > 90 || Math.abs(point.getLongitude()) > 180)) {
            throw new IllegalArgumentException("Invalid location coordinates");
        }
    }

    /** Called under the lifecycle/user locks; a normal rocker tick hits metadata in memory. */
    private Map<String, Boolean> locationTargets(int userId, long generation) {
        return mTargetCache.get(userId, generation, NexBoxCore.get().isHideRoot(), () -> {
            List<ApplicationInfo> apps = BPackageManagerService.get().getInstalledApplications(PackageManager.GET_META_DATA, userId);
            if (apps == null) throw new IllegalStateException("Location package list unavailable");
            Map<String, Boolean> targets = new LinkedHashMap<>();
            for (ApplicationInfo app : apps) {
                if (app == null || app.packageName == null || app.packageName.isEmpty()) {
                    throw new IllegalStateException("Incomplete location package metadata");
                }
                targets.put(app.packageName, !app.packageName.equals(NexBoxCore.getHostPkg())
                        && !app.packageName.equals("com.nexbox.gallery") && !XposedModulePolicy.isCandidate(app));
            }
            return targets;
        });
    }

    /** mLocationConfigs must be held. Reading a snapshot never creates per-package state. */
    private List<BLocationState> applyBatch(int userId, Map<String, Boolean> targets, List<BLocationState> states) {
        HashMap<String, BLocationConfig> previous = mLocationConfigs.get(userId);
        HashMap<String, BLocationConfig> configs = previous == null ? new HashMap<>() : previous;
        try {
            return LocationBatch.apply(configs, targets, states, mConfigDirty, () -> {
                if (previous != null || !configs.isEmpty()) mLocationConfigs.put(userId, configs);
                return save();
            });
        } catch (RuntimeException | Error failure) {
            if (previous == null) mLocationConfigs.remove(userId);
            throw failure;
        }
    }

    /** Called by the user lifecycle, including creation after an older build reused an id. */
    public boolean removeUser(int userId) {
        mTargetCache.removeUser(userId);
        loadConfig();
        synchronized (mLocationConfigs) {
            if (mConfigLoadError != null) return false;
            HashMap<String, BLocationConfig> previous = mLocationConfigs.get(userId);
            if (previous != null) {
                mLocationConfigs.remove(userId);
                if (!save()) {
                    mLocationConfigs.put(userId, previous);
                    return false;
                }
            }
            for (Subscription subscription : mLocationListeners.removeMatching(value -> value.userId == userId)) {
                subscription.unlink();
            }
            return true;
        }
    }

    @Override
    public void setGlobalLocation(BLocation location) {
        updateGlobalConfig(config -> config.location = location);
        Slog.iLazy(TAG, () -> "action=setGlobalLocation hasLocation=" + (location != null));
    }

    @Override
    public BLocation getGlobalLocation() {
        XposedCallerGuard.enforceHostManagerOrCore();
        synchronized (mLocationConfigs) {
            if (mConfigLoadError != null) throw mConfigLoadError;
            return mGlobalConfig.location;
        }
    }

    @Override
    public void requestLocationUpdates(IBinder listener, String packageName, int userId) throws RemoteException {
        if (listener == null || packageName == null || !listener.isBinderAlive()) return;
        synchronized (BUserManagerService.get().mUserLock) {
            requireLocationReader(userId, packageName);
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null) return;
            Subscription subscription = new Subscription(listener, packageName, userId, user.createTime);
            listener.linkToDeath(subscription, 0);
            if (!mLocationListeners.add(listener, subscription)) {
                subscription.unlink();
            } else if (subscription.dead || !listener.isBinderAlive()) {
                subscription.binderDied();
            }
        }
    }

    @Override
    public void removeUpdates(IBinder listener) throws RemoteException {
        if (listener == null) return;
        Subscription subscription = mLocationListeners.remove(listener);
        if (subscription != null) subscription.unlink();
    }

    private final class Subscription implements IBinder.DeathRecipient {
        final IBinder binder;
        final IInterface callback;
        final String packageName;
        final int userId;
        final long userCreateTime;
        final AtomicBoolean deliveryPending = new AtomicBoolean();
        volatile boolean dead;
        BLocation lastLocation;
        long lastSentAt;

        Subscription(IBinder binder, String packageName, int userId, long userCreateTime) {
            this.binder = binder;
            this.callback = BRILocationListenerStub.get().asInterface(binder);
            this.packageName = packageName;
            this.userId = userId;
            this.userCreateTime = userCreateTime;
        }

        @Override public void binderDied() {
            dead = true;
            mLocationListeners.remove(binder, this);
            unlink();
        }

        void unlink() {
            dead = true;
            try { binder.unlinkToDeath(this, 0); } catch (RuntimeException ignored) { }
        }
    }

    private void dispatchLocations() {
        Map<String, BLocation> locations = new HashMap<>();
        long now = System.nanoTime() / 1_000_000L;
        for (Map.Entry<IBinder, Subscription> entry : mLocationListeners.snapshot()) {
            Subscription subscription = entry.getValue();
            try {
                if (subscription.dead || !subscription.binder.isBinderAlive()) {
                    subscription.binderDied();
                    continue;
                }
                BLocation location;
                synchronized (BUserManagerService.get().mUserLock) {
                    BUserInfo user = BUserManagerService.get().getUserInfo(subscription.userId);
                    if (user == null || user.createTime != subscription.userCreateTime) {
                        subscription.binderDied();
                        continue;
                    }
                    String key = subscription.userId + ":" + subscription.packageName;
                    if (!locations.containsKey(key)) {
                        locations.put(key, getLocation(subscription.userId, subscription.packageName));
                    }
                    location = locations.get(key);
                }
                if (location == null) {
                    subscription.lastLocation = null;
                    continue;
                }
                if (location == subscription.lastLocation && now - subscription.lastSentAt < 3000) continue;
                if (!subscription.deliveryPending.compareAndSet(false, true)) continue;
                subscription.lastLocation = location;
                subscription.lastSentAt = now;
                boolean posted = NexBoxCore.get().getHandler().post(() -> {
                    try {
                        // Cancellation/re-registration and a new position invalidate already queued callbacks.
                        if (isDeliveryCurrent(subscription, location)) {
                            LocationListenerCompat.dispatchLocationChanged(subscription.callback, location.convert2SystemLocation());
                        }
                    } finally {
                        subscription.deliveryPending.set(false);
                    }
                });
                if (!posted) subscription.deliveryPending.set(false);
            } catch (RuntimeException error) {
                Slog.w(TAG, "Location dispatch failed", error);
            }
        }
    }

    private boolean isDeliveryCurrent(Subscription subscription, BLocation location) {
        synchronized (BUserManagerService.get().mUserLock) {
            BUserInfo user = BUserManagerService.get().getUserInfo(subscription.userId);
            return !subscription.dead && user != null && user.createTime == subscription.userCreateTime
                    && mLocationListeners.isCurrent(subscription.binder, subscription)
                    && getLocation(subscription.userId, subscription.packageName) == location;
        }
    }

    public boolean save() {
        synchronized (mLocationConfigs) {
            if (mConfigLoadError != null) return false;
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getFakeLocationConf());
            FileOutputStream fileOutputStream = null;
            try {
                mGlobalConfig.writeToParcel(parcel, 0);
                parcel.writeInt(mLocationConfigs.size());
                for (int i = 0; i < mLocationConfigs.size(); i++) {
                    int tmpUserId = mLocationConfigs.keyAt(i);
                    HashMap<String, BLocationConfig> configArrayMap = mLocationConfigs.valueAt(i);
                    parcel.writeInt(tmpUserId);
                    parcel.writeMap(configArrayMap);
                }
                parcel.setDataPosition(0);
                fileOutputStream = atomicFile.startWrite();
                BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
                mConfigDirty = false;
                return true;
            } catch (Exception e) {
                // A legacy setter can leave memory changed after a failed save. A later
                // no-op management batch must still flush that state before confirming it.
                mConfigDirty = true;
                Slog.e("BLocationManagerService", "Container operation failed", e);
                atomicFile.failWrite(fileOutputStream);
                return false;
            } finally {
                parcel.recycle();
                CloseUtils.close(fileOutputStream);
            }
        }
    }

    public void loadConfig() {
        synchronized (mLocationConfigs) {
            if (mConfigLoaded) return;
            mConfigLoaded = true;
            Parcel parcel = Parcel.obtain();
            InputStream is = null;
            try {
                File fakeLocationConf = BEnvironment.getFakeLocationConf();
                if (!fakeLocationConf.exists() && !new File(fakeLocationConf.getPath() + ".bak").exists()) return;
                byte[] bytes = MetadataParcel.readBytes(fakeLocationConf);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                BLocationConfig global;
                SparseArray<HashMap<String, BLocationConfig>> loaded = new SparseArray<>();
                try (MetadataParcel metadata = MetadataParcel.open(fakeLocationConf, parcel)) {
                    global = new BLocationConfig(parcel);
                    int size = parcel.readInt();
                    if (size < 0 || size > 100000 || size > parcel.dataAvail() / 8) {
                        throw new IOException("Invalid location profile count");
                    }
                    for (int i = 0; i < size; i++) {
                        int userId = parcel.readInt();
                        if (loaded.indexOfKey(userId) >= 0) throw new IOException("Duplicate location user identity");
                        loaded.put(userId, metadata.readMap(String.class, BLocationConfig.class, BLocationConfig.CREATOR));
                    }
                    metadata.finish(fakeLocationConf, bytes);
                }
                mGlobalConfig = global;
                mLocationConfigs.clear();
                for (int i = 0; i < loaded.size(); i++) mLocationConfigs.put(loaded.keyAt(i), loaded.valueAt(i));
            } catch (Exception e) {
                Slog.e("BLocationManagerService", "Container operation failed", e);
                // Do not silently erase another profile's data when storage is unreadable.
                mConfigLoadError = new IllegalStateException("Could not load location configuration", e);
                Slog.d(TAG, "bad config; preserving original file");
            } finally {
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }

    @Override
    public void systemReady() {
        loadConfig();
        if (mMonitorRegistered.compareAndSet(false, true)) {
            BPackageManagerService.get().addPackageMonitor(new PackageMonitor() {
                @Override public void onPackageInstalled(String packageName, int userId) {
                    mTargetCache.invalidatePackage(packageName, userId);
                }
                @Override public void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
                    mTargetCache.invalidatePackage(packageName, userId);
                }
            });
        }
    }
}
