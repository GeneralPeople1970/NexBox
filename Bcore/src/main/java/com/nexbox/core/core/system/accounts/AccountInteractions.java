package com.nexbox.core.core.system.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.accounts.AccountAccess.AccountAction;

/** Owns the host UI capabilities used to choose accounts and grant token access. */
final class AccountInteractions {
    interface Grant {
        void grant(BUserAccounts accounts, Account account, String packageName, String tokenType,
                   boolean allow, boolean exposeAccount);
    }

    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final long INTERACTIVE_TIMEOUT_MS = 10 * 60_000L;
    private final Map<IBinder, Request> mAccountInteractions = new HashMap<>();
    private final Handler mHandler;
    private final AccountAccess mAccess;
    private final AccountPackageIdentity mIdentity;
    private final Grant mGrant;

    AccountInteractions(Handler handler, AccountAccess access, AccountPackageIdentity identity, Grant grant) {
        mHandler = handler;
        mAccess = access;
        mIdentity = identity;
        mGrant = grant;
    }

    void packageChanged(String packageName, int userId) {
        List<Request> requests;
        synchronized (mAccountInteractions) { requests = new ArrayList<>(mAccountInteractions.values()); }
        for (Request request : requests) {
            if (request.accounts.userId == userId && (packageName.equals(request.packageName)
                    || request.ownerPackages.contains(packageName))) {
                request.invalidated = true;
                mHandler.post(() -> request.abort("Account package changed"));
            }
        }
    }

    private Request interaction(IBinder capability) {
        com.nexbox.core.core.system.xposed.XposedCallerGuard.enforceHostManager();
        synchronized (mAccountInteractions) {
            Request interaction = mAccountInteractions.get(capability);
            if (interaction == null) throw new IllegalArgumentException("Account request expired");
            return interaction;
        }
    }

    Bundle getAccountInteraction(IBinder capability, IBinder uiLifetime) throws RemoteException {
        Request interaction = interaction(capability);
        interaction.attachUi(uiLifetime);
        return mAccess.resumeAccountCaller(interaction.caller, interaction::state);
    }

    Bundle completeAccountInteraction(IBinder capability, int choice) throws RemoteException {
        Request interaction = interaction(capability);
        return mAccess.resumeAccountCaller(interaction.caller, () -> interaction.choose(choice));
    }

    void cancelAccountInteraction(IBinder capability) {
        interaction(capability).abort("Account request canceled");
    }

    void cancelAccountInteractions(int userId, boolean googleOnly) {
        List<Request> pending;
        synchronized (mAccountInteractions) { pending = new ArrayList<>(mAccountInteractions.values()); }
        for (Request request : pending) {
            if (request.accounts.userId == userId && (!googleOnly || request.hasGoogleAccount())) {
                request.abort("Account environment changed");
            }
        }
    }

    final class Request extends Binder {
        final BUserAccounts accounts;
        final ProcessRecord caller;
        final String packageName;
        final String signer;
        final long generation;
        final long resetEpoch;
        final Account[] candidates;
        final BAccount[] records;
        final Set<String> ownerPackages = new HashSet<>();
        private volatile boolean invalidated;
        final String tokenType;
        final IAccountManagerResponse response;
        final AccountAction<Void> granted;
        final long expiresAt = SystemClock.elapsedRealtime() + INTERACTIVE_TIMEOUT_MS;
        final Runnable timeout = () -> abort("Account request expired");
        final IBinder.DeathRecipient death = () -> abort("Account request caller died");
        private boolean closed;
        private boolean linked;
        private IBinder uiLifetime;
        private final IBinder.DeathRecipient uiDeath = () -> abort("Account window closed");

        Request(BUserAccounts accounts, ProcessRecord caller, String packageName, Account[] candidates,
                           String tokenType, IAccountManagerResponse response, AccountAction<Void> granted) throws RemoteException {
            this.accounts = accounts;
            this.caller = Objects.requireNonNull(caller, "virtual caller required");
            this.packageName = packageName;
            this.signer = mIdentity.accountSigner(packageName, accounts.userId);
            this.generation = accounts.userGeneration;
            this.resetEpoch = accounts.googleResetEpoch;
            this.candidates = candidates.clone();
            this.records = new BAccount[candidates.length];
            this.tokenType = tokenType;
            this.response = response;
            this.granted = granted;
            synchronized (accounts.lock) {
                for (int i = 0; i < candidates.length; i++) {
                    records[i] = mAccess.requireStoredAccount(accounts, candidates[i]);
                    ownerPackages.add(records[i].accountUserData.get(AccountCredentialPolicy.OWNER_PACKAGE));
                }
            }
            synchronized (mAccountInteractions) {
                if (mAccountInteractions.size() >= 32) throw new IllegalStateException("Too many account requests");
                mAccountInteractions.put(this, this);
            }
            try {
                response.asBinder().linkToDeath(death, 0);
                synchronized (this) {
                    if (closed) response.asBinder().unlinkToDeath(death, 0); else linked = true;
                }
                mHandler.postDelayed(timeout, INTERACTIVE_TIMEOUT_MS);
            } catch (RemoteException failure) {
                close();
                throw failure;
            }
        }

        boolean hasGoogleAccount() {
            for (Account account : candidates) if (GOOGLE_ACCOUNT_TYPE.equals(account.type)) return true;
            return false;
        }

        synchronized void attachUi(IBinder token) throws RemoteException {
            if (closed || token == null) throw new IllegalArgumentException("Account window is unavailable");
            if (uiLifetime == token) return;
            token.linkToDeath(uiDeath, 0);
            if (uiLifetime != null) uiLifetime.unlinkToDeath(uiDeath, 0);
            uiLifetime = token;
        }

        private void validate() {
            if (closed || invalidated || SystemClock.elapsedRealtime() >= expiresAt || accounts.userGeneration != generation
                    || (hasGoogleAccount() && accounts.googleResetEpoch != resetEpoch)
                    || !signer.equals(mIdentity.accountSigner(packageName, accounts.userId))) {
                throw new IllegalStateException("Account request is no longer current");
            }
            mAccess.requireAccountStateAvailableLocked(accounts);
            for (int i = 0; i < candidates.length; i++) {
                if (accounts.getAccount(candidates[i]) != records[i]
                        || mAccess.resolveAccountVisibility(candidates[i], packageName, accounts) == AccountManager.VISIBILITY_NOT_VISIBLE) {
                    throw new IllegalStateException("Account choices changed");
                }
                mAccess.requireStoredAccount(accounts, candidates[i]);
            }
        }

        synchronized Bundle state() {
            synchronized (accounts.lock) {
                validate();
                Bundle state = new Bundle();
                String[] names = new String[candidates.length];
                for (int i = 0; i < names.length; i++) names[i] = candidates[i].name;
                state.putStringArray(AccountInteractionContract.ACCOUNT_NAMES, names);
                state.putString(AccountInteractionContract.CALLER_PACKAGE, packageName);
                state.putBoolean(AccountInteractionContract.GRANT_TOKEN, tokenType != null);
                return state;
            }
        }

        Bundle choose(int index) throws RemoteException {
            Bundle result = new Bundle();
            synchronized (this) {
                synchronized (accounts.lock) {
                    validate();
                    if (index < 0 || index >= candidates.length) throw new IllegalArgumentException("Invalid account choice");
                    mGrant.grant(accounts, candidates[index], packageName, tokenType, true, true);
                    result.putString(AccountManager.KEY_ACCOUNT_NAME, candidates[index].name);
                    result.putString(AccountManager.KEY_ACCOUNT_TYPE, candidates[index].type);
                    close();
                }
            }
            try {
                if (granted != null) granted.run(); else response.onResult(result);
            } catch (RemoteException | RuntimeException failure) {
                try { response.onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Account request could not continue"); }
                catch (RemoteException ignored) { }
                throw failure;
            }
            return result;
        }

        private synchronized void close() {
            if (closed) return;
            closed = true;
            synchronized (mAccountInteractions) { mAccountInteractions.remove(this); }
            mHandler.removeCallbacks(timeout);
            if (linked) response.asBinder().unlinkToDeath(death, 0);
            linked = false;
            if (uiLifetime != null) uiLifetime.unlinkToDeath(uiDeath, 0);
            uiLifetime = null;
        }

        void abort(String message) {
            synchronized (this) { if (closed) return; close(); }
            mHandler.post(() -> {
                try { response.onError(AccountManager.ERROR_CODE_CANCELED, message); }
                catch (RemoteException ignored) { /* The original caller may have gone away. */ }
            });
        }

        void deliver() throws RemoteException {
            Bundle result = new Bundle();
            result.putParcelable(AccountManager.KEY_INTENT, AccountInteractionContract.createIntent(this));
            try { response.onResult(result); }
            catch (RemoteException failure) { close(); throw failure; }
        }
    }
}
