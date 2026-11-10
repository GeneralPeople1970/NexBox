package com.nexbox.core.core.system.accounts;

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.IAccountAuthenticator;
import android.accounts.IAccountAuthenticatorResponse;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;

import java.util.HashSet;
import java.util.Set;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BAccountManager;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;

/** Authenticates Binder peers before the platform's PID-less, oneway account transactions. */
public final class AccountAuthenticatorTransport extends Binder {
    private static final String TAG = "AccountAuthenticatorTransport";
    static final String AUTHENTICATOR_DESCRIPTOR = "android.accounts.IAccountAuthenticator";
    static final String RESPONSE_DESCRIPTOR = "android.accounts.IAccountAuthenticatorResponse";
    static final String SESSION_DESCRIPTOR = "com.nexbox.core.accounts.Session.v1";
    static final int OPEN_CALLBACK_CHANNEL = IBinder.LAST_CALL_TRANSACTION;
    static final int CHECK_AUTHENTICATOR_SESSION = IBinder.LAST_CALL_TRANSACTION - 1;
    static final int UNWRAP_LOCAL_RESPONSE = IBinder.LAST_CALL_TRANSACTION - 2;
    private static final String TRANSPORT_DESCRIPTOR = "com.nexbox.core.accounts.Authenticator.v1";
    private static final String LOCAL_RESPONSE_DESCRIPTOR = "com.nexbox.core.accounts.LocalResponse.v1";
    private static final int OPEN_AUTHENTICATOR_CHANNEL = IBinder.LAST_CALL_TRANSACTION;
    private static final ThreadLocal<AppConfig> AUTHENTICATED_CALL = new ThreadLocal<>();
    private static Set<Integer> sRequestCodes;

    private final Binder mAuthenticator;
    private final AppConfig mConfiguration;

    private AccountAuthenticatorTransport(Binder authenticator, AppConfig configuration) {
        mAuthenticator = authenticator;
        mConfiguration = configuration;
        attachInterface(null, AUTHENTICATOR_DESCRIPTOR);
    }

    public static IBinder export(IBinder service) throws RemoteException {
        if (service == null || service instanceof AccountAuthenticatorTransport
                || !AUTHENTICATOR_DESCRIPTOR.equals(service.getInterfaceDescriptor())) return service;
        AppConfig configuration = BActivityThread.getAppConfig();
        if (!(service instanceof Binder) || configuration == null || configuration.token == null) {
            throw new IllegalStateException("Authenticator transport requires a local guest service");
        }
        return new AccountAuthenticatorTransport((Binder) service, configuration);
    }

    static IAccountAuthenticator connect(IBinder service, IBinder session) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(TRANSPORT_DESCRIPTOR);
            request.writeStrongBinder(session);
            if (!service.transact(OPEN_AUTHENTICATOR_CHANNEL, request, reply, 0)) {
                throw new RemoteException("Authenticator does not support verified account calls");
            }
            reply.readException();
            IBinder channel = reply.readStrongBinder();
            if (channel == null) throw new RemoteException("Authenticator returned no account channel");
            return IAccountAuthenticator.Stub.asInterface(channel);
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code != OPEN_AUTHENTICATOR_CHANNEL) return mAuthenticator.transact(code, data, reply, flags);
        if ((flags & FLAG_ONEWAY) != 0 || reply == null) {
            throw new SecurityException("Authenticator channel authentication must be synchronous");
        }
        data.enforceInterface(TRANSPORT_DESCRIPTOR);
        IBinder session = data.readStrongBinder();
        if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected authenticator channel data");
        int callerPid = Binder.getCallingPid();
        int callerUid = Binder.getCallingUid();
        IBAccountManagerService accounts = BAccountManager.get().getService();
        if (callerPid <= 0 || callerUid != NexBoxCore.getHostUid() || accounts == null
                || !accounts.isAccountServiceProcess(callerPid, callerUid)
                || !isCurrent(mConfiguration) || !checkSession(session, mConfiguration)) {
            throw new SecurityException("Authenticator channel does not belong to a live account session");
        }
        Set<Integer> requestCodes = requestCodes();
        reply.writeNoException();
        reply.writeStrongBinder(new RequestChannel(mAuthenticator, mConfiguration, session, requestCodes));
        return true;
    }

    public static boolean isAccountManagerCall(int pid, int uid) {
        AppConfig configuration = AUTHENTICATED_CALL.get();
        // clearCallingIdentity and outgoing/local calls must not inherit this permission.
        return pid == 0 && uid == NexBoxCore.getHostUid() && Binder.getCallingPid() == 0
                && Binder.getCallingUid() == uid && isCurrent(configuration);
    }

    private static boolean isCurrent(AppConfig configuration) {
        return configuration != null && configuration.token != null
                && configuration == BActivityThread.getAppConfig();
    }

    private static boolean checkSession(IBinder session, AppConfig configuration) throws RemoteException {
        if (session == null) return false;
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(SESSION_DESCRIPTOR);
            request.writeStrongBinder(configuration.token);
            if (!session.transact(CHECK_AUTHENTICATOR_SESSION, request, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private static synchronized Set<Integer> requestCodes() {
        if (sRequestCodes != null) return sRequestCodes;
        Set<Integer> codes = new HashSet<>();
        // Every listed platform method starts with IAccountAuthenticatorResponse. Do not
        // reinterpret unknown OEM transactions, or guess transaction numbers from an SDK.
        for (String name : new String[]{"addAccount", "confirmCredentials", "getAuthToken",
                "getAuthTokenLabel", "updateCredentials", "editProperties", "hasFeatures",
                "getAccountRemovalAllowed", "getAccountCredentialsForCloning", "addAccountFromCredentials",
                "startAddAccountSession", "startUpdateCredentialsSession", "finishSession",
                "isCredentialsUpdateSuggested"}) {
            try {
                java.lang.reflect.Field field = IAccountAuthenticator.Stub.class.getDeclaredField("TRANSACTION_" + name);
                field.setAccessible(true);
                int code = field.getInt(null);
                if (code < FIRST_CALL_TRANSACTION || code >= UNWRAP_LOCAL_RESPONSE) {
                    throw new IllegalStateException("Invalid authenticator transaction: " + name);
                }
                codes.add(code);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Authenticator transaction is unavailable: " + name, failure);
            }
        }
        sRequestCodes = codes;
        return codes;
    }

    private static final class RequestChannel extends Binder {
        private final Binder authenticator;
        private final AppConfig configuration;
        private final IBinder session;
        private final Set<Integer> requestCodes;

        RequestChannel(Binder authenticator, AppConfig configuration, IBinder session, Set<Integer> requestCodes) {
            this.authenticator = authenticator;
            this.configuration = configuration;
            this.session = session;
            this.requestCodes = requestCodes;
            attachInterface(null, AUTHENTICATOR_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if ((flags & FLAG_ONEWAY) == 0 || Binder.getCallingPid() != 0
                    || Binder.getCallingUid() != NexBoxCore.getHostUid() || !requestCodes.contains(code)) {
                throw new SecurityException("Invalid authenticator channel transaction");
            }
            if (!isCurrent(configuration) || !checkSession(session, configuration)) return true;
            data.enforceInterface(AUTHENTICATOR_DESCRIPTOR);
            IBinder response = data.readStrongBinder();
            if (!session.equals(response)) throw new SecurityException("Account response belongs to another session");

            Parcel forwarded = Parcel.obtain();
            AppConfig previous = AUTHENTICATED_CALL.get();
            try {
                forwarded.writeInterfaceToken(AUTHENTICATOR_DESCRIPTOR);
                forwarded.writeStrongBinder(new LocalResponse(new ResponseProxy(session)));
                // Copy the remaining typed arguments without unmarshalling credentials or
                // replacing bytes/strings. appendFrom preserves all embedded Binder objects.
                forwarded.appendFrom(data, data.dataPosition(), data.dataAvail());
                forwarded.setDataPosition(0);
                AUTHENTICATED_CALL.set(configuration);
                if (!authenticator.transact(code, forwarded, reply, flags)) {
                    new ResponseProxy(session).onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                            "Authenticator did not handle the account request");
                }
                return true;
            } catch (RemoteException | RuntimeException failure) {
                Slog.w(TAG, "stage=dispatchFailed userId=" + configuration.userId
                        + " failure=" + failure.getClass().getSimpleName());
                try {
                    new ResponseProxy(session).onError(AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
                            "Authenticator request failed");
                } catch (RemoteException | RuntimeException unavailable) {
                    // A closed/dead session cannot accept another failure callback.
                }
                throw failure;
            } finally {
                if (previous == null) AUTHENTICATED_CALL.remove();
                else AUTHENTICATED_CALL.set(previous);
                forwarded.recycle();
            }
        }
    }

    public static void prepareActivityIntent(Intent intent) {
        if (!NexBoxCore.get().isGuestProcess() || intent == null
                || !intent.hasExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)) return;
        // Keep other Parcelable response types on the existing instanceof rejection path.
        Object value = BundleCompat.getParcelable(intent,
                AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, Parcelable.class);
        if (!(value instanceof AccountAuthenticatorResponse)) return;
        AccountAuthenticatorResponse response = (AccountAuthenticatorResponse) value;
        Parcel parcel = Parcel.obtain();
        try {
            response.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            IBinder original = parcel.readStrongBinder();
            if (original == null) return;
            parcel.setDataSize(0);
            parcel.setDataPosition(0);
            parcel.writeStrongBinder(new LocalResponse(new ResponseProxy(original)));
            parcel.setDataPosition(0);
            intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
                    AccountAuthenticatorResponse.CREATOR.createFromParcel(parcel));
        } finally {
            parcel.recycle();
        }
    }

    private static final class ResponseProxy implements IAccountAuthenticatorResponse {
        private final IBinder original;
        private IAccountAuthenticatorResponse target;

        ResponseProxy(IBinder original) { this.original = original; }

        @Override public IBinder asBinder() {
            // Activity intents must carry the guarded export, never a channel authenticated
            // for another process. The receiving authenticator UI opens its own channel.
            return original;
        }

        private synchronized IAccountAuthenticatorResponse target() throws RemoteException {
            if (target != null) return target;
            AppConfig configuration = BActivityThread.getAppConfig();
            if (!isCurrent(configuration)) throw new RemoteException("Account callback process is unavailable");
            IBinder export = unwrap(original);
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(SESSION_DESCRIPTOR);
                request.writeStrongBinder(configuration.token);
                if (!export.transact(OPEN_CALLBACK_CHANNEL, request, reply, 0)) {
                    // Authenticator-internal responses retain their own platform behavior.
                    target = IAccountAuthenticatorResponse.Stub.asInterface(export);
                } else {
                    reply.readException();
                    IBinder channel = reply.readStrongBinder();
                    if (channel == null) throw new RemoteException("Account callback session has expired");
                    target = IAccountAuthenticatorResponse.Stub.asInterface(channel);
                }
                return target;
            } finally {
                request.recycle();
                reply.recycle();
            }
        }

        @Override public void onResult(Bundle value) throws RemoteException { target().onResult(value); }
        @Override public void onRequestContinued() throws RemoteException { target().onRequestContinued(); }
        @Override public void onError(int code, String message) throws RemoteException { target().onError(code, message); }
    }

    private static IBinder unwrap(IBinder response) throws RemoteException {
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(LOCAL_RESPONSE_DESCRIPTOR);
            if (!response.transact(UNWRAP_LOCAL_RESPONSE, request, reply, 0)) return response;
            reply.readException();
            IBinder original = reply.readStrongBinder();
            if (original == null) throw new RemoteException("Account response export is unavailable");
            return original;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    private static final class LocalResponse extends IAccountAuthenticatorResponse.Stub {
        private final ResponseProxy proxy;

        LocalResponse(ResponseProxy proxy) {
            this.proxy = proxy;
            attachInterface(proxy, RESPONSE_DESCRIPTOR);
        }

        @Override public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == UNWRAP_LOCAL_RESPONSE) {
                if ((flags & FLAG_ONEWAY) != 0 || reply == null) {
                    throw new SecurityException("Account response resolution must be synchronous");
                }
                data.enforceInterface(LOCAL_RESPONSE_DESCRIPTOR);
                if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected response resolution data");
                reply.writeNoException();
                reply.writeStrongBinder(proxy.original);
                return true;
            }
            if (code >= FIRST_CALL_TRANSACTION && code <= LAST_CALL_TRANSACTION
                    && Binder.getCallingPid() != Process.myPid() && AUTHENTICATED_CALL.get() == null) {
                throw new SecurityException("Remote account callbacks must authenticate their own process");
            }
            return super.onTransact(code, data, reply, flags);
        }

        @Override public void onResult(Bundle value) throws RemoteException { proxy.onResult(value); }
        @Override public void onRequestContinued() throws RemoteException { proxy.onRequestContinued(); }
        @Override public void onError(int code, String message) throws RemoteException { proxy.onError(code, message); }
    }
}
