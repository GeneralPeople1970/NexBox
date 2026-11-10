package com.nexbox.core.core.system.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.accounts.IAccountManagerResponse;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.nexbox.core.core.system.BProcessManagerService;
import com.nexbox.core.core.system.ProcessRecord;
import com.nexbox.core.core.system.accounts.AccountAuthenticatorRegistry.AuthenticatorInfo;
import com.nexbox.core.core.system.accounts.AccountSessionDiagnostics.Failure;
import com.nexbox.core.core.system.accounts.AccountSessionDiagnostics.Operation;
import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.NexBoxIpcKeys;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

/** Owns authenticator Binder sessions, callback channels, deadlines and deferred credentials. */
final class AccountSessionManager {
    interface Removal {
        boolean remove(BUserAccounts accounts, Account account);
    }

    private static final String TAG = "AccountManagerService";
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final long AUTHENTICATOR_TIMEOUT_MS = 60_000L;
    private static final long INTERACTIVE_TIMEOUT_MS = 10 * 60_000L;
    private static final AtomicLong NEXT_SESSION_ID = new AtomicLong();
    private final LinkedHashMap<String, Session> mSessions = new LinkedHashMap<>();
    private final AccountSessionStore mPendingAccountSessions = new AccountSessionStore();
    private final Context mContext;
    private final BPackageManagerService mPms;
    private final AccountStore mStore;
    private final AccountAccess mAccess;
    private final AccountAuthenticatorRegistry mAuthenticators;
    private final AccountPackageIdentity mIdentity;
    private final Handler mHandler;

    AccountSessionManager(Context context, BPackageManagerService pms, AccountStore store,
                          AccountAccess access, AccountAuthenticatorRegistry authenticators,
                          AccountPackageIdentity identity, Handler handler) {
        mContext = context;
        mPms = pms;
        mStore = store;
        mAccess = access;
        mAuthenticators = authenticators;
        mIdentity = identity;
        mHandler = handler;
    }

    void packageChanged(String packageName, int userId) {
        List<Session> sessions;
        synchronized (mSessions) { sessions = new ArrayList<>(mSessions.values()); }
        for (Session session : sessions) {
            AuthenticatorInfo owner = session.mBoundAuthenticator;
            if (session.mAccounts.userId == userId && ((owner != null && packageName.equals(owner.serviceInfo.packageName))
                    || (session.mRequestor != null && packageName.equals(session.mRequestor.getPackageName())))) {
                session.mInvalidated = true;
                mHandler.post(() -> session.fail(Failure.OWNER_CHANGED,
                        AccountManager.ERROR_CODE_CANCELED, "Account package changed"));
            }
        }
    }

    void closeUserSessions(int userId) {
        List<Session> sessions;
        synchronized (mSessions) { sessions = new ArrayList<>(mSessions.values()); }
        for (Session session : sessions) {
            if (session.mAccounts.userId == userId) session.close();
        }
    }

    List<Session> googleSessions(BUserAccounts accounts) {
        List<Session> sessions = new ArrayList<>();
        synchronized (mSessions) {
            for (Session session : mSessions.values()) {
                if (session.mAccounts == accounts && GOOGLE_ACCOUNT_TYPE.equals(session.mAccountType)) {
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }

    void removePendingUser(int userId) {
        mPendingAccountSessions.removeUser(userId);
    }

    void removePendingGoogleAccounts(int userId) {
        mPendingAccountSessions.removeGoogleAccounts(userId);
    }

    void invalidatePendingPackage(String packageName) {
        mPendingAccountSessions.packageChanged(packageName);
    }

    AccountSessionStore.Entry takePendingSession(Bundle capability, BUserAccounts accounts,
                                                 String packageName, String signer) {
        return mPendingAccountSessions.take(capability, accounts.userId, accounts.userGeneration,
                accounts.googleResetEpoch, packageName, signer);
    }

    abstract class Session extends IAccountAuthenticatorResponse.Stub
            implements IBinder.DeathRecipient, ServiceConnection, AccountAccess.Callback {
        volatile IAccountManagerResponse mResponse;
        final String mAccountType;
        final boolean mExpectActivityLaunch;
        final long mCreationTime;
        final String mAccountName;

        final boolean mAuthDetailsRequired;

        final boolean mUpdateLastAuthenticatedTime;

        public int mNumResults = 0;
        private int mNumRequestContinued = 0;
        private int mNumErrors = 0;

        volatile IAccountAuthenticator mAuthenticator;

        private final boolean mStripAuthTokenFromResult;
        protected final BUserAccounts mAccounts;
        private final long mGoogleResetEpoch;
        private volatile boolean mClosed;
        private volatile boolean mInvalidated;
        private boolean mBound;
        private boolean mResponseLinked;
        private final ProcessRecord mRequestor;
        protected final BAccount mTargetAccount;
        private volatile AuthenticatorInfo mBoundAuthenticator;
        private volatile String mAuthenticatorSigner;
        private volatile ProcessRecord mAuthenticatorProcess;
        private final Runnable mTimeout = this::onTimedOut;
        private long mTimeoutAt;
        private final Operation mOperation;
        private final long mDiagnosticId = NEXT_SESSION_ID.incrementAndGet();

        public Session(Operation operation, BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired) {
            this(operation, accounts, response, accountType, expectActivityLaunch, stripAuthTokenFromResult,
                    accountName, authDetailsRequired, false );
        }

        public Session(Operation operation, BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                       boolean expectActivityLaunch, boolean stripAuthTokenFromResult, String accountName,
                       boolean authDetailsRequired, boolean updateLastAuthenticatedTime) {
            super();

            if (accountType == null) throw new IllegalArgumentException("accountType is null");
            mOperation = Objects.requireNonNull(operation);
            mAccounts = accounts;
            mStripAuthTokenFromResult = stripAuthTokenFromResult;
            mResponse = response;
            mAccountType = accountType;
            mExpectActivityLaunch = expectActivityLaunch;
            mCreationTime = SystemClock.elapsedRealtime();
            mAccountName = accountName;
            mAuthDetailsRequired = authDetailsRequired;
            mUpdateLastAuthenticatedTime = updateLastAuthenticatedTime;
            mRequestor = mAccess.accountCaller(accounts.userId);

            synchronized (accounts.lock) {
                mAccess.requireAccountStateAvailableLocked(accounts);
                mGoogleResetEpoch = accounts.googleResetEpoch;
                mTargetAccount = accountName == null ? null : accounts.getAccount(new Account(accountName, accountType));
                synchronized (mSessions) {
                    mSessions.put(toString(), this);
                }
            }
            if (response != null) {
                try {
                    response.asBinder().linkToDeath(this, 0 );
                    boolean closed;
                    synchronized (mSessions) {
                        closed = mClosed;
                        if (!closed) mResponseLinked = true;
                    }
                    if (closed) response.asBinder().unlinkToDeath(this, 0);
                } catch (RemoteException e) {
                    mResponse = null;
                    binderDied();
                }
            }
        }

        @Override
        public BUserAccounts accounts() {
            return mAccounts;
        }

        @Override
        public final boolean isActive() {
            synchronized (mAccounts.lock) {
                BUserInfo user = BUserManagerService.get().getUserInfo(mAccounts.userId);
                return !mClosed && !mInvalidated && user != null && user.createTime == mAccounts.userGeneration
                        && mStore.peek(mAccounts.userId) == mAccounts
                        && (mTargetAccount == null || mAccounts.getAccount(mTargetAccount.account) == mTargetAccount)
                        && (mRequestor == null || (mRequestor.userGeneration == user.createTime
                        && BProcessManagerService.get().findProcessByPid(mRequestor.pid) == mRequestor
                        && mRequestor.bActivityThread != null && mRequestor.bActivityThread.asBinder().isBinderAlive()))
                        && (!GOOGLE_ACCOUNT_TYPE.equals(mAccountType)
                        || (!mAccounts.googleResetInProgress && mGoogleResetEpoch == mAccounts.googleResetEpoch));
            }
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == AccountAuthenticatorTransport.UNWRAP_LOCAL_RESPONSE) return false;
            if (code == AccountAuthenticatorTransport.OPEN_CALLBACK_CHANNEL
                    || code == AccountAuthenticatorTransport.CHECK_AUTHENTICATOR_SESSION) {
                if ((flags & IBinder.FLAG_ONEWAY) != 0 || reply == null) {
                    throw new SecurityException("Account session authentication must be synchronous");
                }
                data.enforceInterface(AccountAuthenticatorTransport.SESSION_DESCRIPTOR);
                IBinder processToken = data.readStrongBinder();
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected account session data");
                ProcessRecord caller = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
                boolean active = isActive();
                boolean verified = active && Binder.getCallingUid() == android.os.Process.myUid()
                        && caller != null && processToken == caller && isCurrentAuthenticator(caller);
                if (code == AccountAuthenticatorTransport.CHECK_AUTHENTICATOR_SESSION) {
                    reply.writeNoException();
                    reply.writeInt(verified && caller == mAuthenticatorProcess ? 1 : 0);
                } else {
                    if (active && !verified) {
                        throw new SecurityException("Account callback channel does not belong to the authenticator");
                    }
                    reply.writeNoException();
                    reply.writeStrongBinder(active ? new CallbackChannel(caller) : null);
                }
                return true;
            }
            if (code < IBinder.FIRST_CALL_TRANSACTION || code > IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags);
            }
            return dispatchCallback(code, data, reply, flags,
                    BProcessManagerService.get().findProcessByPid(Binder.getCallingPid()));
        }

        private boolean isCurrentAuthenticator(ProcessRecord caller) {
            AuthenticatorInfo authenticator = mBoundAuthenticator;
            return caller != null && caller.info != null && authenticator != null
                    && caller.userId == mAccounts.userId && caller.userGeneration == mAccounts.userGeneration
                    && BProcessManagerService.get().findProcessByPid(caller.pid) == caller
                    && caller.bActivityThread != null && caller.bActivityThread.asBinder().isBinderAlive()
                    && Objects.equals(mAuthenticatorSigner, mIdentity.accountSigner(authenticator.serviceInfo.packageName, mAccounts.userId))
                    && Objects.equals(mAuthenticatorSigner, mIdentity.accountSigner(caller.getPackageName(), mAccounts.userId));
        }

        private boolean dispatchCallback(int code, Parcel data, Parcel reply, int flags, ProcessRecord caller)
                throws RemoteException {
            if (!isActive()) {
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (Binder.getCallingUid() != android.os.Process.myUid() || !isCurrentAuthenticator(caller)) {
                throw new SecurityException("Account callback does not belong to this profile's authenticator");
            }
            return mAccess.dispatchCallback(this, caller, () -> {
                try {
                    return super.onTransact(code, data, reply, flags);
                } catch (RuntimeException invalidResult) {
                    Slog.w(TAG, "Invalid authenticator callback: user=" + mAccounts.userId
                            + ", failure=" + invalidResult.getClass().getSimpleName());
                    fail(Failure.INVALID_RESPONSE, AccountManager.ERROR_CODE_INVALID_RESPONSE, "Invalid authenticator response");
                    if (reply != null) reply.writeNoException();
                    return true;
                }
            });
        }

        private final class CallbackChannel extends Binder {
            private final ProcessRecord caller;

            CallbackChannel(ProcessRecord caller) {
                this.caller = caller;
                attachInterface(null, AccountAuthenticatorTransport.RESPONSE_DESCRIPTOR);
            }

            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
                // IAccountAuthenticatorResponse has only onResult/onRequestContinued/onError.
                if (code < FIRST_CALL_TRANSACTION || code > FIRST_CALL_TRANSACTION + 2
                        || (flags & FLAG_ONEWAY) == 0 || Binder.getCallingPid() != 0) {
                    throw new SecurityException("Invalid account callback channel transaction");
                }
                // The private channel is bound to the actual process/token verified above,
                // not to the shared Linux UID or a caller-supplied PID. Recheck its lifetime.
                return dispatchCallback(code, data, reply, flags, caller);
            }
        }

        void cancelForReset() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) mHandler.post(() -> {
                try {
                    response.onError(AccountManager.ERROR_CODE_CANCELED, "The Google environment was reset");
                } catch (RemoteException ignored) {
                    // The caller may already have been stopped by the reset coordinator.
                }
            });
        }

        IAccountManagerResponse getResponseAndClose() {
            return close();
        }

        protected boolean checkKeyIntent(int authUid, Intent intent) {

            if (intent.getClipData() == null) {
                intent.setClipData(ClipData.newPlainText(null, null));
            }
            intent.setFlags(intent.getFlags() & ~(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION));
            long bid = Binder.clearCallingIdentity();
            try {
                ResolveInfo resolveInfo = mPms.resolveActivity(intent, 0, null, mAccounts.userId);
                AuthenticatorInfo authenticator = mBoundAuthenticator;
                if (resolveInfo == null || resolveInfo.activityInfo == null || authenticator == null
                        || intent.getSelector() != null) return false;
                if (!mIdentity.sameAccountSigner(resolveInfo.activityInfo.packageName,
                        authenticator.serviceInfo.packageName, mAccounts.userId)) return false;
                intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
                return true;
            } finally {
                Binder.restoreCallingIdentity(bid);
            }
        }

        private IAccountManagerResponse close() {
            IAccountManagerResponse response;
            boolean linked;
            synchronized (mSessions) {
                if (mClosed) return null;
                mClosed = true;
                mSessions.remove(toString());
                response = mResponse;
                mResponse = null;
                linked = mResponseLinked;
                mResponseLinked = false;
            }
            try {
                if (response != null && linked) response.asBinder().unlinkToDeath(this, 0);
            } catch (RuntimeException failure) {
                Slog.w(TAG, diagnostic("authenticatorCleanupFailed") + " reason=unlinkDeath"
                        + " failure=" + failure.getClass().getSimpleName());
            }
            cancelTimeout();
            try {
                unbind();
            } catch (RuntimeException failure) {
                Slog.w(TAG, diagnostic("authenticatorCleanupFailed") + " reason=unbind"
                        + " failure=" + failure.getClass().getSimpleName());
            }
            return response;
        }

        @Override
        public void binderDied() {
            mResponse = null;
            close();
        }

        protected String toDebugString() {
            return toDebugString(SystemClock.elapsedRealtime());
        }

        protected String toDebugString(long now) {
            return "Session: expectLaunch " + mExpectActivityLaunch
                    + ", connected " + (mAuthenticator != null)
                    + ", stats (" + mNumResults + "/" + mNumRequestContinued
                    + "/" + mNumErrors + ")"
                    + ", lifetime " + ((now - mCreationTime) / 1000.0);
        }

        void bind() {
            if (!isActive()) return;
            Slog.dLazy(TAG, () -> diagnostic("authenticatorBindRequested"));
            scheduleTimeout(AUTHENTICATOR_TIMEOUT_MS);
            try {
                Failure reason = bindToAuthenticator(mAccountType);
                if (reason != null && !mClosed) fail(reason,
                        AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator binding failed");
            } catch (RuntimeException failure) {
                fail(Failure.BIND_EXCEPTION, AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                        "Authenticator binding failed", failure);
            }
        }

        private void unbind() {
            mAuthenticator = null;
            mAuthenticatorProcess = null;
            boolean bound;
            synchronized (this) {
                bound = mBound;
                mBound = false;
            }
            if (bound) mContext.unbindService(this);
        }

        public void cancelTimeout() {
            synchronized (mSessions) {
                mTimeoutAt = 0L;
                mHandler.removeCallbacks(mTimeout);
            }
        }

        private void scheduleTimeout(long duration) {
            synchronized (mSessions) {
                if (mClosed) return;
                mTimeoutAt = SystemClock.elapsedRealtime() + duration;
                mHandler.removeCallbacks(mTimeout);
                mHandler.postDelayed(mTimeout, duration);
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!isActive()) { unbind(); return; }
            Failure stage = Failure.CONNECT_EXCEPTION;
            try {
                AuthenticatorInfo authenticator = mBoundAuthenticator;
                if (service == null) {
                    fail(Failure.NULL_BINDER, AccountManager.ERROR_CODE_INVALID_RESPONSE, "Unexpected authenticator service");
                    return;
                }
                if (authenticator == null || !new ComponentName(authenticator.serviceInfo.packageName,
                        authenticator.serviceInfo.name).equals(name)
                        || !"android.accounts.IAccountAuthenticator".equals(service.getInterfaceDescriptor())) {
                    fail(Failure.UNEXPECTED_SERVICE, AccountManager.ERROR_CODE_INVALID_RESPONSE, "Unexpected authenticator service");
                    return;
                }
                mAuthenticatorProcess = BProcessManagerService.get().findProcessRecord(
                        authenticator.serviceInfo.packageName, authenticator.serviceInfo.processName, mAccounts.userId);
                if (!isCurrentAuthenticator(mAuthenticatorProcess)) {
                    fail(Failure.PROCESS_STALE, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator process is no longer current");
                    return;
                }
                mAuthenticator = AccountAuthenticatorTransport.connect(service, asBinder());
                Slog.dLazy(TAG, () -> diagnostic("authenticatorConnected"));
                stage = Failure.REQUEST_EXCEPTION;
                run();
            } catch (RemoteException | RuntimeException e) {
                fail(stage, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception", e);
            }
        }

        @Override
        public void onNullBinding(ComponentName name) {
            fail(Failure.NULL_BINDER, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator returned no Binder");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            fail(Failure.BINDING_DIED, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "Authenticator binding died");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mAuthenticator = null;
            fail(Failure.DISCONNECTED, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "disconnected");
        }

        public abstract void run() throws RemoteException;

        public void onTimedOut() {
            synchronized (mSessions) {
                if (mClosed || mTimeoutAt == 0L) return;
                long remaining = mTimeoutAt - SystemClock.elapsedRealtime();
                if (remaining > 0L) {
                    mHandler.postDelayed(mTimeout, remaining);
                    return;
                }
            }
            fail(Failure.TIMEOUT, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "timeout");
        }

        @Override
        public void onResult(Bundle result) {
            if (!isActive()) return;
            mNumResults++;
            Slog.dLazy(TAG, () -> diagnostic("authenticatorResult")
                    + " hasIntent=" + (result != null && result.containsKey(AccountManager.KEY_INTENT)));
            Intent intent = null;
            if (result != null) {
                boolean isSuccessfulConfirmCreds = result.getBoolean(
                        AccountManager.KEY_BOOLEAN_RESULT, false);
                boolean isSuccessfulUpdateCredsOrAddAccount =
                        result.containsKey(AccountManager.KEY_ACCOUNT_NAME)
                                && result.containsKey(AccountManager.KEY_ACCOUNT_TYPE);

                boolean needUpdate = mUpdateLastAuthenticatedTime
                        && (isSuccessfulConfirmCreds || isSuccessfulUpdateCredsOrAddAccount);
                if (needUpdate || mAuthDetailsRequired) {
                    boolean accountPresent = mAccess.isAccountPresentForCaller(mAccountName, mAccountType, mAccounts.userId);
                    if (needUpdate && accountPresent) {
                        mAccess.updateLastAuthenticatedTime(mAccounts, new Account(mAccountName, mAccountType));
                    }
                    if (mAuthDetailsRequired) {
                        long lastAuthenticatedTime = -1;
                        if (accountPresent) {
                            lastAuthenticatedTime = mAccounts
                                    .findAccountLastAuthenticatedTime(
                                            new Account(mAccountName, mAccountType));
                        }
                        result.putLong(AccountManager.KEY_LAST_AUTHENTICATED_TIME,
                                lastAuthenticatedTime);
                    }
                }
            }
            if (result != null
                    && (intent = BundleCompat.getParcelable(result, AccountManager.KEY_INTENT, Intent.class)) != null) {
                if (!checkKeyIntent(
                        Binder.getCallingUid(),
                        intent)) {
                    fail(Failure.INVALID_RESPONSE, AccountManager.ERROR_CODE_INVALID_RESPONSE,
                            "invalid intent in bundle returned");
                    return;
                }
            }
            if (result != null
                    && !TextUtils.isEmpty(result.getString(AccountManager.KEY_AUTHTOKEN))) {
                String accountName = result.getString(AccountManager.KEY_ACCOUNT_NAME);
                String accountType = result.getString(AccountManager.KEY_ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    Account account = new Account(accountName, accountType);

                }
            }
            IAccountManagerResponse response;
            if (mExpectActivityLaunch && result != null
                    && result.containsKey(AccountManager.KEY_INTENT)) {
                scheduleTimeout(INTERACTIVE_TIMEOUT_MS);
                response = mResponse;
            } else {
                response = getResponseAndClose();
            }
            if (response != null) {
                try {
                    if (result == null) {
                        logFailure(Failure.INVALID_RESPONSE, AccountManager.ERROR_CODE_INVALID_RESPONSE, null);
                        if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, getClass().getSimpleName()
                                    + " calling onError() on response " + response);
                        }
                        response.onError(AccountManager.ERROR_CODE_INVALID_RESPONSE,
                                "null bundle returned");
                    } else {
                        if (mStripAuthTokenFromResult) {
                            result.remove(AccountManager.KEY_AUTHTOKEN);
                        }
                        if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                            Slog.v(TAG, getClass().getSimpleName()
                                    + " calling onResult() on response " + response);
                        }
                        if ((result.getInt(AccountManager.KEY_ERROR_CODE, -1) > 0) &&
                                (intent == null)) {
                            logFailure(Failure.AUTHENTICATOR_RESULT_ERROR, result.getInt(AccountManager.KEY_ERROR_CODE), null);
                            response.onError(result.getInt(AccountManager.KEY_ERROR_CODE),
                                    result.getString(AccountManager.KEY_ERROR_MESSAGE));
                        } else {
                            response.onResult(result);
                        }
                    }
                } catch (RemoteException e) {

                    if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override
        public void onRequestContinued() {
            mNumRequestContinued++;
        }

        @Override
        public void onError(int errorCode, String errorMessage) {
            fail(Failure.AUTHENTICATOR_ERROR, errorCode, errorMessage);
        }

        final void fail(Failure reason, int errorCode, String errorMessage) {
            fail(reason, errorCode, errorMessage, null);
        }

        private void fail(Failure reason, int errorCode, String errorMessage, Throwable failure) {
            mNumErrors++;
            logFailure(reason, errorCode, failure);
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, getClass().getSimpleName()
                            + " calling onError() on response " + response);
                }
                try {
                    response.onError(errorCode, errorMessage);
                } catch (RemoteException e) {
                    Slog.w(TAG, diagnostic("authenticatorResponseFailed") + " failure=" + e.getClass().getSimpleName());
                }
            } else {
                if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "Session.onError: already closed");
                }
            }
        }

        private String diagnostic(String stage) {
            AuthenticatorInfo authenticator = mBoundAuthenticator;
            return "stage=" + stage + AccountSessionDiagnostics.context(mOperation, mDiagnosticId,
                    mAccounts.userId, mAccounts.userGeneration,
                    mRequestor == null ? null : mRequestor.getPackageName(),
                    mRequestor == null ? null : mRequestor.processName,
                    authenticator == null ? null : authenticator.serviceInfo.packageName,
                    authenticator == null ? null : authenticator.serviceInfo.name);
        }

        private void logFailure(Failure reason, int errorCode, Throwable failure) {
            Slog.w(TAG, diagnostic("authenticatorError") + reason.fields(errorCode)
                    + " closed=" + mClosed
                    + (failure == null ? "" : " failure=" + failure.getClass().getSimpleName()));
        }

        private Failure bindToAuthenticator(String authenticatorType) {
            AuthenticatorInfo authenticatorInfo = mAuthenticators.getAuthenticators(mAccounts.userId).get(authenticatorType);
            if (authenticatorInfo == null) {
                return Failure.AUTHENTICATOR_MISSING;
            }
            mBoundAuthenticator = authenticatorInfo;
            mAuthenticatorSigner = mIdentity.accountSigner(authenticatorInfo.serviceInfo.packageName, mAccounts.userId);

            Intent intent = new Intent();
            intent.setAction(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            ComponentName componentName = new ComponentName(authenticatorInfo.serviceInfo.packageName, authenticatorInfo.serviceInfo.name);
            intent.setComponent(componentName);

            intent.putExtra(NexBoxIpcKeys.ACCOUNT_USER_ID, mAccounts.userId);

            if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                Slog.v(TAG, "performing bindService to " + componentName);
            }
            int flags = Context.BIND_AUTO_CREATE;

            boolean bound = mContext.bindService(intent, this, flags);
            synchronized (this) { mBound = bound; }
            if (mClosed) { unbind(); return Failure.SESSION_CLOSED; }
            if (!bound) {
                if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "bindService to " + componentName + " failed");
                }
                return Failure.BIND_REJECTED;
            }

            Slog.dLazy(TAG, () -> diagnostic("authenticatorBindAccepted"));
            return null;
        }
    }

    final class RemoveAccountSession extends Session {
        final Account mAccount;
        private final Removal mRemoval;
        public RemoveAccountSession(BUserAccounts accounts, IAccountManagerResponse response,
                                    Account account, boolean expectActivityLaunch, Removal removal) {
            super(Operation.REMOVE_ACCOUNT, accounts, response, account.type, expectActivityLaunch,
                    true , account.name,
                    false );
            mAccount = account;
            mRemoval = removal;
        }

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", removeAccount"
                    + ", user=" + mAccounts.userId;
        }

        @Override
        public void run() throws RemoteException {
            mAuthenticator.getAccountRemovalAllowed(this, mAccount);
        }

        @Override
        public void onResult(Bundle result) {
            if (!isActive()) return;
            if (result != null && result.containsKey(AccountManager.KEY_BOOLEAN_RESULT)
                    && !result.containsKey(AccountManager.KEY_INTENT)) {
                final boolean removalAllowed = result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT);
                if (removalAllowed) {
                    mRemoval.remove(mAccounts, mAccount);
                }
                IAccountManagerResponse response = getResponseAndClose();
                if (response != null) {
                    if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
                    try {
                        response.onResult(result);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error calling onResult()", e);
                    }
                }
            }
            super.onResult(result);
        }
    }

    final class GetAccountsByTypeAndFeatureSession extends Session {
        private final String[] mFeatures;
        private volatile Account[] mAccountsOfType = null;
        private volatile ArrayList<Account> mAccountsWithFeatures = null;
        private volatile int mCurrentAccount = 0;
        private final int mUserId;
        private final String mPackageName;
        private final boolean mIncludeManagedNotVisible;

        public GetAccountsByTypeAndFeatureSession(
                Operation operation,
                BUserAccounts accounts,
                IAccountManagerResponse response,
                String type,
                String[] features,
                int userId,
                String packageName,
                boolean includeManagedNotVisible) {
            super(operation, accounts, response, type, false ,
                    true , null ,
                    false );
            mUserId = userId;
            mFeatures = features;
            mPackageName = packageName;
            mIncludeManagedNotVisible = includeManagedNotVisible;
        }

        @Override
        public void run() throws RemoteException {
            mAccountsOfType = mAccess.getAccountsFromCache(mAccounts, mAccountType, mPackageName, mIncludeManagedNotVisible);

            mAccountsWithFeatures = new ArrayList<>(mAccountsOfType.length);
            mCurrentAccount = 0;

            checkAccount();
        }

        public void checkAccount() {
            if (mCurrentAccount >= mAccountsOfType.length) {
                sendResult();
                return;
            }

            final IAccountAuthenticator accountAuthenticator = mAuthenticator;
            if (accountAuthenticator == null) {

                if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                    Slog.v(TAG, "checkAccount: aborting session since we are no longer"
                            + " connected to the authenticator, " + toDebugString());
                }
                return;
            }
            try {
                accountAuthenticator.hasFeatures(this, mAccountsOfType[mCurrentAccount], mFeatures);
            } catch (RemoteException e) {
                fail(Failure.REQUEST_EXCEPTION, AccountManager.ERROR_CODE_REMOTE_EXCEPTION, "remote exception");
            }
        }

        @Override
        public void onResult(Bundle result) {
            if (!isActive()) return;
            mNumResults++;
            if (result == null) {
                fail(Failure.INVALID_RESPONSE, AccountManager.ERROR_CODE_INVALID_RESPONSE, "null bundle");
                return;
            }
            if (result.getBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)) {
                mAccountsWithFeatures.add(mAccountsOfType[mCurrentAccount]);
            }
            mCurrentAccount++;
            checkAccount();
        }

        public void sendResult() {
            IAccountManagerResponse response = getResponseAndClose();
            if (response != null) {
                try {
                    Account[] accounts = new Account[mAccountsWithFeatures.size()];
                    for (int i = 0; i < accounts.length; i++) {
                        accounts[i] = mAccountsWithFeatures.get(i);
                    }
                    if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, getClass().getSimpleName() + " calling onResult() on response "
                                + response);
                    }
                    Bundle result = new Bundle();
                    result.putParcelableArray(AccountManager.KEY_ACCOUNTS, accounts);
                    response.onResult(result);
                } catch (RemoteException e) {

                    if (Slog.isLoggable(TAG, Log.VERBOSE)) {
                        Slog.v(TAG, "failure while notifying response", e);
                    }
                }
            }
        }

        @Override
        protected String toDebugString(long now) {
            return super.toDebugString(now) + ", getAccountsByTypeAndFeatures"
                    + ", " + (mFeatures != null ? TextUtils.join(",", mFeatures) : null);
        }
    }

    abstract class StartAccountSession extends Session {
        private final String callerPackage;
        private final String callerSigner;
        private boolean sessionIssued;

        StartAccountSession(Operation operation, BUserAccounts accounts, IAccountManagerResponse response, String accountType,
                            String accountName, boolean expectActivity, Bundle options, String signer) {
            super(operation, accounts, response, accountType, expectActivity, true, accountName, false);
            callerPackage = options.getString(AccountManager.KEY_ANDROID_PACKAGE_NAME);
            callerSigner = signer;
        }

        @Override
        public void onResult(Bundle result) {
            if (!isActive()) return;
            if (result == null || result.containsKey(AccountManager.KEY_INTENT)
                    || result.getInt(AccountManager.KEY_ERROR_CODE, 0) > 0) {
                super.onResult(result);
                return;
            }
            Bundle credentials = result.getBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE);
            if (credentials == null) {
                fail(Failure.INVALID_RESPONSE, AccountManager.ERROR_CODE_INVALID_RESPONSE, "Authenticator returned no account session");
                return;
            }
            Bundle outgoing = new Bundle(result);
            synchronized (mAccounts.lock) {
                mAccess.requireAccountStateAvailableLocked(mAccounts);
                if (sessionIssued) return;
                AuthenticatorInfo authenticator = mAuthenticators.getAuthenticators(mAccounts.userId).get(mAccountType);
                if (authenticator == null || !callerSigner.equals(mIdentity.accountSigner(callerPackage, mAccounts.userId))) {
                    fail(Failure.OWNER_CHANGED, AccountManager.ERROR_CODE_CANCELED, "Account session owner changed");
                    return;
                }
                Bundle frozen = credentials.deepCopy();
                frozen.putString(AccountManager.KEY_ACCOUNT_TYPE, mAccountType);
                outgoing.putBundle(AccountManager.KEY_ACCOUNT_SESSION_BUNDLE, mPendingAccountSessions.put(
                        new AccountSessionStore.Entry(mAccounts.userId, mAccounts.userGeneration,
                                mAccounts.googleResetEpoch, callerPackage, callerSigner, mAccountType,
                                authenticator.serviceInfo.packageName,
                                mIdentity.accountSigner(authenticator.serviceInfo.packageName, mAccounts.userId), frozen, mTargetAccount)));
                sessionIssued = true;
            }
            mHandler.postDelayed(mPendingAccountSessions::prune, AccountSessionStore.LIFETIME_MS);
            outgoing.remove(AccountManager.KEY_PASSWORD);
            outgoing.remove(AccountManager.KEY_AUTHTOKEN);
            super.onResult(outgoing);
        }
    }
}
