package com.nexbox.core.fake.service.context.providers;

import android.content.AttributionSource;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.fake.hook.ClassInvocationStub;
import com.nexbox.core.utils.LocalProviderIdentity;
import com.nexbox.core.utils.Slog;

/** Provider routing and calling identity are separate from provider-owned payloads. */
public class ContentProviderStub extends ClassInvocationStub {
    public static final String TAG = "ContentProviderStub";
    public enum Route { SYSTEM, GUEST }

    private static final String SAMSUNG_HEALTH = "com.sec.android.app.shealth";
    private static final String SAMSUNG_ACCOUNT = "com.samsung.android.samsungaccount.accountmanagerprovider";
    private IInterface mBase;
    private volatile IInterface mOnewayBase;
    private IBinder mOnewayProcessToken;
    private Route mRoute = Route.GUEST;
    private String mCallerPackage;
    private String mAuthority;

    public IInterface wrapperSystem(IInterface provider, String authority) {
        String caller = BActivityThread.getAppPackageName();
        return wrap(provider, Route.SYSTEM,
                caller == null || caller.isEmpty() ? NexBoxCore.getHostPkg() : caller, authority);
    }

    public IInterface wrapperGuest(IInterface provider, String callerPackage, String authority) {
        return wrap(provider, Route.GUEST, callerPackage, authority);
    }

    private IInterface wrap(IInterface provider, Route route, String callerPackage, String authority) {
        if (provider == null) throw new IllegalArgumentException("Provider is required");
        mBase = provider;
        mOnewayBase = null;
        mOnewayProcessToken = null;
        mRoute = route;
        mCallerPackage = callerPackage;
        mAuthority = authority;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    public final Route getRoute() { return mRoute; }
    public final IInterface getBaseProvider() { return mBase; }
    public final String getAuthority() { return mAuthority; }
    public final String getCallerPackage() { return mCallerPackage; }

    @Override protected Object getWho() { return mBase; }
    @Override protected void inject(Object baseInvocation, Object proxyInvocation) { }
    @Override protected void onBindMethod() { }
    @Override public boolean isBadEnv() { return false; }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) return invokeBase(method, args);

        Object[] mapped;
        try {
            mapped = mapCallingIdentity(method, args);
        } catch (RuntimeException failure) {
            logFailure("identity", method, args, null, failure);
            throw failure;
        }

        boolean samsungCall = isSamsungAccountIsChildCall(method, args);
        final Object result;
        try {
            logMapping(method, args, mapped);
            result = invokeProvider(method, mapped);
        } catch (Throwable failure) {
            logFailure("dispatch", method, args, mapped, failure);
            // Keep the pre-existing Samsung-only compatibility path separate. Permissions and
            // caller-identity failures are always genuine errors, including on that path.
            if (samsungCall && !isPermissionOrUidFailure(failure)) {
                Slog.w(TAG, "Samsung account child-status compatibility result after provider failure");
                return createSamsungAdultResultBundle();
            }
            throw failure;
        }
        if (samsungCall) {
            Bundle compatible = maybeBypassSamsungChildAccountResult(result);
            if (compatible != null) return compatible;
        }
        return result;
    }

    protected Object[] mapCallingIdentity(Method method, Object[] args) {
        return mRoute == Route.SYSTEM ? ProviderCallIdentity.forSystemProvider(method, args)
                : ProviderCallIdentity.forGuestProvider(method, args);
    }

    private boolean needsGuestOnewayChannel(Method method) {
        return mRoute == Route.GUEST && !(mBase.asBinder() instanceof Binder)
                && BActivityThread.getAppConfig() != null && ProviderCallIdentity.isOneway(method);
    }

    private synchronized IInterface getOnewayProvider() throws android.os.RemoteException {
        AppConfig self = BActivityThread.getAppConfig();
        if (self == null || self.token == null) {
            throw new SecurityException("Guest provider channel requires a configured caller");
        }
        // A recovered server can issue a new lifetime token to the same package/PID. Never
        // send with the previous token's channel just because the provider wrapper survived.
        if (mOnewayBase == null || mOnewayProcessToken != self.token) {
            mOnewayBase = GuestProviderTransport.connect(mBase, self);
            mOnewayProcessToken = self.token;
        }
        return mOnewayBase;
    }

    private Object invokeProvider(Method method, Object[] args) throws Throwable {
        // A local interface performs no Binder hop: it would otherwise inherit an unrelated
        // incoming caller. Deprecated remote-interface bridges also construct AttributionSource
        // locally from Binder.getCallingUid(), so clear for both transport shapes. Restore the
        // route and Binder identity even on failure; remote callbacks have a different PID.
        boolean guestOneway = needsGuestOnewayChannel(method);
        long identity = Binder.clearCallingIdentity();
        try (LocalProviderIdentity ignored = LocalProviderIdentity.enter(mRoute == Route.SYSTEM)) {
            IInterface target = guestOneway ? getOnewayProvider() : mBase;
            if (ProviderCallIdentity.isLegacyGetTypeAsync(method)
                    && args != null && args.length == 2) {
                // Expand the deprecated bridge with this outgoing hop's identity. Do not let
                // its getPackagesForUid lookup choose an unrelated package in a shared UID.
                Method modern;
                try {
                    modern = method.getDeclaringClass().getMethod("getTypeAsync",
                            AttributionSource.class, Uri.class, method.getParameterTypes()[1]);
                } catch (NoSuchMethodException legacyPlatform) {
                    // Android 12/12L/13 only have (Uri, RemoteCallback). Their original
                    // oneway parcel has no AttributionSource and must stay that way.
                    return invokeTarget(target, method, args);
                }
                AppConfig config = mRoute == Route.GUEST ? BActivityThread.getAppConfig() : null;
                int uid = config == null ? NexBoxCore.getHostUid() : config.buid;
                String packageName = config == null ? NexBoxCore.getHostPkg() : config.packageName;
                AttributionSource source = new AttributionSource.Builder(uid)
                        .setPackageName(packageName).build();
                // Apply the same route-specific mapping as an explicitly modern call,
                // including an unspecified PID on remote oneway transports.
                Object[] modernArgs = mapCallingIdentity(modern, new Object[]{source, args[0], args[1]});
                return invokeTarget(target, modern, modernArgs);
            }
            return invokeTarget(target, method, args);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void logMapping(Method method, Object[] before, Object[] after) {
        try {
            if (!Slog.isLoggable(TAG, android.util.Log.DEBUG)) return;
            String original = ProviderCallIdentity.describeCaller(method, before);
            String mapped = ProviderCallIdentity.describeCaller(method, after);
            boolean guestOneway = needsGuestOnewayChannel(method);
            if (original.equals(mapped) && !guestOneway) return;
            Slog.d(TAG, "stage=identityMapped route=" + mRoute + " authority=" + mAuthority
                    + " method=" + method.getName() + " local=" + (mBase.asBinder() instanceof Binder)
                    + " virtualUser=" + BActivityThread.getUserId() + " callerBefore={" + original
                    + "} callerAfter={" + mapped + "}"
                    + (guestOneway ? " transport=authenticatedOneway pid=unspecified" : ""));
        } catch (Throwable ignored) {
            // Identity diagnostics must never replace or block the actual provider operation.
        }
    }

    private Object invokeBase(Method method, Object[] args) throws Throwable {
        return invokeTarget(mBase, method, args);
    }

    private static Object invokeTarget(IInterface target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            throw cause == null ? failure : cause;
        }
    }

    private void logFailure(String stage, Method method, Object[] before, Object[] after, Throwable failure) {
        try {
            if (!Slog.isLoggable(TAG, android.util.Log.WARN)) return;
            Slog.w(TAG, "stage=" + stage + " route=" + mRoute + " authority=" + mAuthority
                    + " uriAuthority=" + uriAuthority(method, before)
                    + " method=" + method.getName() + " signature=" + method.toGenericString() + " callerBefore={"
                    + ProviderCallIdentity.describeCaller(method, before) + "} callerAfter={"
                    + ProviderCallIdentity.describeCaller(method, after) + "}"
                    + describeFailureContext(method, before), failure);
        } catch (Throwable ignored) {
            // A logging/metadata failure must not replace the real provider exception.
        }
    }

    protected String describeFailureContext(Method method, Object[] args) {
        return "";
    }

    private static String uriAuthority(Method method, Object[] args) {
        if (args == null) return null;
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            if (parameters[i] == Uri.class && args[i] instanceof Uri) {
                // Diagnose aliased/unknown signatures without recording URI paths or query data.
                return ((Uri) args[i]).getAuthority();
            }
        }
        return null;
    }

    private boolean isSamsungAccountIsChildCall(Method method, Object[] args) {
        if (mRoute != Route.SYSTEM || !SAMSUNG_ACCOUNT.equals(mAuthority)
                || !SAMSUNG_HEALTH.equals(mCallerPackage) || !"call".equals(method.getName())
                || args == null || args.length != method.getParameterTypes().length) return false;
        ProviderCallPolicy.Layout layout = ProviderCallIdentity.layout(method);
        if (layout.callMethod < 0 || !"isChildAccount".equals(args[layout.callMethod])) return false;
        Object authority = layout.authority >= 0 ? args[layout.authority] : mAuthority;
        return SAMSUNG_ACCOUNT.equals(authority);
    }

    private static boolean isPermissionOrUidFailure(Throwable failure) {
        // This guard only limits the old Samsung exception fallback; it never substitutes a
        // result for a permission failure on the general provider path.
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof SecurityException) return true;
            String message = failure.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("permission") || normalized.contains("does not belong to")
                    || (normalized.contains("uid")
                    && (normalized.contains("match") || normalized.contains("belong")))) return true;
        }
        return false;
    }

    private static Bundle maybeBypassSamsungChildAccountResult(Object result) {
        if (!(result instanceof Bundle)) return createSamsungAdultResultBundle();
        Bundle bundle = (Bundle) result;
        if (!bundle.containsKey("result_code") || bundle.getInt("result_code", -1) != 0) {
            return createSamsungAdultResultBundle();
        }
        return null;
    }

    private static Bundle createSamsungAdultResultBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt("result_code", 0);
        bundle.putString("result_message", "");
        bundle.putBoolean("is_child_account", false);
        bundle.putBoolean("isChildAccount", false);
        bundle.putBoolean("childAccountAccessError", false);
        return bundle;
    }
}
