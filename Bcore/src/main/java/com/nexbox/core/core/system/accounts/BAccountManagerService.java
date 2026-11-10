package com.nexbox.core.core.system.accounts;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.accounts.IAccountManagerResponse;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ISystemService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.accounts.AccountAuthenticatorRegistry.AuthenticatorInfo;
import com.nexbox.core.core.system.accounts.AccountSessionDiagnostics.Operation;
import com.nexbox.core.core.system.accounts.AccountSessionManager.Session;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.pm.PackageMonitor;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.BzArrayUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.AccountManagerCompat;
import com.nexbox.core.utils.compat.BundleCompat;

@SuppressLint("InlinedApi")
public class BAccountManagerService extends IBAccountManagerService.Stub implements ISystemService , PackageMonitor {
    private static final String TAG = "AccountManagerService";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private final Object mAccountsLock = BUserAccounts.REGISTRY_LOCK;

    private static BAccountManagerService sService = new BAccountManagerService();

    private static final Account[] EMPTY_ACCOUNT_ARRAY = new Account[]{};

    private final BPackageManagerService mPms;

    private final LinkedList<TokenCache> mTokenCaches = new LinkedList<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final AccountStore mStore = new AccountStore();
    private final AccountPackageIdentity mIdentity;
    private final AccountAuthenticatorRegistry mAuthenticators;
    private final AccountAccess mAccess;
    private final AccountSessionManager mSessionManager;
    private final AccountInteractions mInteractions;

    public static BAccountManagerService get() {
        return sService;
    }

    public BAccountManagerService() {
        Context context = NexBoxCore.getContext();
        mPms = BPackageManagerService.get();
        mIdentity = new AccountPackageIdentity(mPms);
        mAuthenticators = new AccountAuthenticatorRegistry(context, mPms, mIdentity);
        mAccess = new AccountAccess(mStore, mAuthenticators, mIdentity, mPms);
        mSessionManager = new AccountSessionManager(context, mPms, mStore, mAccess,
                mAuthenticators, mIdentity, mHandler);
        mInteractions = new AccountInteractions(mHandler, mAccess, mIdentity, this::grantAccountAccess);
    }

    @Override
    public void systemReady() {
        mStore.loadAccounts();
        loadAuthenticatorCache(null);
        mStore.requireLoaded();
        migrateLegacyAccountBindings();
        mPms.addPackageMonitor(this);
    }

    @Override
    public void onPackageUninstalled(String packageName, boolean isRemove, int userId) {
        packageAccountStateChanged(packageName, userId, true);
    }

    @Override
    public void onPackageInstalled(String packageName, int userId) {
        packageAccountStateChanged(packageName, userId, false);
    }

    private void packageAccountStateChanged(String packageName, int userId, boolean removed) {
        Set<String> ownerTypes = mAuthenticators.cachedOwnerTypes(packageName, userId);
        loadAuthenticatorCache(packageName);
        String signer = removed ? null : mIdentity.accountSigner(packageName, userId);
        synchronized (mAccountsLock) {
            BUserAccounts accounts = mStore.peek(userId);
            if (accounts != null) {
                List<BAccount> original = new ArrayList<>(accounts.accounts);
                Map<BAccount, Map<String, String>> metadata = new HashMap<>();
                Map<BAccount, Map<String, Integer>> visibility = new HashMap<>();
                for (BAccount account : original) {
                    metadata.put(account, new HashMap<>(account.accountUserData));
                    visibility.put(account, new HashMap<>(account.visibility));
                    String owner = account.accountUserData.get(AccountCredentialPolicy.OWNER_PACKAGE);
                    if (packageName.equals(owner) || (owner == null && ownerTypes.contains(account.account.type))) {
                        ownerTypes.add(account.account.type);
                        if (removed) { accounts.accounts.remove(account); continue; }
                    }
                    String grantedSigner = account.accountUserData.get(AccountCredentialPolicy.visibilityKey(packageName));
                    if (removed || !Objects.equals(signer, grantedSigner)) {
                        account.visibility.remove(packageName);
                        AccountCredentialPolicy.removePackageGrants(account.accountUserData, packageName);
                    }
                }
                boolean changed = !accounts.accounts.equals(original);
                for (BAccount account : accounts.accounts) {
                    changed |= !account.accountUserData.equals(metadata.get(account)) || !account.visibility.equals(visibility.get(account));
                }
                if (changed && !mStore.saveAllAccounts()) {
                    accounts.accounts.clear(); accounts.accounts.addAll(original);
                    for (BAccount account : original) {
                        account.accountUserData.clear(); account.accountUserData.putAll(metadata.get(account));
                        account.visibility.clear(); account.visibility.putAll(visibility.get(account));
                    }
                    throw new IllegalStateException("Could not update account state after package change");
                }
                if (changed) notifyAccountsChanged(accounts);
            }
        }
        synchronized (mTokenCaches) {
            mTokenCaches.removeIf(token -> token.userId == userId
                    && (packageName.equals(token.packageName) || ownerTypes.contains(token.account.type)));
        }
        mSessionManager.packageChanged(packageName, userId);
        mInteractions.packageChanged(packageName, userId);
    }

    private void migrateLegacyAccountBindings() {
        synchronized (mAccountsLock) {
            Map<BAccount, Map<String, String>> previous = new HashMap<>();
            for (BUserAccounts accounts : mStore.accounts()) {
                BUserInfo user = BUserManagerService.get().getUserInfo(accounts.userId);
                if (user == null) continue;
                accounts.userGeneration = user.createTime;
                Map<String, AuthenticatorInfo> authenticators = mAuthenticators.getAuthenticators(accounts.userId);
                for (BAccount account : accounts.accounts) {
                    previous.put(account, new HashMap<>(account.accountUserData));
                    if (!account.accountUserData.containsKey(AccountCredentialPolicy.OWNER_PACKAGE)) {
                        AuthenticatorInfo owner = authenticators.get(account.account.type);
                        account.accountUserData.put(AccountCredentialPolicy.OWNER_PACKAGE,
                                owner == null ? "" : owner.serviceInfo.packageName);
                        account.accountUserData.put(AccountCredentialPolicy.OWNER_SIGNER,
                                owner == null ? "" : mIdentity.accountSigner(owner.serviceInfo.packageName, accounts.userId));
                    }
                    for (String packageName : account.visibility.keySet()) {
                        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                                || AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) continue;
                        String key = AccountCredentialPolicy.visibilityKey(packageName);
                        if (account.accountUserData.containsKey(key)) continue;
                        PackageInfo app = mPms.getPackageInfo(packageName, 0, accounts.userId);
                        account.accountUserData.put(key, app == null ? "" : mIdentity.accountSigner(packageName, accounts.userId));
                    }
                }
            }
            boolean changed = false;
            for (Map.Entry<BAccount, Map<String, String>> entry : previous.entrySet()) {
                changed |= !entry.getKey().accountUserData.equals(entry.getValue());
            }
            if (changed && !mStore.saveAllAccounts()) {
                previous.forEach((account, values) -> { account.accountUserData.clear(); account.accountUserData.putAll(values); });
                throw mStore.failLoad("Account ownership migration could not be saved");
            }
        }
    }

    public void removeUser(int userId) {
        mInteractions.cancelAccountInteractions(userId, false);
        mSessionManager.removePendingUser(userId);
        mAuthenticators.removeUser(userId);
        mSessionManager.closeUserSessions(userId);
        synchronized (mTokenCaches) { mTokenCaches.removeIf(token -> token.userId == userId); }
        synchronized (mAccountsLock) {
            BUserAccounts previous = mStore.remove(userId);
            if (!mStore.saveAllAccounts()) {
                if (previous != null) mStore.restore(userId, previous);
                throw new IllegalStateException("Could not clear user accounts");
            }
        }
    }

    @Override
    public boolean isAccountServiceProcess(int pid, int uid) {
        return Binder.getCallingUid() == android.os.Process.myUid()
                && pid == android.os.Process.myPid() && uid == android.os.Process.myUid();
    }

    public boolean beginGoogleAccountReset(int userId, long generation) {
        requirePackageLifecycleLock();
        boolean started = false;
        try {
            List<Session> sessions = new ArrayList<>();
            synchronized (mAccountsLock) {
                BUserInfo user = BUserManagerService.get().getUserInfo(userId);
                if (userId < 0 || generation <= 0 || user == null
                        || user.createTime != generation) return false;
                BUserAccounts accounts = mStore.getOrCreateUserAccounts(userId);
                if (accounts.googleResetInProgress) return false;
                if (accounts.googleResetEpoch == Long.MAX_VALUE) {
                    throw new IllegalStateException("Account reset generation exhausted");
                }
                accounts.googleResetEpoch++;
                accounts.googleResetInProgress = true;
                started = true;
                sessions = mSessionManager.googleSessions(accounts);
            }
            for (Session session : sessions) {
                session.cancelForReset();
            }
            mSessionManager.removePendingGoogleAccounts(userId);
            mInteractions.cancelAccountInteractions(userId, true);
            Slog.i(TAG, "Google account reset started: user=" + userId
                    + ", generation=" + generation + ", sessions=" + sessions.size());
            return true;
        } catch (RuntimeException | Error failure) {
            if (started) endGoogleAccountReset(userId, generation);
            throw failure;
        }
    }

    /** Atomically refuse traces and suspend Google writes before a controlled installation. */
    public boolean beginTraceFreeGoogleInstall(int userId, long generation) {
        requirePackageLifecycleLock();
        synchronized (mAccountsLock) {
            mStore.requireLoaded();
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null || user.createTime != generation) return false;
            BUserAccounts current = mStore.peek(userId);
            if (current != null) {
                if (current.userGeneration != 0 && current.userGeneration != generation) {
                    throw new IllegalStateException("Account generation is unreadable");
                }
                if (current.googleResetInProgress) return false;
                for (BAccount account : current.accounts) {
                    if (GOOGLE_ACCOUNT_TYPE.equals(account.account.type)) return false;
                }
                if (current.googleResetEpoch == Long.MAX_VALUE) return false;
            }
            BUserAccounts accounts = mStore.getOrCreateUserAccounts(userId);
            accounts.googleResetEpoch++;
            accounts.googleResetInProgress = true;
            return true;
        }
    }

    public boolean clearGoogleAccountsForReset(int userId, long generation) {
        requirePackageLifecycleLock();
        BUserAccounts accounts;
        int removed;
        synchronized (mAccountsLock) {
            mStore.requireLoaded();
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            accounts = mStore.peek(userId);
            if (user == null || generation <= 0 || user.createTime != generation
                    || accounts == null || accounts.userGeneration != generation
                    || !accounts.googleResetInProgress) return false;
            List<BAccount> previous = new ArrayList<>(accounts.accounts);
            accounts.accounts.removeIf(entry -> GOOGLE_ACCOUNT_TYPE.equals(entry.account.type));
            removed = previous.size() - accounts.accounts.size();
            if (!mStore.saveAllAccounts()) {
                accounts.accounts.clear();
                accounts.accounts.addAll(previous);
                return false;
            }
            synchronized (mTokenCaches) {
                mTokenCaches.removeIf(token -> token.userId == userId
                        && GOOGLE_ACCOUNT_TYPE.equals(token.account.type));
            }
        }
        if (removed > 0) notifyAccountsChanged(accounts);
        Slog.i(TAG, "Google account reset persisted: user=" + userId
                + ", generation=" + generation + ", accounts=" + removed);
        return true;
    }

    public void endGoogleAccountReset(int userId, long generation) {
        synchronized (mAccountsLock) {
            BUserAccounts accounts = mStore.peek(userId);
            if (accounts != null && accounts.userGeneration == generation) {
                accounts.googleResetInProgress = false;
            }
        }
    }

    public boolean hasGoogleAccountsForUser(int userId, long generation) {
        synchronized (mAccountsLock) {
            mStore.requireLoaded();
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (userId < 0 || generation <= 0 || user == null
                    || user.createTime != generation) return false;
            BUserAccounts accounts = mStore.peek(userId);
            if (accounts == null) return false;
            if (accounts.userGeneration != 0 && accounts.userGeneration != generation) {
                throw new IllegalStateException("Account registry belongs to a different user generation");
            }
            for (BAccount account : accounts.accounts) {
                if (GOOGLE_ACCOUNT_TYPE.equals(account.account.type)) return true;
            }
            return false;
        }
    }

    private void requirePackageLifecycleLock() {
        if (!Thread.holdsLock(mPms.getLifecycleLock())) {
            throw new IllegalStateException("Account reset requires the package lifecycle lock");
        }
    }

    private void notifyAccountsChanged(BUserAccounts accounts) {
        BUserInfo user = BUserManagerService.get().getUserInfo(accounts.userId);
        synchronized (mAccountsLock) {
            if (user == null || mStore.peek(accounts.userId) != accounts) return;
        }
        mHandler.post(() -> {
            try {
                // This service emits the legacy guest broadcast protocol. Account update listeners
                // consume changes; they cannot replace this producer or its user-generation scope.
                @SuppressWarnings("deprecation")
                Intent event = new Intent(AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION);
                com.nexbox.core.core.system.am.BActivityManagerService.get().sendSystemBroadcast(
                        event, accounts.userId, user.createTime);
            } catch (RuntimeException failure) {
                Slog.w(TAG, "Could not deliver virtual account update event", failure);
            }
        });
    }

    private void clearCachedTokens(int userId, Account account) {
        synchronized (mTokenCaches) {
            mTokenCaches.removeIf(token -> token.userId == userId && (account == null || token.account.equals(account)));
        }
    }

    private void grantAccountAccess(BUserAccounts accounts, Account account, String packageName, String tokenType,
                                    boolean allow, boolean exposeAccount) {
        grantAccountAccess(accounts, account, Collections.singletonList(packageName), tokenType, allow, exposeAccount);
    }

    private void grantAccountAccess(BUserAccounts accounts, Account account, List<String> packageNames, String tokenType,
                                    boolean allow, boolean exposeAccount) {
        Map<String, String> signers = new LinkedHashMap<>();
        for (String name : packageNames) signers.put(name, mIdentity.accountSigner(name, accounts.userId));
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            Map<String, String> metadata = new HashMap<>(stored.accountUserData);
            Map<String, Integer> visibility = new HashMap<>(stored.visibility);
            for (Map.Entry<String, String> entry : signers.entrySet()) {
                String name = entry.getKey();
                if (exposeAccount && allow) {
                    stored.visibility.put(name, AccountManager.VISIBILITY_USER_MANAGED_VISIBLE);
                    stored.accountUserData.put(AccountCredentialPolicy.visibilityKey(name), entry.getValue());
                }
                if (tokenType != null) {
                    String key = AccountCredentialPolicy.grantKey(name, tokenType);
                    if (allow) stored.accountUserData.put(key, entry.getValue()); else stored.accountUserData.remove(key);
                }
            }
            if (!mStore.saveAllAccounts()) {
                stored.accountUserData.clear(); stored.accountUserData.putAll(metadata);
                stored.visibility.clear(); stored.visibility.putAll(visibility);
                throw new IllegalStateException("Could not save account permission");
            }
            if (!allow) clearCachedTokens(accounts.userId, account);
        }
        notifyAccountsChanged(accounts);
    }

    @Override
    public Bundle getAccountInteraction(IBinder capability, IBinder uiLifetime) throws RemoteException {
        return mInteractions.getAccountInteraction(capability, uiLifetime);
    }

    @Override
    public Bundle completeAccountInteraction(IBinder capability, int choice) throws RemoteException {
        return mInteractions.completeAccountInteraction(capability, choice);
    }

    @Override
    public void cancelAccountInteraction(IBinder capability) {
        mInteractions.cancelAccountInteraction(capability);
    }

    @Override
    public String getPassword(Account account, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) { return mAccess.requireStoredAccount(accounts, account).password; }
    }

    @Override
    public String getUserData(Account account, String key, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        if (AccountCredentialPolicy.isInternal(key)) return null;
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) { return mAccess.requireStoredAccount(accounts, account).accountUserData.get(key); }
    }

    @Override
    public AuthenticatorDescription[] getAuthenticatorTypes(int userId) throws RemoteException {
        getUserAccounts(userId);
        List<AuthenticatorDescription> authenticatorDescriptions = new ArrayList<>();
        for (AuthenticatorInfo authenticator : mAuthenticators.getAuthenticators(userId).values()) {
            authenticatorDescriptions.add(authenticator.desc);
        }
        return authenticatorDescriptions.toArray(new AuthenticatorDescription[]{});
    }

    @Override
    public Account[] getAccountsForPackage(String packageName, int uid, int userId) {
        String target = mAccess.queryPackage(packageName, null, userId);
        PackageInfo info = mPms.getPackageInfo(target, 0, userId);
        if (info == null || info.applicationInfo == null || info.applicationInfo.uid != uid) {
            throw new SecurityException("Account query UID does not match the package");
        }
        return getAccountsFromCache(getUserAccounts(userId), null, target, false);
    }

    @Override
    public Account[] getAccountsByTypeForPackage(String type, String packageName, int userId) {
        return getAccountsFromCache(getUserAccounts(userId), type, mAccess.queryPackage(packageName, type, userId), false);
    }

    @Override
    public Account[] getAccountsAsUser(String accountType, int userId) {
        return getAccountsFromCache(getUserAccounts(userId), accountType, mAccess.queryPackage(null, accountType, userId), false);
    }

    @Override
    public void getAccountByTypeAndFeatures(IAccountManagerResponse response, String accountType, String[] features, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = mAccess.queryPackage(null, accountType, userId);
        ProcessRecord requestor = mAccess.accountCaller(userId);

        BUserAccounts userAccounts = getUserAccounts(userId);
        if (BzArrayUtils.isEmpty(features)) {
            Account[] accountsWithManagedNotVisible = getAccountsFromCache(
                    userAccounts, accountType, opPackageName,
                    true );
            handleGetAccountsResult(
                    response, accountsWithManagedNotVisible, opPackageName, userId, requestor);
            return;
        }

        IAccountManagerResponse retrieveAccountsResponse =
                new IAccountManagerResponse.Stub() {
                    @Override
                    public void onResult(Bundle value) throws RemoteException {
                        Parcelable[] parcelables = BundleCompat.getParcelableArray(
                                value, AccountManager.KEY_ACCOUNTS);
                        Account[] accounts = new Account[parcelables.length];
                        for (int i = 0; i < parcelables.length; i++) {
                            accounts[i] = (Account) parcelables[i];
                        }
                        handleGetAccountsResult(
                                response, accounts, opPackageName, userId, requestor);
                    }

                    @Override
                    public void onError(int errorCode, String errorMessage)
                            throws RemoteException {
                        response.onError(errorCode, errorMessage);
                    }
                };
        mSessionManager.new GetAccountsByTypeAndFeatureSession(
                Operation.GET_ACCOUNT_BY_FEATURES,
                userAccounts,
                retrieveAccountsResponse,
                accountType,
                features,
                userId,
                opPackageName,
                true ).bind();
    }

    @Override
    public void getAccountsByFeatures(IAccountManagerResponse response, String type, String[] features, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (type == null) throw new IllegalArgumentException("accountType is null");

        String opPackageName = mAccess.getCallingPackageName();

        BUserAccounts userAccounts = getUserAccounts(userId);
        if (features == null || features.length == 0) {
            Account[] accounts = getAccountsFromCache(userAccounts, type,
                    opPackageName, false);
            Bundle result = new Bundle();
            result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
            onResult(response, result);
            return;
        }
        mSessionManager.new GetAccountsByTypeAndFeatureSession(
                Operation.GET_ACCOUNTS_BY_FEATURES,
                userAccounts,
                response,
                type,
                features,
                userId,
                opPackageName,
                false ).bind();
    }

    @Override
    public boolean addAccountExplicitly(Account account, String password, Bundle extras, int userId) throws RemoteException {
        return addAccountExplicitlyWithVisibility(account, password, extras, null, userId);
    }

    @Override
    public void removeAccountAsUser(IAccountManagerResponse response, Account account, boolean expectActivityLaunch, int userId) throws RemoteException {
        Preconditions.checkArgument(account != null, "account cannot be null");
        Preconditions.checkArgument(response != null, "response cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        mSessionManager.new RemoveAccountSession(accounts, response, account, expectActivityLaunch, this::removeAccountInternal).bind();
    }

    @Override
    public boolean removeAccountExplicitly(Account account, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        return removeAccountInternal(getUserAccounts(userId), account);
    }

    @Override
    public void copyAccountToUser(IAccountManagerResponse response, Account account, int userFrom, int userTo) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        // Virtual groups are independent environments, not Android managed profiles.
        response.onError(AccountManagerCompat.ERROR_CODE_USER_RESTRICTED, "Account copying between profiles is not allowed");
    }

    @Override
    public void invalidateAuthToken(String accountType, String authToken, int userId) {
        Objects.requireNonNull(accountType, "account type cannot be null");
        Objects.requireNonNull(authToken, "auth token cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            mAccess.requireAccountStateAvailableLocked(accounts);
            Map<BAccount, Map<String, String>> previous = new HashMap<>();
            for (BAccount account : accounts.accounts) {
                if (!accountType.equals(account.account.type) || !account.authTokens.containsValue(authToken)) continue;
                previous.put(account, new HashMap<>(account.authTokens));
                account.authTokens.values().removeIf(authToken::equals);
            }
            if (!previous.isEmpty() && !mStore.saveAllAccounts()) {
                previous.forEach((account, values) -> { account.authTokens.clear(); account.authTokens.putAll(values); });
                throw new IllegalStateException("Could not invalidate the account token");
            }
            synchronized (mTokenCaches) {
                mTokenCaches.removeIf(token -> token.userId == userId && accountType.equals(token.account.type)
                        && authToken.equals(token.authToken));
            }
        }
    }

    @Override
    public String peekAuthToken(Account account, String authTokenType, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "token type cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) { return mAccess.requireStoredAccount(accounts, account).authTokens.get(authTokenType); }
    }

    @Override
    public void setAuthToken(Account account, String authTokenType, String authToken, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "token type cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        if (!saveAuthTokenToDatabase(getUserAccounts(userId), account, authTokenType, authToken)) {
            throw new IllegalStateException("Could not save the account token");
        }
    }

    @Override
    public void setPassword(Account account, String password, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            if (!Objects.equals(stored.password, password) || !stored.authTokens.isEmpty()) {
                String previousPassword = stored.password;
                Map<String, String> previousTokens = new HashMap<>(stored.authTokens);
                stored.password = password;
                stored.authTokens.clear();
                if (!mStore.saveAllAccounts()) {
                    stored.password = previousPassword;
                    stored.authTokens.putAll(previousTokens);
                    throw new IllegalStateException("Could not save account credentials");
                }
            }
            // Even an identical password is an explicit cache-invalidation operation.
            clearCachedTokens(userId, account);
        }
        notifyAccountsChanged(accounts);
    }

    @Override
    public void clearPassword(Account account, int userId) throws RemoteException {
        setPassword(account, null, userId);
    }

    @Override
    public void setUserData(Account account, String key, String value, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(key, "key cannot be null");
        if (AccountCredentialPolicy.isInternal(key)) throw new SecurityException("Reserved account metadata");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            Map<String, String> data = mAccess.requireStoredAccount(accounts, account).accountUserData;
            boolean existed = data.containsKey(key);
            String previous = data.get(key);
            if (!AccountWritePolicy.changesValue(existed, previous, value)) return;
            if (value == null) data.remove(key); else data.put(key, value);
            if (!mStore.saveAllAccounts()) {
                if (existed) data.put(key, previous); else data.remove(key);
                throw new IllegalStateException("Could not save account data");
            }
        }
    }

    @Override
    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) throws RemoteException {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(authTokenType, "token type cannot be null");
        ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (caller == null) throw new SecurityException("A virtual authenticator is required");
        mAccess.enforceAuthenticatorOwner(account.type, caller.userId);
        String[] packages = mPms.getPackagesForUid(uid, caller.userId);
        if (packages == null || packages.length == 0) throw new IllegalArgumentException("Unknown token recipient UID");
        BUserAccounts accounts = getUserAccounts(caller.userId);
        for (String packageName : packages) {
            PackageInfo target = mPms.getPackageInfo(packageName, 0, caller.userId);
            if (target == null || target.applicationInfo == null || target.applicationInfo.uid != uid) {
                throw new SecurityException("Token recipient belongs to another profile");
            }
        }
        grantAccountAccess(accounts, account, Arrays.asList(packages), authTokenType, value, value);
    }

    @Override
    public void getAuthToken(IAccountManagerResponse response, Account account, String authTokenType, boolean notifyOnAuthFailure, boolean expectActivityLaunch, Bundle suppliedOptions, int userId) throws RemoteException {
        Preconditions.checkArgument(response != null, "response cannot be null");
        try {
            if (account == null) {
                Slog.w(TAG, "getAuthToken called with null account");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "account is null");
                return;
            }
            if (authTokenType == null) {
                Slog.w(TAG, "getAuthToken called with null authTokenType");
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "authTokenType is null");
                return;
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to report error back to the client." + e);
            return;
        }
        final BUserAccounts accounts = getUserAccounts(userId);
        final Bundle loginOptions = mAccess.accountOptions(suppliedOptions, userId);
        AuthenticatorInfo authenticatorInfo = mAuthenticators.getAuthenticators(userId).get(account.type);

        final boolean customTokens =
                authenticatorInfo != null && authenticatorInfo.desc.customTokens;

        final String callerPkg = loginOptions.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        final ProcessRecord requestor = mAccess.accountCaller(userId);
        final String requestSigner = mIdentity.accountSigner(callerPkg, userId);
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            int visibility = mAccess.resolveAccountVisibility(account, callerPkg, accounts);
            if (visibility == AccountManager.VISIBILITY_NOT_VISIBLE) {
                response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Account is not available to this application");
                return;
            }
            if (!AccountAccess.visibleAccount(visibility) || (!customTokens && !mAccess.hasTokenGrant(accounts, stored, callerPkg, authTokenType))) {
                mInteractions.new Request(accounts, requestor, callerPkg, new Account[]{account}, authTokenType, response,
                        () -> { getAuthToken(response, account, authTokenType, notifyOnAuthFailure,
                                expectActivityLaunch, loginOptions, userId); return null; }).deliver();
                return;
            }
        }

        if (notifyOnAuthFailure) {
            loginOptions.putBoolean(AccountManagerCompat.KEY_NOTIFY_ON_FAILURE, true);
        }

        if (!customTokens) {
            String authToken = readAuthTokenInternal(accounts, account, authTokenType);
            if (authToken != null) {
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTHTOKEN, authToken);
                result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                onResult(response, result);
                return;
            }
        }

        if (customTokens) {

            String token = readCachedTokenInternal(
                    accounts,
                    account,
                    authTokenType,
                    callerPkg);
            if (token != null) {
                if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "getAuthToken: cache hit ofr custom token authenticator.");
                }
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_AUTHTOKEN, token);
                result.putString(AccountManager.KEY_ACCOUNT_NAME, account.name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, account.type);
                onResult(response, result);
                return;
            }
        }

        mSessionManager.new Session(
                Operation.GET_AUTH_TOKEN,
                accounts,
                response,
                account.type,
                expectActivityLaunch,
                false ,
                account.name,
                false ) {
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", getAuthToken"
                        + ", user=" + userId
                        + ", authTokenType " + authTokenType
                        + ", notifyOnAuthFailure " + notifyOnAuthFailure;
            }

            @Override
            public void run() throws RemoteException {

                mAuthenticator.getAuthToken(this, account, authTokenType, loginOptions);
            }

            @Override
            public void onResult(Bundle result) {
                if (!isActive()) return;
                if (result == null || result.containsKey(AccountManager.KEY_INTENT)
                        || result.getInt(AccountManager.KEY_ERROR_CODE, 0) > 0) {
                    super.onResult(result);
                    return;
                }
                String token = result.getString(AccountManager.KEY_AUTHTOKEN);
                if (token != null) {
                    if (token.isEmpty() || !account.name.equals(result.getString(AccountManager.KEY_ACCOUNT_NAME))
                            || !account.type.equals(result.getString(AccountManager.KEY_ACCOUNT_TYPE))) {
                        onError(AccountManager.ERROR_CODE_INVALID_RESPONSE, "Authenticator returned a different account");
                        return;
                    }
                    synchronized (mAccounts.lock) {
                        BAccount stored = mAccess.requireStoredAccount(mAccounts, account);
                        if (!requestSigner.equals(mIdentity.accountSigner(callerPkg, userId))
                                || !AccountAccess.visibleAccount(mAccess.resolveAccountVisibility(account, callerPkg, mAccounts))
                                || (!customTokens && !mAccess.hasTokenGrant(mAccounts, stored, callerPkg, authTokenType))) {
                            onError(AccountManager.ERROR_CODE_CANCELED, "Account permission changed");
                            return;
                        }
                        if (!customTokens && !saveAuthTokenToDatabase(mAccounts, account, authTokenType, token)) {
                            onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Could not save the account token");
                            return;
                        }
                        long expiry = result.getLong(AbstractAccountAuthenticator.KEY_CUSTOM_TOKEN_EXPIRY, 0L);
                        if (customTokens && expiry > System.currentTimeMillis()) {
                            saveCachedToken(mAccounts, account, callerPkg, authTokenType, token, expiry);
                        }
                    }
                }
                super.onResult(result);
            }
        }.bind();
    }

    @Override
    public void addAccount(IAccountManagerResponse response, String accountType, String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch, Bundle optionsIn, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");

        final Bundle options = mAccess.accountOptions(optionsIn, userId);

        BUserAccounts accounts = getUserAccounts(userId);
        mSessionManager.new Session(Operation.ADD_ACCOUNT, accounts, response, accountType, expectActivityLaunch,
                true , null ,
                false , true ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.addAccount(this, mAccountType, authTokenType, requiredFeatures,
                        options);
            }

            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", addAccount"
                        + ", accountType " + accountType
                        + ", requiredFeatures " + Arrays.toString(requiredFeatures);
            }
        }.bind();
    }

    @Override
    public void addAccountAsUser(IAccountManagerResponse response, String accountType, String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch, Bundle options, int userId) throws RemoteException {
        addAccount(response, accountType, authTokenType, requiredFeatures, expectActivityLaunch, options, userId);
    }

    @Override
    public void startAddAccountSession(IAccountManagerResponse response, String accountType, String authTokenType,
                                       String[] features, boolean expectActivity, Bundle options, int userId) {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(accountType, "account type cannot be null");
        Bundle request = mAccess.accountOptions(options, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        String signer = mIdentity.accountSigner(request.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME), userId);
        mSessionManager.new StartAccountSession(Operation.START_ADD_ACCOUNT, accounts, response, accountType, null, expectActivity, request, signer) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.startAddAccountSession(this, accountType, authTokenType, features, request);
            }
        }.bind();
    }

    @Override
    public void startUpdateCredentialsSession(IAccountManagerResponse response, Account account, String authTokenType,
                                              boolean expectActivity, Bundle options, int userId) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(account, "account cannot be null");
        Bundle request = mAccess.accountOptions(options, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        if (!mAccess.requireVisibleAccount(response, accounts, account)) return;
        String signer = mIdentity.accountSigner(request.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME), userId);
        mSessionManager.new StartAccountSession(Operation.START_UPDATE_CREDENTIALS, accounts, response, account.type, account.name, expectActivity, request, signer) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.startUpdateCredentialsSession(this, account, authTokenType, request);
            }
        }.bind();
    }

    @Override
    public void finishSessionAsUser(IAccountManagerResponse response, Bundle sessionBundle,
                                    boolean expectActivity, Bundle appInfo, int userId) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        Bundle identity = mAccess.accountOptions(appInfo, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        String callerPackage = identity.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
        AccountSessionStore.Entry pending;
        try {
            synchronized (accounts.lock) {
                mAccess.requireAccountStateAvailableLocked(accounts);
                pending = mSessionManager.takePendingSession(sessionBundle, accounts, callerPackage, mIdentity.accountSigner(callerPackage, userId));
                if (pending.targetAccount != null && accounts.getAccount(pending.targetAccount.account) != pending.targetAccount) {
                    throw new IllegalArgumentException("Account changed after the session was created");
                }
            }
            AuthenticatorInfo authenticator = mAuthenticators.getAuthenticators(userId).get(pending.accountType);
            if (authenticator == null || !pending.authenticatorPackage.equals(authenticator.serviceInfo.packageName)
                    || !pending.authenticatorSigner.equals(mIdentity.accountSigner(pending.authenticatorPackage, userId))) {
                throw new IllegalArgumentException("Account authenticator changed");
            }
        } catch (IllegalArgumentException | SecurityException invalidSession) {
            response.onError(AccountManager.ERROR_CODE_BAD_ARGUMENTS, "Account session is no longer valid");
            return;
        }
        Bundle request = pending.credentials.deepCopy();
        request.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, callerPackage);
        request.putInt(AccountManager.KEY_CALLER_UID, identity.getInt(AccountManager.KEY_CALLER_UID));
        request.putInt(AccountManager.KEY_CALLER_PID, identity.getInt(AccountManager.KEY_CALLER_PID));
        request.putString(AccountManager.KEY_ACCOUNT_TYPE, pending.accountType);
        mSessionManager.new Session(Operation.FINISH_SESSION, accounts, response, pending.accountType, expectActivity, true, null, false, true) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.finishSession(this, pending.accountType, request);
            }
        }.bind();
    }

    @Override
    public void isCredentialsUpdateSuggested(IAccountManagerResponse response, Account account,
                                             String statusToken, int userId) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (!mAccess.requireVisibleAccount(response, accounts, account)) return;
        mSessionManager.new Session(Operation.CREDENTIALS_UPDATE_SUGGESTED, accounts, response, account.type, false, true, account.name, false) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.isCredentialsUpdateSuggested(this, account, statusToken);
            }
        }.bind();
    }

    @Override
    public void updateCredentials(IAccountManagerResponse response, Account account, String authTokenType, boolean expectActivityLaunch, Bundle suppliedOptions, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (account == null) throw new IllegalArgumentException("account is null");
        final Bundle loginOptions = mAccess.accountOptions(suppliedOptions, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        if (!mAccess.requireVisibleAccount(response, accounts, account)) return;
        mSessionManager.new Session(Operation.UPDATE_CREDENTIALS, accounts, response, account.type, expectActivityLaunch,
                true , account.name,
                false , true ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.updateCredentials(this, account, authTokenType, loginOptions);
            }
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", updateCredentials"
                        + ", user=" + userId
                        + ", authTokenType " + authTokenType;
            }
        }.bind();
    }

    @Override
    public void editProperties(IAccountManagerResponse response, String accountType, boolean expectActivityLaunch, int userId) throws RemoteException {
        if (response == null) throw new IllegalArgumentException("response is null");
        if (accountType == null) throw new IllegalArgumentException("accountType is null");
        mAccess.enforceAuthenticatorOwner(accountType, userId);

        BUserAccounts accounts = getUserAccounts(userId);
        mSessionManager.new Session(Operation.EDIT_PROPERTIES, accounts, response, accountType, expectActivityLaunch,
                true , null ,
                false ) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.editProperties(this, mAccountType);
            }
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", editProperties"
                        + ", accountType " + accountType;
            }
        }.bind();
    }

    @Override
    public void confirmCredentialsAsUser(IAccountManagerResponse response, Account account, Bundle options, boolean expectActivityLaunch, int userId) throws RemoteException {
        Objects.requireNonNull(response, "response cannot be null");
        Objects.requireNonNull(account, "account cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (!mAccess.requireVisibleAccount(response, accounts, account)) return;
        Bundle request = mAccess.accountOptions(options, userId);
        mSessionManager.new Session(Operation.CONFIRM_CREDENTIALS, accounts, response, account.type, expectActivityLaunch,
                true, account.name, true, true) {
            @Override
            public void run() throws RemoteException {
                mAuthenticator.confirmCredentials(this, account, request);
            }
        }.bind();
    }

    @Override
    public boolean accountAuthenticated(Account account, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        return mAccess.updateLastAuthenticatedTime(getUserAccounts(userId), account);
    }

    @Override
    public void getAuthTokenLabel(IAccountManagerResponse response, String accountType, String authTokenType, int userId) throws RemoteException {
        Preconditions.checkArgument(accountType != null, "accountType cannot be null");
        Preconditions.checkArgument(authTokenType != null, "authTokenType cannot be null");

        BUserAccounts accounts = getUserAccounts(userId);
        mSessionManager.new Session(Operation.GET_AUTH_TOKEN_LABEL, accounts, response, accountType, false ,
                false ,  null ,
                false ) {
            @Override
            protected String toDebugString(long now) {
                return super.toDebugString(now) + ", getAuthTokenLabel"
                        + ", " + accountType
                        + ", authTokenType " + authTokenType;
            }

            @Override
            public void run() throws RemoteException {
                mAuthenticator.getAuthTokenLabel(this, authTokenType);
            }

            @Override
            public void onResult(Bundle result) {
                if (!isActive()) return;
                if (result != null) {
                    String label = result.getString(AccountManager.KEY_AUTH_TOKEN_LABEL);
                    Bundle bundle = new Bundle();
                    bundle.putString(AccountManager.KEY_AUTH_TOKEN_LABEL, label);
                    super.onResult(bundle);
                } else {
                    super.onResult(result);
                }
            }
        }.bind();
    }

    @Override
    public PackageVisibilityMap getPackagesAndVisibilityForAccount(Account account, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            return new PackageVisibilityMap(new HashMap<>(mAccess.requireStoredAccount(accounts, account).visibility));
        }
    }

    protected void saveCachedToken(BUserAccounts accounts, Account account, String callerPkg,
                                   String tokenType, String token, long expiryMillis) {
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            TokenCache cache = new TokenCache(accounts.userId, account, callerPkg, tokenType, token, expiryMillis);
            cache.userGeneration = accounts.userGeneration;
            cache.resetEpoch = accounts.googleResetEpoch;
            cache.callerSigner = mIdentity.accountSigner(callerPkg, accounts.userId);
            cache.authenticatorSigner = stored.accountUserData.get(AccountCredentialPolicy.OWNER_SIGNER);
            synchronized (mTokenCaches) {
                mTokenCaches.removeIf(old -> old.expiryEpochMillis <= System.currentTimeMillis()
                        || (old.userId == accounts.userId && old.account.equals(account)
                        && old.packageName.equals(callerPkg) && old.authTokenType.equals(tokenType)));
                if (mTokenCaches.size() >= 256) mTokenCaches.removeFirst();
                mTokenCaches.add(cache);
            }
        }
    }

    protected boolean saveAuthTokenToDatabase(BUserAccounts accounts, Account account,
                                               String authTokenType, String authToken) {
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            boolean existed = stored.authTokens.containsKey(authTokenType);
            String previous = stored.authTokens.get(authTokenType);
            if (!AccountWritePolicy.changesValue(existed, previous, authToken)) return true;
            if (authToken == null) stored.authTokens.remove(authTokenType); else stored.authTokens.put(authTokenType, authToken);
            if (mStore.saveAllAccounts()) return true;
            if (existed) stored.authTokens.put(authTokenType, previous); else stored.authTokens.remove(authTokenType);
            return false;
        }
    }

    protected String readCachedTokenInternal(BUserAccounts accounts, Account account, String tokenType,
                                               String callingPackage) {
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            String callerSigner = mIdentity.accountSigner(callingPackage, accounts.userId);
            synchronized (mTokenCaches) {
                Iterator<TokenCache> iterator = mTokenCaches.iterator();
                while (iterator.hasNext()) {
                    TokenCache token = iterator.next();
                    if (token.userId != accounts.userId || !token.account.equals(account)
                            || !token.authTokenType.equals(tokenType) || !token.packageName.equals(callingPackage)) continue;
                    if (token.userGeneration == accounts.userGeneration
                            && (!GOOGLE_ACCOUNT_TYPE.equals(account.type) || token.resetEpoch == accounts.googleResetEpoch)
                            && callerSigner.equals(token.callerSigner)
                            && Objects.equals(token.authenticatorSigner, stored.accountUserData.get(AccountCredentialPolicy.OWNER_SIGNER))
                            && token.expiryEpochMillis > System.currentTimeMillis()) return token.authToken;
                    iterator.remove();
                }
            }
            return null;
        }
    }

    protected String readAuthTokenInternal(BUserAccounts accounts, Account account, String authTokenType) {
        synchronized (accounts.lock) { return mAccess.requireStoredAccount(accounts, account).authTokens.get(authTokenType); }
    }

    @Override
    public boolean addAccountExplicitlyWithVisibility(Account account, String password,
                                                      Bundle extras, PackageVisibilityMap packageToVisibility, int userId) {

        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        return addAccountInternal(accounts, account, password, extras,
                packageToVisibility == null ? null : packageToVisibility.toMap());
    }

    @Override
    public boolean setAccountVisibility(Account account, String packageName, int newVisibility, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        mAccess.enforceAuthenticatorOwner(account.type, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        synchronized (accounts.lock) {
            BAccount stored = mAccess.requireStoredAccount(accounts, account);
            Map<String, Integer> previous = new HashMap<>(stored.visibility);
            Map<String, String> metadata = new HashMap<>(stored.accountUserData);
            if (!setAccountVisibility(account, packageName, newVisibility, accounts)) return false;
            boolean changed = !previous.equals(stored.visibility) || !metadata.equals(stored.accountUserData);
            if (changed && !mStore.saveAllAccounts()) {
                stored.visibility.clear(); stored.visibility.putAll(previous);
                stored.accountUserData.clear(); stored.accountUserData.putAll(metadata);
                return false;
            }
            clearCachedTokens(userId, account);
        }
        notifyAccountsChanged(accounts);
        return true;
    }

    @Override
    public int getAccountVisibility(Account account, String packageName, int userId) {
        Objects.requireNonNull(account, "account cannot be null");
        Objects.requireNonNull(packageName, "package cannot be null");
        BUserAccounts accounts = getUserAccounts(userId);
        if (AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                || AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) {
            mAccess.enforceAuthenticatorOwner(account.type, userId);
            synchronized (accounts.lock) {
                BAccount stored = mAccess.requireStoredAccount(accounts, account);
                return stored.visibility.getOrDefault(packageName,
                        AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                        ? AccountManager.VISIBILITY_USER_MANAGED_VISIBLE : AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE);
            }
        }
        return mAccess.resolveAccountVisibility(account, mAccess.queryPackage(packageName, account.type, userId), accounts);
    }

    @Override
    public AccountVisibilityMap getAccountsAndVisibilityForPackage(String packageName, String accountType, int userId) {
        String target = mAccess.queryPackage(packageName, accountType, userId);
        BUserAccounts accounts = getUserAccounts(userId);
        Map<Account, Integer> result = new LinkedHashMap<>();
        synchronized (accounts.lock) {
            mAccess.requireAccountStateAvailableLocked(accounts);
            for (BAccount stored : accounts.accounts) {
                if (accountType != null && !accountType.equals(stored.account.type)) continue;
                int visibility = mAccess.resolveAccountVisibility(stored.account, target, accounts);
                if (visibility != AccountManager.VISIBILITY_NOT_VISIBLE) result.put(stored.account, visibility);
            }
        }
        return new AccountVisibilityMap(result);
    }

    @Override
    public void registerAccountListener(String[] accountTypes, String opPackageName, int userId) {
        mAccess.queryPackage(opPackageName, null, userId);
        getUserAccounts(userId);
        // Android's AccountManager registers its receiver; the virtual broadcast service
        // delivers LOGIN_ACCOUNTS_CHANGED_ACTION and subsequent reads enforce visibility.
    }

    @Override
    public void unregisterAccountListener(String[] accountTypes, String opPackageName, int userId) {
        mAccess.queryPackage(opPackageName, null, userId);
    }

    private boolean addAccountInternal(BUserAccounts accounts, Account account, String password,
                                       Bundle extras, Map<String, Integer> packageToVisibility) {
        if (accounts == null) return false;
        AuthenticatorInfo owner = mAuthenticators.getAuthenticators(accounts.userId).get(account.type);
        if (owner == null) throw new IllegalStateException("Account authenticator is not installed");
        BAccount prepared = new BAccount();
        prepared.account = account;
        prepared.password = password;
        prepared.accountUserData.put(AccountCredentialPolicy.OWNER_PACKAGE, owner.serviceInfo.packageName);
        prepared.accountUserData.put(AccountCredentialPolicy.OWNER_SIGNER, mIdentity.accountSigner(owner.serviceInfo.packageName, accounts.userId));
        if (extras != null) for (String key : extras.keySet()) {
            if (AccountCredentialPolicy.isInternal(key)) throw new SecurityException("Reserved account metadata");
            prepared.insertExtra(key, extras.getString(key));
        }
        if (packageToVisibility != null) for (Map.Entry<String, Integer> entry : packageToVisibility.entrySet()) {
            String packageName = entry.getKey();
            Integer visibility = entry.getValue();
            if (TextUtils.isEmpty(packageName) || visibility == null || visibility < AccountManager.VISIBILITY_UNDEFINED
                    || visibility > AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) {
                throw new IllegalArgumentException("Invalid account visibility");
            }
            if (!AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                    && !AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName)) {
                prepared.accountUserData.put(AccountCredentialPolicy.visibilityKey(packageName), mIdentity.accountSigner(packageName, accounts.userId));
            }
            prepared.visibility.put(packageName, visibility);
        }
        synchronized (accounts.lock) {
            mAccess.requireAccountStateAvailableLocked(accounts);
            if (accounts.getAccount(account) != null) return false;
            accounts.accounts.add(prepared);
            if (!mStore.saveAllAccounts()) { accounts.accounts.remove(prepared); return false; }
        }
        notifyAccountsChanged(accounts);
        return true;
    }

    private boolean setAccountVisibility(Account account, String packageName, int newVisibility, BUserAccounts accounts) {
        if (TextUtils.isEmpty(packageName) || newVisibility < AccountManager.VISIBILITY_UNDEFINED
                || newVisibility > AccountManager.VISIBILITY_USER_MANAGED_NOT_VISIBLE) {
            throw new IllegalArgumentException("Invalid account visibility");
        }
        synchronized (accounts.lock) {
            mAccess.requireAccountStateAvailableLocked(accounts);
            BAccount stored = accounts.getAccount(account);
            if (stored == null) return false;
            boolean legacy = AccountManager.PACKAGE_NAME_KEY_LEGACY_VISIBLE.equals(packageName)
                    || AccountManager.PACKAGE_NAME_KEY_LEGACY_NOT_VISIBLE.equals(packageName);
            if (!legacy) {
                String signer = mIdentity.accountSigner(packageName, accounts.userId);
                stored.accountUserData.put(AccountCredentialPolicy.visibilityKey(packageName), signer);
                if (!AccountAccess.visibleAccount(newVisibility)) AccountCredentialPolicy.removePackageGrants(stored.accountUserData, packageName);
            }
            if (newVisibility == AccountManager.VISIBILITY_UNDEFINED) stored.visibility.remove(packageName);
            else stored.visibility.put(packageName, newVisibility);
            return true;
        }
    }

    private void handleGetAccountsResult(IAccountManagerResponse response, Account[] candidates,
                                         String callingPackage, int userId, ProcessRecord requestor) throws RemoteException {
        mAccess.resumeAccountCaller(requestor, () -> {
            BUserAccounts accounts = getUserAccounts(userId);
            if (candidates.length == 0) { response.onResult(new Bundle()); return null; }
            boolean choose = candidates.length > 1
                    || !AccountAccess.visibleAccount(mAccess.resolveAccountVisibility(candidates[0], callingPackage, accounts));
            if (choose) {
                mInteractions.new Request(accounts, requestor, callingPackage, candidates, null, response, null).deliver();
            } else {
                synchronized (accounts.lock) { mAccess.requireStoredAccount(accounts, candidates[0]); }
                Bundle result = new Bundle();
                result.putString(AccountManager.KEY_ACCOUNT_NAME, candidates[0].name);
                result.putString(AccountManager.KEY_ACCOUNT_TYPE, candidates[0].type);
                response.onResult(result);
            }
            return null;
        });
    }

    private boolean removeAccountInternal(BUserAccounts accounts, Account account) {
        boolean deleted;
        synchronized (accounts.lock) {
            mAccess.requireAccountStateAvailableLocked(accounts);
            BAccount previous = accounts.getAccount(account);
            int index = accounts.accounts.indexOf(previous);
            deleted = accounts.delAccount(account);
            if (deleted && !mStore.saveAllAccounts()) {
                accounts.accounts.add(index, previous);
                return false;
            }
            if (deleted) {
                synchronized (mTokenCaches) {
                    mTokenCaches.removeIf(token -> token.userId == accounts.userId && token.account.equals(account));
                }
            }
        }
        if (deleted) notifyAccountsChanged(accounts);
        return deleted;
    }

    public void loadAuthenticatorCache(String packageName) {
        if (packageName != null) mSessionManager.invalidatePendingPackage(packageName);
        mAuthenticators.invalidate();
    }

    private void onResult(IAccountManagerResponse response, Bundle result) {
        if (result == null) {
            Slog.e(TAG, "the result is unexpectedly null", new Exception());
        }
        if (Slog.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                    + response);
        }
        try {
            response.onResult(result);
        } catch (RemoteException e) {

            if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "failure while notifying response", e);
            }
        }
    }

    protected Account[] getAccountsFromCache(BUserAccounts accounts, String accountType, String packageName,
                                              boolean includeManagedNotVisible) {
        return mAccess.getAccountsFromCache(accounts, accountType, packageName, includeManagedNotVisible);
    }

    public String readPasswordInternal(BUserAccounts accounts, Account account) {
        return mAccess.readPasswordInternal(accounts, account);
    }

    public BUserAccounts getUserAccounts(int userId) {
        return mAccess.getUserAccounts(userId);
    }
}
