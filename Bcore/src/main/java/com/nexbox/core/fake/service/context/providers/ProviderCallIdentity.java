package com.nexbox.core.fake.service.context.providers;

import android.content.AttributionSource;

import java.lang.reflect.Method;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.entity.AppConfig;
import com.nexbox.core.utils.AttributionSourceUtils;

/** Route-specific transport identity; application payloads and attribution-chain tails stay intact. */
public final class ProviderCallIdentity {
    private ProviderCallIdentity() { }

    public static Object[] forSystemProvider(Method method, Object[] args) {
        return ProviderCallPolicy.rewrite(method.getName(), parameterNames(method), args,
                NexBoxCore.getHostPkg(), NexBoxCore.getHostUid(),
                isOneway(method) ? AttributionSourceUtils::copyForOnewayProviderCaller
                        : AttributionSourceUtils::copyForProviderCaller);
    }

    public static Object[] forGuestProvider(Method method, Object[] args) {
        // callingBUid is the process's original launcher, not the sender of this transaction.
        // An outgoing hop belongs to this process, even when running inside an incoming call.
        AppConfig config = BActivityThread.getAppConfig();
        // Host/bootstrap consumers have no virtual identity. The receiving hook also keeps
        // their real Binder UID; never invent an app-id for an unconfigured caller.
        if (config == null) return forSystemProvider(method, args);
        return ProviderCallPolicy.rewriteGuest(method.getName(), parameterNames(method), args,
                config.packageName, config.buid,
                isOneway(method) ? AttributionSourceUtils::copyForGuestOnewayProviderCaller
                        : AttributionSourceUtils::copyForGuestProviderCaller);
    }

    static boolean isOneway(Method method) {
        return ProviderCallPolicy.isOneway(method.getName(), parameterNames(method));
    }

    static boolean isLegacyGetTypeAsync(Method method) {
        return ProviderCallPolicy.isLegacyGetTypeAsync(method.getName(), parameterNames(method));
    }

    static ProviderCallPolicy.Layout layout(Method method) {
        return ProviderCallPolicy.layout(method.getName(), parameterNames(method));
    }

    /** Deliberately excludes URI paths, SQL, method arguments and Bundle contents from diagnostics. */
    static String describeCaller(Method method, Object[] args) {
        ProviderCallPolicy.Layout layout = layout(method);
        if (args == null || args.length != method.getParameterTypes().length || args.length == 0) {
            return "unavailable";
        }
        if (layout.kind == ProviderCallPolicy.Kind.ATTRIBUTION_SOURCE && args[0] instanceof AttributionSource) {
            AttributionSource source = (AttributionSource) args[0];
            return "package=" + source.getPackageName() + ",uid=" + source.getUid();
        }
        if (layout.kind == ProviderCallPolicy.Kind.CALLING_PACKAGE) {
            return "package=" + args[0] + ",uid=implicitBinderCaller";
        }
        return "noKnownIdentitySlot";
    }

    private static String[] parameterNames(Method method) {
        Class<?>[] types = method.getParameterTypes();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return names;
    }
}
