package com.nexbox.core.core.system.user;

import com.nexbox.core.utils.Slog;

import android.os.Parcel;
import android.os.Bundle;
import com.nexbox.core.core.GmsCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.accounts.BAccountManagerService;
import com.nexbox.core.core.system.am.BActivityManagerService;
import com.nexbox.core.fake.device.DeviceSpoofManager;
import android.os.RemoteException;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.core.env.UserDataFiles;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.location.BLocationManagerService;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.xposed.BXposedManagerService;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.utils.CloseUtils;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.MutationReceipt;


public class BUserManagerService extends IBUserManagerService.Stub implements ISystemService {
    private static final String TAG = "BUserManagerService";
    private static BUserManagerService sService = new BUserManagerService();
    private final AtomicLong mGmsResetSequence = new AtomicLong();
    public final HashMap<Integer, BUserInfo> mUsers = new HashMap<>();
    public final Object mUserLock = new Object();
    private final Object mRenameLock = new Object();
    // Persisted after the user list so ids cannot reuse an identity after clock rollback/restart.
    private long mLastCreateTime;
    private volatile IllegalStateException mUserLoadError;
    private volatile boolean mUserRegistryLoaded;

    private void ensureUserRegistryReadable() {
        if (!mUserRegistryLoaded) throw new IllegalStateException("User registry is not loaded yet");
        IllegalStateException failure = mUserLoadError;
        if (failure != null) throw failure;
    }

    public static BUserManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        scanUserL();
    }

    @Override
    public BUserInfo getUserInfo(int userId) {
        ensureUserRegistryReadable();
        synchronized (mUsers) {
            return snapshot(mUsers.get(userId));
        }
    }

    private static BUserInfo snapshot(BUserInfo user) {
        if (user == null) return null;
        BUserInfo copy = new BUserInfo();
        copy.id = user.id;
        copy.status = user.status;
        copy.name = user.name;
        copy.createTime = user.createTime;
        return copy;
    }

    @Override
    public boolean exists(int userId) {
        ensureUserRegistryReadable();
        synchronized (mUsers) {
            return mUsers.get(userId) != null;
        }
    }

    @Override
    public BUserInfo createUser(int userId) throws RemoteException {
        XposedCallerGuard.enforceHostManagerOrCore();
        ensureUserRegistryReadable();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (mUserLock) {
                if (exists(userId)) return getUserInfo(userId);
                return createUserLocked(userId);
            }
        }
    }

    @Override
    public List<BUserInfo> getUsers() {
        ensureUserRegistryReadable();
        synchronized (mUsers) {
            ArrayList<BUserInfo> bUsers = new ArrayList<>();
            for (BUserInfo value : mUsers.values()) {
                if (value.id >= 0) {
                    bUsers.add(snapshot(value));
                }
            }
            return bUsers;
        }
    }

    @Override
    public List<BUserInfo> getCloneGroups() {
        XposedCallerGuard.enforceHostManager();
        ensureUserRegistryReadable();
        BPackageManagerService packages = BPackageManagerService.get();
        synchronized (packages.getLifecycleLock()) {
            synchronized (mUserLock) {
                java.util.Set<Integer> populated = packages.getCloneGroupUserIds();
                ArrayList<BUserInfo> result = new ArrayList<>();
                synchronized (mUsers) {
                    for (BUserInfo user : mUsers.values()) {
                        if (user.id >= 0 && populated.contains(user.id)) result.add(snapshot(user));
                    }
                }
                return result;
            }
        }
    }

    @Override
    public int getNextAvailableUserId() {
        XposedCallerGuard.enforceHostManager();
        ensureUserRegistryReadable();
        BPackageManagerService packages = BPackageManagerService.get();
        synchronized (packages.getLifecycleLock()) {
            synchronized (mUserLock) {
                packages.requireLifecycleMetadataReadable();
                List<Integer> registered;
                synchronized (mUsers) { registered = new ArrayList<>(mUsers.keySet()); }
                int candidate = UserIdAllocator.nextAvailable(registered,
                        id -> packages.hasAnyInstalledPackage(id) || hasUserFiles(id));
                Slog.i(TAG, "action=findAvailableUser userId=" + candidate);
                // This read reserves nothing. Creation still validates absence, files,
                // and the caller's zero generation under this same lifecycle lock.
                return candidate;
            }
        }
    }

    public List<BUserInfo> getAllUsers() {
        ensureUserRegistryReadable();
        synchronized (mUsers) {
            ArrayList<BUserInfo> users = new ArrayList<>();
            for (BUserInfo user : mUsers.values()) users.add(snapshot(user));
            return users;
        }
    }

    @Override
    public void deleteUser(int userId) throws RemoteException {
        XposedCallerGuard.enforceHostManagerOrCore();
        ensureUserRegistryReadable();
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            BUserInfo user = getUserInfo(userId);
            if (user != null) deleteUserIfMatches(userId, user.createTime, false);
        }
    }

    @Override
    public boolean renameUser(int userId, long createTime, String name) {
        return renameUserWithResult(userId, createTime, name).getBoolean("saved");
    }

    @Override
    public Bundle renameUserWithResult(int userId, long createTime, String name) {
        XposedCallerGuard.enforceHostManager();
        ensureUserRegistryReadable();
        Bundle result = new Bundle();
        result.putInt("userId", userId);
        result.putLong("createTime", createTime);
        result.putBoolean("saved", false);
        result.putBoolean("changed", false);
        result.putBoolean("refreshConfirmed", false);
        result.putString("refreshStatus", "not-run");
        result.putInt("updatedTasks", 0);
        result.putInt("unchangedTasks", 0);
        result.putInt("removedTasks", 0);
        result.putInt("remainingTasks", -1);
        result.putInt("unverifiedTasks", -1);
        result.putBoolean("stopRequested", false);
        result.putBoolean("stopConfirmed", false);
        result.putBoolean("requiresRelaunch", false);
        String trimmed = name == null ? "" : name.trim();
        if (userId < 0 || createTime <= 0 || trimmed.isEmpty() || trimmed.length() > 32) {
            result.putString("error", "invalid-request");
            return result;
        }
        synchronized (mRenameLock) {
            BUserInfo renamed;
            synchronized (BPackageManagerService.get().getLifecycleLock()) {
                synchronized (mUserLock) {
                    synchronized (mUsers) {
                        BUserInfo user = mUsers.get(userId);
                        if (user == null || user.createTime != createTime) {
                            result.putString("error", "stale-user");
                            return result;
                        }
                        String old = user.name;
                        boolean changed = !trimmed.equals(old);
                        if (changed) {
                            user.name = trimmed;
                            if (!saveUserInfoLocked()) {
                                user.name = old;
                                result.putString("error", "save-failed");
                                return result;
                            }
                        }
                        renamed = snapshot(user);
                        result.putBoolean("saved", true);
                        result.putBoolean("changed", changed);
                    }
                }
            }
            // Never acquire a stack while holding the lifecycle lock: launches take the opposite order.
            // The rename-only lock prevents an older request overwriting a newer name in system recents.
            BActivityManagerService.get().refreshUserTaskLabels(renamed, result);
            Slog.i(TAG, "action=renameUser userId=" + userId + " generation=" + createTime
                    + " saved=true changed=" + result.getBoolean("changed")
                    + " refresh=" + result.getString("refreshStatus")
                    + " updated=" + result.getInt("updatedTasks")
                    + " removed=" + result.getInt("removedTasks")
                    + " unverified=" + result.getInt("unverifiedTasks"));
            return result;
        }
    }

    @Override
    public boolean deleteUserIfMatches(int userId, long createTime, boolean onlyIfEmpty) throws RemoteException {
        XposedCallerGuard.enforceHostManagerOrCore();
        ensureUserRegistryReadable();
        if (userId < 0 || createTime <= 0) return false;
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            synchronized (mUserLock) {
                BUserInfo previous = mUsers.get(userId);
                if (previous == null || previous.createTime != createTime) return false;
                BPackageManagerService.get().requireLifecycleMetadataReadable();
                if (onlyIfEmpty && BPackageManagerService.get().hasAnyInstalledPackage(userId)) {
                    return false;
                }
                if (onlyIfEmpty && hasUserFiles(userId)) return false;
                BPackageManagerService.get().deleteUser(userId);
                clearEnvironment(userId);
                synchronized (mUsers) {
                    mUsers.remove(userId);
                    if (!saveUserInfoLocked()) {
                        mUsers.put(userId, previous);
                        throw new IllegalStateException("Could not persist user deletion");
                    }
                }
                return true;
            }
        }
    }

    @Override
    public boolean hasGmsEnvironmentIfMatches(int userId, long userCreateTime) {
        XposedCallerGuard.enforceHostManager();
        if (userId < 0 || userCreateTime <= 0) return false;
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            BUserInfo user = getUserInfo(userId);
            return user != null && user.createTime == userCreateTime
                    && (!BPackageManagerService.get().getInstalledGmsPackagesForUser(userId).isEmpty()
                    || BAccountManagerService.get().hasGoogleAccountsForUser(userId, userCreateTime));
        }
    }

    @Override
    public Bundle deleteUserIfInventory(int userId, long createTime, String token,
                                        boolean onlyIfEmpty, String requestId) {
        XposedCallerGuard.enforceHostManager();
        MutationReceipt receipt = new MutationReceipt(requestId);
        receipt.data.putInt("userId", userId);
        receipt.data.putLong("createTime", createTime);
        receipt.data.putBoolean("deleted", false);
        BPackageManagerService packages = BPackageManagerService.get();
        synchronized (packages.getLifecycleLock()) {
            try {
                packages.requireInventoryLocked(userId, createTime, token);
                synchronized (mUserLock) {
                    BUserInfo previous = getUserInfo(userId);
                    if (onlyIfEmpty && (packages.hasAnyInstalledPackage(userId) || hasUserFiles(userId))) {
                        return receipt.failure("user_not_empty");
                    }
                    packages.deleteUserWithReceipt(userId, receipt);
                    receipt.begin("clear_environment");
                    clearEnvironment(userId);
                    receipt.confirmed("environmentCleared");
                    receipt.begin("persist_user_removal");
                    synchronized (mUsers) {
                        mUsers.remove(userId);
                        if (!saveUserInfoLocked()) {
                            mUsers.put(userId, previous);
                            return receipt.failure("user_removal_unconfirmed");
                        }
                    }
                    receipt.confirmed("userRemoved");
                    receipt.data.putBoolean("deleted", true);
                    receipt.data.putBoolean("saved", true);
                    return receipt.success();
                }
            } catch (MutationReceipt.Rejected failure) {
                return receipt.failure(failure.code);
            } catch (Exception failure) {
                return receipt.failure("state_unavailable");
            } finally {
                Slog.i(TAG, "action=conditionalDelete requestId=" + requestId + " userId=" + userId
                        + " generation=" + createTime + " stage=" + receipt.data.getString("stage")
                        + " mutationState=" + receipt.data.getString("mutationState")
                        + " success=" + receipt.data.getBoolean("success"));
            }
        }
    }

    @Override
    public Bundle resetGmsIfInventory(int userId, long createTime, String token,
                                      List<Bundle> expectedComponents, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return BPackageManagerService.get().resetGmsIfInventory(userId, createTime, token, expectedComponents, requestId);
    }

    @Override
    public boolean resetGmsEnvironmentIfMatches(int userId, long userCreateTime) {
        XposedCallerGuard.enforceHostManager();
        if (userId < 0 || userCreateTime <= 0) return false;
        final long resetId = mGmsResetSequence.incrementAndGet();
        BPackageManagerService packages = BPackageManagerService.get();
        BAccountManagerService accounts = BAccountManagerService.get();
        // Process starts, package mutation and user recreation share this lock. Do not
        // hold mUserLock while closing authenticator sessions or stopping components.
        synchronized (packages.getLifecycleLock()) {
            String stage = "preflight";
            boolean resetting = false;
            try {
                BUserInfo user = getUserInfo(userId);
                if (user == null || user.createTime != userCreateTime) {
                    Slog.w(TAG, "gmsReset=" + resetId + " stage=stale_user userId=" + userId
                            + " generation=" + userCreateTime);
                    return false;
                }
                packages.requireLifecycleMetadataReadable();
                List<String> components = packages.getInstalledGmsPackagesForUser(userId);
                stage = "close_accounts";
                resetting = accounts.beginGoogleAccountReset(userId, userCreateTime);
                if (!resetting) {
                    Slog.w(TAG, "gmsReset=" + resetId + " stage=" + stage
                            + " userId=" + userId + " result=failed");
                    return false;
                }
                Slog.i(TAG, "gmsReset=" + resetId + " stage=begin userId=" + userId
                        + " generation=" + userCreateTime + " components=" + components.size());

                // Stop every component before clearing either accounts or private data:
                // another running GMS component must not write the old credentials back.
                stage = "stop_components";
                if (!BProcessManagerService.get().stopPackagesForGmsReset(components, userId, userCreateTime)) {
                    Slog.w(TAG, "gmsReset=" + resetId + " stage=" + stage
                            + " userId=" + userId + " result=unconfirmed");
                    return false;
                }
                stage = "clear_accounts";
                if (!accounts.clearGoogleAccountsForReset(userId, userCreateTime)) {
                    Slog.w(TAG, "gmsReset=" + resetId + " stage=" + stage
                            + " userId=" + userId + " result=failed");
                    return false;
                }
                stage = "clear_components";
                boolean success = true;
                int cleared = 0;
                for (String packageName : components) {
                    if (packages.clearPackageIfMatches(packageName, userId, userCreateTime)) {
                        cleared++;
                    } else {
                        success = false;
                        Slog.w(TAG, "gmsReset=" + resetId + " stage=" + stage
                                + " package=" + packageName + " userId=" + userId + " result=failed");
                    }
                }
                Slog.i(TAG, "gmsReset=" + resetId + " stage=complete userId=" + userId
                        + " generation=" + userCreateTime + " cleared=" + cleared
                        + " components=" + components.size() + " success=" + success);
                return success;
            } catch (Exception failure) {
                // Earlier stages may already have completed; do not claim rollback.
                Slog.w(TAG, "gmsReset=" + resetId + " stage=" + stage + " userId=" + userId
                        + " generation=" + userCreateTime + " result=failed", failure);
                return false;
            } finally {
                if (resetting) accounts.endGoogleAccountReset(userId, userCreateTime);
            }
        }
    }

    /** A failed cleanup keeps the ID reserved; creation retries cleanup before publishing it. */
    private void clearEnvironment(int userId) {
        BPackageManagerService.get().requireLifecycleMetadataReadable();
        if (BEnvironment.getExternalVirtualRoot() == null) {
            throw new IllegalStateException("External user storage is unavailable; existing data was preserved");
        }
        com.nexbox.core.core.system.BProcessManagerService.get().killUser(userId);
        com.nexbox.core.core.system.am.BJobManagerService.get().removeUser(userId);
        com.nexbox.core.core.system.am.VirtualPendingIntents.removeOwner(userId, null);
        com.nexbox.core.media.PrivateDocuments.removeOwner(userId, null);
        com.nexbox.core.core.system.notification.BNotificationManagerService.get().removeUser(userId);
        com.nexbox.core.media.NexBoxMediaStore.removeUser(userId);
        if (!BLocationManagerService.get().removeUser(userId)) {
            throw new IllegalStateException("Could not clear user location settings");
        }
        BXposedManagerService.get().onUserDeleted(userId);
        BAccountManagerService.get().removeUser(userId);
        BActivityManagerService.get().removeUser(userId);
        BUserInfo deviceProfileOwner = getUserInfo(userId);
        DeviceSpoofManager.removeUser(userId, deviceProfileOwner == null ? 0L : deviceProfileOwner.createTime);
        deleteChecked(BEnvironment.getUserDir(userId));
        deleteChecked(BEnvironment.getDeUserDir(userId));
        deleteChecked(BEnvironment.getExternalUserDir(userId));
    }

    private static void deleteChecked(File directory) {
        try { UserDataFiles.deleteTree(directory); }
        catch (IOException error) { throw new IllegalStateException("Could not remove " + directory, error); }
    }

    private BUserInfo createUserLocked(int userId) throws RemoteException {
        BPackageManagerService.get().requireLifecycleMetadataReadable();
        if ((userId >= 0 && BPackageManagerService.get().hasAnyInstalledPackage(userId)) || hasUserFiles(userId)) {
            throw new IllegalStateException("The unused user id still has stored data and needs recovery");
        }
        // Older builds could leave raw package flags for an absent id. Remove those
        // before publishing the replacement, not just its CE/DE/external directories.
        if (userId >= 0) BPackageManagerService.get().deleteUser(userId);
        clearEnvironment(userId);
        BUserInfo bUserInfo = new BUserInfo();
        bUserInfo.id = userId;
        bUserInfo.status = BUserStatus.ENABLE;
        bUserInfo.createTime = nextCreateTimeLocked();
        DeviceSpoofManager.initializeUser(userId, bUserInfo.createTime);
        synchronized (mUsers) {
            mUsers.put(userId, bUserInfo);
            if (!saveUserInfoLocked()) {
                mUsers.remove(userId);
                throw new IllegalStateException("Could not persist user identity");
            }
        }
        return snapshot(bUserInfo);
    }

    /** Empty directory scaffolding may be retried, but unindexed files are not disposable. */
    private static boolean hasUserFiles(int userId) {
        try {
            if (containsFiles(BEnvironment.getUserDir(userId)) || containsFiles(BEnvironment.getDeUserDir(userId))) return true;
            if (BEnvironment.getExternalVirtualRoot() == null) {
                throw new IOException("External user storage is unavailable");
            }
            return containsFiles(BEnvironment.getExternalUserDir(userId));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not confirm that user data is empty", failure);
        }
    }

    private static boolean containsFiles(File directory) throws IOException {
        final boolean[] found = {false};
        try {
            Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    found[0] = true; // Includes symlinks; never follows their targets.
                    return FileVisitResult.TERMINATE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                    if (file.equals(directory.toPath()) && failure instanceof NoSuchFileException) return FileVisitResult.TERMINATE;
                    throw failure;
                }
            });
        } catch (NoSuchFileException absent) {
            // A genuinely absent top-level tree has no residual user data.
            if (!absent.getFile().equals(directory.toPath().toString())) throw absent;
        }
        return found[0];
    }

    private long nextCreateTimeLocked() {
        if (mLastCreateTime == Long.MAX_VALUE) {
            throw new IllegalStateException("User identity timestamp exhausted");
        }
        mLastCreateTime = Math.max(Math.max(System.currentTimeMillis(), 1), mLastCreateTime + 1);
        return mLastCreateTime;
    }

    private boolean saveUserInfoLocked() {
        Parcel parcel = Parcel.obtain();
        AtomicFile atomicFile = new AtomicFile(BEnvironment.getUserInfoConf());
        FileOutputStream fileOutputStream = null;
        try {
            ArrayList<BUserInfo> bUsers = new ArrayList<>(mUsers.values());
            parcel.writeTypedList(bUsers);
            parcel.writeLong(mLastCreateTime);
            try {
                fileOutputStream = atomicFile.startWrite();
                BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
                atomicFile.finishWrite(fileOutputStream);
                return true;
            } catch (IOException e) {
                Slog.e("BUserManagerService", "Could not persist user registry", e);
                atomicFile.failWrite(fileOutputStream);
                return false;
            } finally {
                CloseUtils.close(fileOutputStream);
            }
        } finally {
            parcel.recycle();
        }
    }

    private void scanUserL() {
        synchronized (mUserLock) {
            Parcel parcel = Parcel.obtain();
            InputStream is = null;
            try {
                File userInfoConf = BEnvironment.getUserInfoConf();
                if (!userInfoConf.exists() && !new File(userInfoConf.getPath() + ".bak").exists()) {
                    if (BPackageManagerService.get().hasAnyInstalledPackage(BUserHandle.USER_ALL)
                            || containsFiles(new File(BEnvironment.getVirtualRoot(), "data/user"))
                            || containsFiles(new File(BEnvironment.getVirtualRoot(), "data/user_de"))
                            || (BEnvironment.getExternalVirtualRoot() != null && containsFiles(
                                    new File(BEnvironment.getExternalVirtualRoot(), "storage/emulated")))) {
                        throw new IOException("User registry is missing for existing container data");
                    }
                    return;
                }
                byte[] bytes = MetadataParcel.readBytes(userInfoConf);
                if (bytes.length < Integer.BYTES) throw new IOException("Truncated user registry");
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);

                ArrayList<BUserInfo> loadUsers = parcel.createTypedArrayList(BUserInfo.CREATOR);
                if (loadUsers == null) throw new IOException("Invalid user registry payload");
                synchronized (mUsers) {
                    mLastCreateTime = parcel.dataAvail() >= Long.BYTES ? Math.max(0, parcel.readLong()) : 0;
                    if (parcel.dataAvail() != 0) throw new IOException("Unexpected user registry payload");
                    mUsers.clear();
                    for (BUserInfo loadUser : loadUsers) {
                        if (loadUser == null || mUsers.containsKey(loadUser.id)) throw new IOException("Invalid user registry identity");
                        mLastCreateTime = Math.max(mLastCreateTime, loadUser.createTime);
                        mUsers.put(loadUser.id, loadUser);
                    }
                    HashMap<Integer, Long> migrated = new HashMap<>();
                    for (BUserInfo user : mUsers.values()) {
                        if (user.createTime <= 0) {
                            migrated.put(user.id, user.createTime);
                            user.createTime = nextCreateTimeLocked();
                        }
                    }
                    // Legacy profiles get an identity once, not a fresh timestamp every launch.
                    // If storage rejects the migration, leave them unbound (fail-closed).
                    if (!migrated.isEmpty() && !saveUserInfoLocked()) {
                        for (Integer id : migrated.keySet()) mUsers.get(id).createTime = migrated.get(id);
                        throw new IOException("Could not persist migrated user identities");
                    }
                }
                mUserLoadError = null;
            } catch (Exception e) {
                // Never advertise a failed load as "no users": an absent-id install
                // would otherwise erase the old profile's still-existing package/data state.
                mUserLoadError = new IllegalStateException("User registry could not be read", e);
                Slog.e("BUserManagerService", "Could not load user registry", e);
            } finally {
                mUserRegistryLoaded = true;
                parcel.recycle();
                CloseUtils.close(is);
            }
        }
    }
}
