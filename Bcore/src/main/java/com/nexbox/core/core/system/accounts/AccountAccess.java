package com.nexbox.core.core.system.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.accounts.AccountAuthenticatorRegistry.AuthenticatorInfo;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.compat.AccountManagerCompat;

/** Validates callers and credential ownership, including scoped authenticator callbacks. */
final class AccountAccess {
    interface Callback {
        BUserAccounts accounts();
        boolean isActive();
    }

    private final Object mAccountsLock = BUserAccounts.REGISTRY_LOCK;
    private final ThreadLocal<Callback> mAccountCallback = new ThreadLocal<>();
    private final ThreadLocal<ProcessRecord> mResumedCaller = new ThreadLocal<>();
    private final AccountStore mStore;
    private final AccountAuthenticatorRegistry mAuthenticators;
    private final AccountPackageIdentity mIdentity;
    private final BPackageManagerService mPms;

    AccountAccess(AccountStore store, AccountAuthenticatorRegistry authenticators,
                  AccountPackageIdentity identity, BPackageManagerService pms) {
        mStore = store;
        mAuthenticators = authenticators;
        mIdentity = identity;
        mPms = pms;
    }

    <T> T dispatchCallback(Callback callback, ProcessRecord caller, AccountAction<T> action) throws RemoteException {
        Callback previous = mAccountCallback.get();
        ProcessRecord previousCaller = mResumedCaller.get();
        // Framework cleanup runs as the core while the verified owner stays in this scope.
        long identity = Binder.clearCallingIdentity();
        mAccountCallback.set(callback);
        mResumedCaller.set(caller);
        try {
            return action.run();
        } finally {
            if (previous == null) mAccountCallback.remove(); else mAccountCallback.set(previous);
            if (previousCaller == null) mResumedCaller.remove(); else mResumedCaller.set(previousCaller);
            Binder.restoreCallingIdentity(identity);
        }
    }

    ProcessRecord accountCaller(int userId) {
        ProcessRecord resumed = mResumedCaller.get();
        if (resumed != null) {
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (resumed.userId != userId || user == null || resumed.userGeneration != user.createTime
                    || BProcessManagerService.get().findProcessByPid(resumed.pid) != resumed
                    || resumed.bActivityThread == null || !resumed.bActivityThread.asBinder().isBinderAlive()) {
                throw new SecurityException("Account request caller is no longer current");
            }
            return resumed;
        }
        int pid = Binder.getCallingPid();
        if (pid == android.os.Process.myPid() && Binder.getCallingUid() == android.os.Process.myUid()) return null;
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(pid);
        BUserInfo user = BUserManagerService.get().getUserInfo(userId);
        if (Binder.getCallingUid() != android.os.Process.myUid() || caller == null || caller.info == null
                || caller.userId != userId || user == null || caller.userGeneration != user.createTime
                || caller.bActivityThread == null || !caller.bActivityThread.asBinder().isBinderAlive()) {
            throw new SecurityException("Account caller does not belong to the current profile");
        }
        return caller;
    }

    boolean ownsAccountType(String accountType, String packageName, int userId) {
        AuthenticatorInfo owner = mAuthenticators.getAuthenticators(userId).get(accountType);
        return owner != null && mIdentity.sameAccountSigner(packageName, owner.serviceInfo.packageName, userId);
    }

    void enforceAuthenticatorOwner(String accountType, int userId) {
        ProcessRecord caller = accountCaller(userId);
        if (caller != null && !ownsAccountType(accountType, caller.getPackageName(), userId)) {
            throw new SecurityException("Only this profile's authenticator may access account credentials");
        }
    }

    String queryPackage(String requested, String accountType, int userId) {
        ProcessRecord caller = accountCaller(userId);
        if (caller == null) return requested == null ? NexBoxCore.getHostPkg() : requested;
        if (requested == null || requested.equals(caller.getPackageName())) return caller.getPackageName();
        PackageInfo target = mPms.getPackageInfo(requested, 0, userId);
        if (target != null && target.applicationInfo != null && (target.applicationInfo.uid == caller.buid
                || (accountType != null && ownsAccountType(accountType, caller.getPackageName(), userId)))) return requested;
        throw new SecurityException("Account query package does not belong to its caller");
    }

    BAccount requireStoredAccount(BUserAccounts accounts, Account account) {
        requireAccountStateAvailableLocked(accounts);
        BAccount stored = accounts.getAccount(account);
        if (stored == null) throw new IllegalArgumentException("Account is not registered in this profile");
        AuthenticatorInfo owner = mAuthenticators.getAuthenticators(accounts.userId).get(account.type);
        if (owner == null) throw new IllegalStateException("Account authenticator is not installed");
        String signer = mIdentity.accountSigner(owner.serviceInfo.packageName, accounts.userId);
        String savedPackage = stored.accountUserData.get(AccountCredentialPolicy.OWNER_PACKAGE);
        String savedSigner = stored.accountUserData.get(AccountCredentialPolicy.OWNER_SIGNER);
        if (savedPackage == null && savedSigner == null) {
            // Migrate legacy records against their currently installed authenticator without changing Parcel format.
            stored.accountUserData.put(AccountCredentialPolicy.OWNER_PACKAGE, owner.serviceInfo.packageName);
            stored.accountUserData.put(AccountCredentialPolicy.OWNER_SIGNER, signer);
            if (!mStore.saveAllAccounts()) {
                stored.accountUserData.remove(AccountCredentialPolicy.OWNER_PACKAGE);
                stored.accountUserData.remove(AccountCredentialPolicy.OWNER_SIGNER);
                throw new IllegalStateException("Cannot persist account ownership");
            }
        } else if (!owner.serviceInfo.packageName.equals(savedPackage) || !signer.equals(savedSigner)) {
            throw new SecurityException("Account authenticator identity changed");
        }
        return stored;
    }

    static boolean visibleAccount(int visibility) {
        return visibility == AccountManager.VISIBILITY_VISIBLE || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE;
    }

    boolean requireVisibleAccount(IAccountManagerResponse response, BUserAccounts accounts, Account account)
            throws RemoteException {
        String caller = queryPackage(null, account.type, accounts.userId);
        synchronized (accounts.lock) {
            requireStoredAccount(accounts, account);
            if (visibleAccount(resolveAccountVisibility(account, caller, accounts))) return true;
        }
        response.onError(AccountManagerCompat.ERROR_CODE_USER_RESTRICTED, "Account is not visible to this application");
        return false;
    }

    interface AccountAction<T> { T run() throws RemoteException; }

    <T> T resumeAccountCaller(ProcessRecord caller, AccountAction<T> action) throws RemoteException {
        if (caller == null) throw new SecurityException("A virtual account request is required");
        ProcessRecord previous = mResumedCaller.get();
        Callback previousCallback = mAccountCallback.get();
        mResumedCaller.set(caller);
        mAccountCallback.remove();
        try {
            accountCaller(caller.userId);
            return action.run();
        } finally {
            if (previous == null) mResumedCaller.remove(); else mResumedCaller.set(previous);
            if (previousCallback == null) mAccountCallback.remove(); else mAccountCallback.set(previousCallback);
        }
    }

    boolean hasTokenGrant(BUserAccounts accounts, BAccount account, String packageName, String tokenType) {
        return ownsAccountType(account.account.type, packageName, accounts.userId)
                || mIdentity.accountSigner(packageName, accounts.userId).equals(account.accountUserData.get(
                        AccountCredentialPolicy.grantKey(packageName, tokenType)));
    }

    Bundle accountOptions(Bundle supplied, int userId) {
        ProcessRecord caller = accountCaller(userId);
        Bundle options = supplied == null ? new Bundle() : new Bundle(supplied);
        String packageName = caller == null ? NexBoxCore.getHostPkg() : caller.getPackageName();
        String requested = options.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        if (requested != null && !packageName.equals(requested)) {
            PackageInfo owner = mPms.getPackageInfo(requested, 0, userId);
            if (caller == null || owner == null || owner.applicationInfo == null || owner.applicationInfo.uid != caller.buid) {
                throw new SecurityException("Account request package does not belong to its caller");
            }
            packageName = requested;
        }
        options.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, packageName);
        options.putInt(AccountManager.KEY_CALLER_UID, caller == null ? NexBoxCore.getHostUid() : caller.buid);
        options.putInt(AccountManager.KEY_CALLER_PID, caller == null ? android.os.Process.myPid() : caller.pid);
        return options;
    }

    /** Must be checked inside the shared registry lock before touching account state. */
    void requireAccountStateAvailableLocked(BUserAccounts accounts) {
        mStore.requireAvailableLocked(accounts);
        Callback callback = mAccountCallback.get();
        if (callback != null && (callback.accounts() != accounts || !callback.isActive())) {
            throw new IllegalStateException("The account callback is no longer current");
        }
        if (callback == null) accountCaller(accounts.userId);
    }

    Account[] getAccountsFromCache(BUserAccounts userAccounts, String accountType, String callingPackage, boolean includeManagedNotVisible) {
        synchronized (userAccounts.lock) {
            requireAccountStateAvailableLocked(userAccounts);
            Account[] accounts = accountType == null ? userAccounts.toAccounts()
                    : userAccounts.getAccountsByType(accountType);
            return filterAccounts(userAccounts, accounts, callingPackage, includeManagedNotVisible);
        }
    }

    @NonNull
    private Account[] filterAccounts(BUserAccounts accounts, Account[] unfiltered, String callingPackage, boolean includeManagedNotVisible) {
        Map<Account, Integer> firstPass = new LinkedHashMap<>();
        for (Account account : unfiltered) {
            int visibility = resolveAccountVisibility(account, callingPackage, accounts);
            if ((visibility == AccountManager.VISIBILITY_VISIBLE
                    || visibility == AccountManager.VISIBILITY_USER_MANAGED_VISIBLE)
                    || (includeManagedNotVisible
                    && (visibility
                    == AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE))) {
                firstPass.put(account, visibility);
            }
        }

        return firstPass.keySet().toArray(new Account[]{});
    }

    Integer resolveAccountVisibility(Account account, @NonNull String packageName, BUserAccounts accounts) {
        synchronized (accounts.lock) {
            requireAccountStateAvailableLocked(accounts);
            BAccount stored = accounts.getAccount(account);
            if (stored == null) return AccountManager.VISIBILITY_NOT_VISIBLE;
            PackageInfo app = mPms.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS, accounts.userId);
            if (app == null || app.applicationInfo == null) return AccountManager.VISIBILITY_NOT_VISIBLE;
            String ownerPackage = stored.accountUserData.get(AccountCredentialPolicy.OWNER_PACKAGE);
            String ownerSigner = stored.accountUserData.get(AccountCredentialPolicy.OWNER_SIGNER);
            if (ownerPackage != null && (mPms.getPackageInfo(ownerPackage, 0, accounts.userId) == null
                    || !Objects.equals(ownerSigner, mIdentity.accountSigner(ownerPackage, accounts.userId)))) {
                return AccountManager.VISIBILITY_NOT_VISIBLE;
            }
            if (ownsAccountType(account.type, packageName, accounts.userId)) return AccountManager.VISIBILITY_VISIBLE;
            Integer explicit = stored.visibility.get(packageName);
            if (explicit != null && explicit != AccountManager.VISIBILITY_UNDEFINED) {
                String signer = stored.accountUserData.get(AccountCredentialPolicy.visibilityKey(packageName));
                if (signer == null || signer.equals(mIdentity.accountSigner(packageName, accounts.userId))) return explicit;
                return AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE;
            }
            boolean legacyAccess = false;
            if (app.applicationInfo.targetSdkVersion < 26 && app.requestedPermissions != null
                    && app.requestedPermissionsFlags != null) {
                for (int i = 0; i < app.requestedPermissions.length && i < app.requestedPermissionsFlags.length; i++) {
                    if ("android.permission.GET_ACCOUNTS".equals(app.requestedPermissions[i])
                            && (app.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) legacyAccess = true;
                }
            }
            String key = legacyAccess ? AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE
                    : AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE;
            return stored.visibility.getOrDefault(key, legacyAccess ? AccountManager.VISIBILITY_USER_MANAGED_VISIBLE
                    : AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);
        }
    }

    private int getAccountVisibilityFromCache(Account account, String packageName,
                                              BUserAccounts accounts) {
        synchronized (accounts.lock) {
            requireAccountStateAvailableLocked(accounts);
            Map<String, Integer> accountVisibility =
                    getPackagesAndVisibilityForAccountLocked(account, accounts);
            Integer visibility = accountVisibility.get(packageName);
            return visibility != null ? visibility : AccountManager.VISIBILITY_UNDEFINED;
        }
    }

    @NonNull
    private Map<String, Integer> getPackagesAndVisibilityForAccountLocked(Account account,
                                                                  BUserAccounts accounts) {
        return accounts.getVisibility(account);
    }

    private String readUserDataInternal(BUserAccounts accounts, Account account, String key) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            requireAccountStateAvailableLocked(accounts);
            Map<String, String> accountUserData = accounts.getAccountUserData(account);
            return accountUserData.get(key);
        }
    }

    String readPasswordInternal(BUserAccounts accounts, Account account) {
        if (accounts == null)
            return null;
        synchronized (accounts.lock) {
            requireAccountStateAvailableLocked(accounts);
            BAccount bAccount = accounts.getAccount(account);
            if (bAccount == null)
                return null;
            return bAccount.password;
        }
    }

    BUserAccounts getUserAccounts(int userId) {
        synchronized (mAccountsLock) {
            BUserAccounts accounts = mStore.getOrCreateUserAccounts(userId);
            requireAccountStateAvailableLocked(accounts);
            return accounts;
        }
    }

    boolean isAccountPresentForCaller(String accountName, String accountType, int userId) {
        if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) return false;
        BUserAccounts userAccounts = getUserAccounts(userId);
        if (userAccounts != null) {
            BAccount account = userAccounts.getAccount(new Account(accountName, accountType));
            return account != null;
        }
        return false;
    }

    boolean updateLastAuthenticatedTime(BUserAccounts userAccounts, Account account) {
        synchronized (userAccounts.lock) {
            BAccount stored = requireStoredAccount(userAccounts, account);
            long previous = stored.updateLastAuthenticatedTime;
            userAccounts.updateLastAuthenticatedTime(account);
            if (mStore.saveAllAccounts()) return true;
            stored.updateLastAuthenticatedTime = previous;
            return false;
        }
    }

    String getCallingPackageName() {
        int callingPid = Binder.getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            throw new IllegalArgumentException("ProcessRecord is null, PID: " + callingPid);
        return processByPid.getPackageName();
    }
}
