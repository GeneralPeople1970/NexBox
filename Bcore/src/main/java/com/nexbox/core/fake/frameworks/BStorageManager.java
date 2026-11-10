package com.nexbox.core.fake.frameworks;

import com.nexbox.core.utils.Slog;

import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.storage.StorageVolume;

import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.os.IBStorageManagerService;


public class BStorageManager extends CoreServiceManager<IBStorageManagerService> {
    private static final BStorageManager sStorageManager = new BStorageManager();

    public static BStorageManager get() {
        return sStorageManager;
    }

    public Bundle importVerifiedMedia(String content, long length, String sha256, String mime, String requestId) throws RemoteException {
        return requireContractService().importVerifiedMedia(content, length, sha256, mime, requestId);
    }

    public Bundle deleteMediaIfVersion(long id, String version, String owner, String sha256, String requestId) throws RemoteException {
        return requireContractService().deleteMediaIfVersion(id, version, owner, sha256, requestId);
    }

    public Bundle getMediaExportSnapshot(long id, String version, String owner, String sha256, String requestId) throws RemoteException {
        return requireContractService().getMediaExportSnapshot(id, version, owner, sha256, requestId);
    }

    private IBStorageManagerService requireContractService() {
        IBStorageManagerService service = getService();
        if (service == null) throw new IllegalStateException("Storage service unavailable");
        return service;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.STORAGE_MANAGER;
    }

    public StorageVolume[] getVolumeList(int uid, String packageName, int flags, int userId) {
        try {
            return requireContractService().getVolumeList(uid, packageName, flags, userId);
        } catch (RemoteException e) {
            Slog.e("BStorageManager", "Container operation failed", e);
            throw new IllegalStateException("Virtual storage volumes could not be read", e);
        }
    }

    public Uri getUriForFile(String file) {
        try {
            return requireContractService().getUriForFile(file);
        } catch (RemoteException e) {
            Slog.e("BStorageManager", "Container operation failed", e);
            throw new IllegalStateException("Share URI could not be created", e);
        }
    }
}
