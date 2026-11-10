package com.nexbox.core.core.system.os;

import android.net.Uri;
import android.os.Binder;
import android.os.Parcel;
import android.os.Process;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.List;

import black.android.os.storage.BRStorageManager;
import black.android.os.storage.BRStorageVolume;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserHandle;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.fake.provider.FileProvider;
import com.nexbox.core.proxy.ProxyManifest;
import com.nexbox.core.core.system.xposed.XposedCallerGuard;
import com.nexbox.core.media.ConditionalMedia;
import com.nexbox.core.media.NexBoxMediaStore;
import com.nexbox.core.utils.Slog;


public class BStorageManagerService extends IBStorageManagerService.Stub implements ISystemService {
    private static final BStorageManagerService sService = new BStorageManagerService();

    public static BStorageManagerService get() {
        return sService;
    }

    public BStorageManagerService() {
    }

    @Override
    public Bundle importVerifiedMedia(String content, long length, String sha256, String mime, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return ConditionalMedia.importVerified(content, length, sha256, mime, requestId);
    }

    @Override
    public Bundle deleteMediaIfVersion(long mediaId, String version, String owner, String sha256, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return ConditionalMedia.deleteIfVersion(mediaId, version, owner, sha256, requestId);
    }

    @Override
    public Bundle getMediaExportSnapshot(long mediaId, String version, String owner, String sha256, String requestId) {
        XposedCallerGuard.enforceHostManager();
        return ConditionalMedia.exportSnapshot(mediaId, version, owner, sha256, requestId);
    }

    @Override
    public StorageVolume[] getVolumeList(int uid, String packageName, int flags, int userId) throws RemoteException {
        ProcessRecord caller;
        long generation;
        Object lifecycle = BPackageManagerService.get().getLifecycleLock();
        synchronized (lifecycle) {
            caller = requireStorageCaller(userId);
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (userId < 0 || user == null || user.createTime <= 0L) {
                throw new SecurityException("Storage target user is unavailable");
            }
            generation = user.createTime;
        }
        StorageVolume[] storageVolumes = null;
        try {
            storageVolumes = BRStorageManager.getWithException()
                    .getVolumeList(BUserHandle.getUserId(Process.myUid()), 0);
        } catch (RuntimeException unavailable) {
            // Public enumeration is a capability fallback only. Every returned volume
            // still has to pass the same path isolation and identity checks below.
        }

        if (storageVolumes == null) {
            try {
                Context context = NexBoxCore.getContext();
                StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (storageManager != null) {
                    List<StorageVolume> volumes = storageManager.getStorageVolumes();
                    if (volumes != null) {
                        storageVolumes = volumes.toArray(new StorageVolume[0]);
                    }
                }
            } catch (RuntimeException failure) {
                throw new IllegalStateException("Storage volumes could not be read", failure);
            }
        }

        if (storageVolumes == null) {
            throw new IllegalStateException("Storage volumes are unavailable");
        }

        synchronized (lifecycle) {
            ProcessRecord currentCaller = requireStorageCaller(userId);
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (caller != currentCaller || user == null || user.createTime != generation) {
                throw new SecurityException("Storage user lifetime changed");
            }
            File directory = BEnvironment.getExternalUserDir(userId);
            StorageVolume[] result = new StorageVolume[storageVolumes.length];
            for (int i = 0; i < storageVolumes.length; i++) {
                if (storageVolumes[i] == null) throw new IllegalStateException("Invalid storage volume");
                Parcel parcel = Parcel.obtain();
                try {
                    storageVolumes[i].writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    StorageVolume volume = StorageVolume.CREATOR.createFromParcel(parcel);
                    BRStorageVolume.getWithException(volume)._set_mPath(directory);
                    BRStorageVolume.getWithException(volume)._set_mInternalPath(directory);
                    if (!directory.equals(BRStorageVolume.getWithException(volume).mPath())
                            || !directory.equals(BRStorageVolume.getWithException(volume).mInternalPath())) {
                        throw new IllegalStateException("Virtual storage paths could not be confirmed");
                    }
                    result[i] = volume;
                } finally {
                    parcel.recycle();
                }
            }
            return result;
        }
    }

    @Override
    public Uri getUriForFile(String file) throws RemoteException {
        if (file == null || file.isEmpty() || !new File(file).isAbsolute()) {
            throw new IllegalArgumentException("An absolute share file is required");
        }
        synchronized (BPackageManagerService.get().getLifecycleLock()) {
            ProcessRecord caller = requireStorageCaller(null);
            try {
                File requested = new File(BEnvironment.relocateLegacyPath(file)).getCanonicalFile();
                if (caller != null && (!requested.isFile() || !canShare(caller, requested))) {
                    Slog.w("BStorageManager", "action=shareFile stage=rejected reason=outside_caller_roots"
                            + " userId=" + caller.userId + " generation=" + caller.userGeneration);
                    throw new SecurityException("Share file is outside the caller's virtual storage");
                }
                return FileProvider.getUriForFile(NexBoxCore.getContext(),
                        ProxyManifest.getProxyFileProvider(), requested);
            } catch (IOException failure) {
                throw new IllegalArgumentException("Share file path could not be verified", failure);
            }
        }
    }

    private static ProcessRecord requireStorageCaller(Integer userId) {
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (caller == null) {
            XposedCallerGuard.enforceHostManagerOrCore();
            return null;
        }
        BUserInfo user = BUserManagerService.get().getUserInfo(caller.userId);
        if (Binder.getCallingUid() != NexBoxCore.getHostUid() || caller.userId < 0
                || user == null || caller.userGeneration != user.createTime
                || (userId != null && caller.userId != userId)
                || caller.bActivityThread == null || !caller.bActivityThread.asBinder().isBinderAlive()) {
            throw new SecurityException("Storage caller does not belong to this user lifetime");
        }
        return caller;
    }

    private static boolean canShare(ProcessRecord caller, File file) throws IOException {
        String packageName = caller.getPackageName();
        int userId = caller.userId;
        if (inside(file, BEnvironment.getDataDir(packageName, userId))
                || inside(file, BEnvironment.getDeDataDir(packageName, userId))) return true;
        File external = BEnvironment.getExternalUserDir(userId);
        if (inside(file, external)) {
            if (inside(file, new File(external, "Android/data"))) {
                return inside(file, BEnvironment.getExternalDataDir(packageName, userId));
            }
            if (inside(file, new File(external, "Android/obb"))) {
                return inside(file, BEnvironment.getExternalObbDir(packageName, userId));
            }
            return true;
        }
        File media = NexBoxMediaStore.getSharedMediaRoot();
        for (String name : new String[]{"DCIM", "Pictures", "Movies", "Download"}) {
            if (inside(file, new File(media, name))) return true;
        }
        return false;
    }

    private static boolean inside(File file, File root) throws IOException {
        // BEnvironment roots are canonical. A symlink replacing an owned directory
        // must not turn another user's directory into the caller's permitted root.
        String rootPath = root.getAbsolutePath();
        return rootPath.equals(root.getCanonicalPath())
                && file.getPath().startsWith(rootPath + File.separator);
    }

    @Override
    public void systemReady() {

    }
}
