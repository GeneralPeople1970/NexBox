package com.nexbox.core.fake.service.context.providers;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import black.android.content.BRContentProviderNative;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.am.IBActivityManagerService;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.frameworks.BActivityManager;
import com.nexbox.core.utils.GuestProviderIdentity;
import com.nexbox.core.utils.Slog;

/**
 * Exports a local provider without changing its platform wire format. A synchronous handshake
 * authenticates the sender's actual PID and server-issued process token before issuing a private
 * oneway channel. Async payloads and callbacks still use the platform's FLAG_ONEWAY transactions.
 */
public final class GuestProviderTransport extends Binder {
    private static final String TAG = "GuestProviderTransport";
    private static final String PROVIDER_DESCRIPTOR = "android.content.IContentProvider";
    private static final String CHANNEL_DESCRIPTOR = "com.nexbox.core.GuestProviderTransport.v1";
    private static final int OPEN_ASYNC_CHANNEL = IBinder.LAST_CALL_TRANSACTION;
    // Keep the same binder identity for aliases/repeated acquisitions, without pinning a
    // provider or its export after the framework and all remote clients have released it.
    private static final Map<Binder, WeakReference<GuestProviderTransport>> EXPORTS = new WeakHashMap<>();
    private final Binder provider;

    private GuestProviderTransport(Binder provider) {
        this.provider = provider;
        // Deliberately expose no local interface: all callers must enter the transport boundary.
        attachInterface(null, PROVIDER_DESCRIPTOR);
    }

    public static synchronized IBinder export(IBinder provider) {
        if (provider instanceof GuestProviderTransport) return provider;
        if (!(provider instanceof Binder)) {
            throw new IllegalArgumentException("Only an in-process guest provider can be exported");
        }
        Binder local = (Binder) provider;
        WeakReference<GuestProviderTransport> reference = EXPORTS.get(local);
        GuestProviderTransport transport = reference == null ? null : reference.get();
        if (transport == null) {
            transport = new GuestProviderTransport(local);
            EXPORTS.put(local, new WeakReference<>(transport));
        }
        return transport;
    }

    /** Kept private by ContentProviderStub; its public asBinder always returns the unbound export. */
    static IInterface connect(IInterface provider, AppConfig self) throws RemoteException {
        if (self == null || self.token == null) {
            throw new SecurityException("Guest provider channel requires a configured caller");
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(CHANNEL_DESCRIPTOR);
            request.writeStrongBinder(self.token);
            if (!provider.asBinder().transact(OPEN_ASYNC_CHANNEL, request, reply, 0)) {
                throw new RemoteException("Guest provider does not support authenticated async calls");
            }
            reply.readException();
            IBinder channel = reply.readStrongBinder();
            if (channel == null) throw new RemoteException("Guest provider returned no async channel");
            IInterface result = BRContentProviderNative.get().asInterface(channel);
            if (result == null) throw new RemoteException("Guest provider async interface is unavailable");
            return result;
        } finally {
            request.recycle();
            reply.recycle();
        }
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != OPEN_ASYNC_CHANNEL) return provider.transact(code, data, reply, flags);
        if ((flags & FLAG_ONEWAY) != 0 || reply == null) {
            throw new SecurityException("Provider channel authentication must be synchronous");
        }
        data.enforceInterface(CHANNEL_DESCRIPTOR);
        IBinder token = data.readStrongBinder();
        if (data.dataAvail() != 0) throw new IllegalArgumentException("Unexpected provider channel data");
        int callerPid = Binder.getCallingPid();
        AppConfig caller = resolve(callerPid, token);
        if (caller == null) throw new SecurityException("Unknown or expired guest provider caller");
        // Resolve optional/required platform codes before publishing the channel. A missing
        // required OEM API must fail this synchronous handshake, not silently drop a callback.
        AsyncCodes asyncCodes = AsyncCodes.load();
        reply.writeNoException();
        reply.writeStrongBinder(new OnewayChannel(provider, callerPid, caller, asyncCodes));
        return true;
    }

    private static AppConfig resolve(int callerPid, IBinder token) throws RemoteException {
        if (callerPid <= 0 || token == null) return null;
        IBActivityManagerService service = BActivityManager.get().getService();
        if (service == null) throw new RemoteException("Guest caller registry is unavailable");
        return service.resolveProviderCaller(callerPid, token);
    }

    private static final class OnewayChannel extends Binder {
        private final Binder provider;
        private final int callerPid;
        private final AppConfig caller;
        private final AsyncCodes asyncCodes;

        OnewayChannel(Binder provider, int callerPid, AppConfig caller, AsyncCodes asyncCodes) {
            this.provider = provider;
            this.callerPid = callerPid;
            this.caller = caller;
            this.asyncCodes = asyncCodes;
            attachInterface(null, PROVIDER_DESCRIPTOR);
        }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            if ((flags & FLAG_ONEWAY) == 0 || Binder.getCallingPid() != 0
                    || Binder.getCallingUid() != NexBoxCore.getHostUid() || !asyncCodes.contains(code)) {
                throw new SecurityException("Invalid guest provider async transaction");
            }
            // A UID/PID cache alone survives PID reuse. Recheck the original process token and
            // both live virtual-user generations for every call, without process-start locks.
            AppConfig live = resolve(callerPid, caller.token);
            if (live == null || live.buid != caller.buid || live.userId != caller.userId
                    || !caller.packageName.equals(live.packageName)) {
                Slog.w(TAG, "stage=asyncRejected reason=expired_caller callerPid=" + callerPid);
                throw new SecurityException("Guest provider async caller has expired");
            }
            try (GuestProviderIdentity ignored = GuestProviderIdentity.enter(live.buid)) {
                // Binder.transact on a LOCAL Binder directly invokes onTransact. No new IPC or
                // clearCallingIdentity occurs: framework AttributionSource validation and the
                // provider callback see this scoped UID while the real oneway PID remains 0.
                return provider.transact(code, data, reply, flags);
            }
        }
    }

    /** Read the running framework's transaction numbers, not a guessed OEM/API wire layout. */
    private static final class AsyncCodes {
        private static AsyncCodes cached;
        private final int type = read("GET_TYPE_ASYNC_TRANSACTION", false);
        private final int canonicalize = read("CANONICALIZE_ASYNC_TRANSACTION", false);
        private final int uncanonicalize = read("UNCANONICALIZE_ASYNC_TRANSACTION", false);
        private final int anonymousType = read("GET_TYPE_ANONYMOUS_ASYNC_TRANSACTION", true);

        static synchronized AsyncCodes load() {
            // Do not throw from a class initializer: that would turn an unsupported API into
            // an ExceptionInInitializerError outside Binder's normal exception reply path.
            if (cached == null) cached = new AsyncCodes();
            return cached;
        }

        boolean contains(int code) {
            return code > 0 && (code == type || code == canonicalize || code == uncanonicalize
                    || code == anonymousType);
        }

        private static int read(String name, boolean optional) {
            try {
                java.lang.reflect.Field field = Class.forName(PROVIDER_DESCRIPTOR).getField(name);
                field.setAccessible(true);
                int code = field.getInt(null);
                if (code < FIRST_CALL_TRANSACTION || code >= OPEN_ASYNC_CHANNEL) {
                    throw new IllegalStateException("Invalid provider async transaction: " + name);
                }
                return code;
            } catch (ReflectiveOperationException failure) {
                if (optional && failure instanceof NoSuchFieldException) return -1;
                throw new IllegalStateException("Provider async transaction is unavailable: " + name, failure);
            }
        }
    }
}
