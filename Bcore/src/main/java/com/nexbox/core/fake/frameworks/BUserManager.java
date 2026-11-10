package com.nexbox.core.fake.frameworks;

import android.os.DeadObjectException;
import android.os.Bundle;
import android.os.RemoteException;

import java.util.List;

import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.IBUserManagerService;
import com.nexbox.core.utils.Slog;


public class BUserManager extends CoreServiceManager<IBUserManagerService> {
    private static final String TAG = "BUserManager";
    private static final BUserManager sUserManager = new BUserManager();

    public static BUserManager get() {
        return sUserManager;
    }

    public Bundle deleteUserIfInventory(int userId, long createTime, String token,
                                        boolean onlyIfEmpty, String requestId) throws RemoteException {
        return requireContractService().deleteUserIfInventory(userId, createTime, token, onlyIfEmpty, requestId);
    }

    public Bundle resetGmsIfInventory(int userId, long createTime, String token,
                                      List<Bundle> components, String requestId) throws RemoteException {
        return requireContractService().resetGmsIfInventory(userId, createTime, token, components, requestId);
    }

    private IBUserManagerService requireContractService() {
        IBUserManagerService service = getService();
        if (service == null) throw new IllegalStateException("User service unavailable");
        return service;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.USER_MANAGER;
    }

    public BUserInfo createUser(int userId) {
        try {
            return requireContractService().createUser(userId);
        } catch (DeadObjectException e) {
            clearServiceCache();
            Slog.w(TAG, "action=createUser stage=serviceDied userId=" + userId + " result=unconfirmed", e);
            throw new IllegalStateException("User creation outcome is unknown; refresh before retrying", e);
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not create user", e);
        }
    }

    public void deleteUser(int userId) {
        try {
            IBUserManagerService service = requireContractService();
            BUserInfo target = service.getUserInfo(userId);
            if (target != null && !service.deleteUserIfMatches(userId, target.createTime, false)) {
                throw new IllegalStateException("User changed or deletion was not confirmed");
            }
        } catch (DeadObjectException e) {
            clearServiceCache();
            Slog.w(TAG, "action=deleteUser stage=serviceDied userId=" + userId + " result=unconfirmed", e);
            throw new IllegalStateException("User deletion outcome is unknown; refresh before retrying", e);
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not delete user", e);
        }
    }

    public List<BUserInfo> getUsers() {
        try {
            return requireContractService().getUsers();
        } catch (DeadObjectException e) {
            // Only this read is safe to retry. A dead Binder is not evidence that a
            // preceding create/delete failed before committing its mutation.
            clearServiceCache();
            try {
                return requireContractService().getUsers();
            } catch (RemoteException retryException) {
                throw new IllegalStateException("Could not read user registry after reconnecting", retryException);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Could not read user registry", e);
        }
    }
}
