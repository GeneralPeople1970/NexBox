package com.nexbox.core.core.system.accounts;

import android.os.Parcel;

import androidx.core.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.core.env.MetadataParcel;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.BzFileUtils;
import com.nexbox.core.utils.CloseUtils;
import com.nexbox.core.utils.Slog;

/** Owns accounts.conf and its registry; callers keep the shared lock across mutations and saves. */
final class AccountStore {
    private final Object mAccountsLock = BUserAccounts.REGISTRY_LOCK;
    private final Map<Integer, BUserAccounts> mUserAccountsMap = new HashMap<>();
    private volatile IllegalStateException mAccountsLoadError;

    void requireLoaded() {
        if (mAccountsLoadError != null) throw mAccountsLoadError;
    }

    IllegalStateException failLoad(String message) {
        mAccountsLoadError = new IllegalStateException(message);
        return mAccountsLoadError;
    }

    // Registry access is always under REGISTRY_LOCK, including transaction rollback.
    BUserAccounts peek(int userId) {
        return mUserAccountsMap.get(userId);
    }

    java.util.Collection<BUserAccounts> accounts() {
        return mUserAccountsMap.values();
    }

    BUserAccounts remove(int userId) {
        return mUserAccountsMap.remove(userId);
    }

    void restore(int userId, BUserAccounts accounts) {
        mUserAccountsMap.put(userId, accounts);
    }

    void requireAvailableLocked(BUserAccounts accounts) {
        BUserInfo user = BUserManagerService.get().getUserInfo(accounts.userId);
        requireLoaded();
        if (user == null || user.createTime != accounts.userGeneration
                || mUserAccountsMap.get(accounts.userId) != accounts
                || accounts.googleResetInProgress) {
            throw new IllegalStateException("The account environment is changing");
        }
    }

    void loadAccounts() {
        Parcel parcel = Parcel.obtain();
        InputStream is = null;
        try {
            File userInfoConf = BEnvironment.getAccountsConf();
            if (!userInfoConf.exists() && !new File(userInfoConf.getPath() + ".bak").exists()) {
                return;
            }
            byte[] bytes = MetadataParcel.readBytes(userInfoConf);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);

            HashMap<Integer, BUserAccounts> accountsMap;
            try (MetadataParcel metadata = MetadataParcel.open(userInfoConf, parcel)) {
                accountsMap = metadata.readMap(Integer.class, BUserAccounts.class, BUserAccounts.CREATOR);
                for (Map.Entry<Integer, BUserAccounts> entry : accountsMap.entrySet()) {
                    if (entry.getKey() != entry.getValue().userId) {
                        throw new IOException("Account registry user identity mismatch");
                    }
                }
                metadata.finish(userInfoConf, bytes);
            }
            synchronized (mAccountsLock) {
                mUserAccountsMap.clear();
                mUserAccountsMap.putAll(accountsMap);
            }
            mAccountsLoadError = null;
        } catch (Exception e) {
            mAccountsLoadError = new IllegalStateException("Account registry needs recovery", e);
            Slog.e("BAccountManagerService", "Container operation failed", e);
        } finally {
            parcel.recycle();
            CloseUtils.close(is);
        }
    }

    boolean saveAllAccounts() {
        synchronized (mAccountsLock) {
            if (mAccountsLoadError != null) return false;
            Parcel parcel = Parcel.obtain();
            AtomicFile atomicFile = new AtomicFile(BEnvironment.getAccountsConf());
            FileOutputStream fileOutputStream = null;
            try {
                parcel.writeMap(mUserAccountsMap);
                try {
                    fileOutputStream = atomicFile.startWrite();
                    BzFileUtils.writeParcelToOutput(parcel, fileOutputStream);
                    atomicFile.finishWrite(fileOutputStream);
                    return true;
                } catch (IOException e) {
                    Slog.e("BAccountManagerService", "Container operation failed", e);
                    atomicFile.failWrite(fileOutputStream);
                    return false;
                } finally {
                    CloseUtils.close(fileOutputStream);
                }
            } finally {
                parcel.recycle();
            }
        }
    }

    BUserAccounts getOrCreateUserAccounts(int userId) {
        if (mAccountsLoadError != null) throw mAccountsLoadError;
        synchronized (mAccountsLock) {
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null || user.createTime <= 0) throw new IllegalStateException("Account user no longer exists");
            BUserAccounts bUserAccounts = mUserAccountsMap.get(userId);
            if (bUserAccounts == null) {
                bUserAccounts = new BUserAccounts();
                bUserAccounts.userId = userId;
                mUserAccountsMap.put(userId, bUserAccounts);
            }
            if (bUserAccounts.userGeneration == 0) bUserAccounts.userGeneration = user.createTime;
            if (bUserAccounts.userGeneration != user.createTime) {
                throw new IllegalStateException("Account registry belongs to a different user generation");
            }
            return bUserAccounts;
        }
    }
}
