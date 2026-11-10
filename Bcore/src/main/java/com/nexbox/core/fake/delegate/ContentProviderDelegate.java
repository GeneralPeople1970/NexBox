package com.nexbox.core.fake.delegate;

import android.content.ComponentName;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.IInterface;
import android.text.TextUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadProviderClientRecordJB;
import black.android.app.BRActivityThreadProviderClientRecordP;
import black.android.content.BRContentProviderHolderOreo;
import black.android.providers.BRSettingsContentProviderHolder;
import black.android.providers.BRSettingsGlobal;
import black.android.providers.BRSettingsNameValueCacheOreo;
import black.android.providers.BRSettingsSecure;
import black.android.providers.BRSettingsSystem;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.env.BEnvironment;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.service.context.providers.ContentProviderStub;
import com.nexbox.core.fake.service.context.providers.SystemProviderStub;
import com.nexbox.core.fake.service.context.providers.SettingsProviderStub;
import com.nexbox.core.fake.service.context.providers.OaidProviderStub;
import com.nexbox.core.fake.service.context.providers.VirtualMediaProviderStub;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.Slog;


public class ContentProviderDelegate {
    public static final String TAG = "ContentProviderDelegate";
    private static String resolveCallerPackage() {
        try {
            String appPackageName = BActivityThread.getAppPackageName();
            if (!TextUtils.isEmpty(appPackageName)) {
                return appPackageName;
            }
        } catch (Throwable ignored) {
        }
        return NexBoxCore.getHostPkg();
    }

    private static ContentProviderStub ownStub(IInterface provider) {
        if (provider == null || !Proxy.isProxyClass(provider.getClass())) return null;
        InvocationHandler handler = Proxy.getInvocationHandler(provider);
        return handler instanceof ContentProviderStub ? (ContentProviderStub) handler : null;
    }

    /** Unwrap only our own adapters. A third-party Proxy is still a legitimate base provider. */
    private static IInterface unwrapOwn(IInterface provider) {
        ContentProviderStub stub;
        while ((stub = ownStub(provider)) != null) {
            IInterface base = stub.getBaseProvider();
            if (base == null || base == provider) break;
            provider = base;
        }
        return provider;
    }

    private static boolean adapterMatches(ContentProviderStub stub, String authority,
            ContentProviderStub.Route route) {
        if (route == ContentProviderStub.Route.GUEST) return stub.getClass() == ContentProviderStub.class;
        switch (authority) {
            case "media": return stub instanceof VirtualMediaProviderStub;
            case "settings": return stub instanceof SettingsProviderStub;
            case "com.vivo.vms.IdProvider":
            case "com.meizu.flyme.openidsdk":
            case "cn.nubia.identity": return stub instanceof OaidProviderStub;
            case "telephony": return stub.getClass() == SystemProviderStub.class;
            default: return stub.getClass() == ContentProviderStub.class;
        }
    }

    private static IInterface wrapProvider(IInterface provider, String auth,
            ContentProviderStub.Route route, String guestCaller, ProviderInfo info) {
        if (provider == null) return null;
        String authority = auth == null ? "" : auth;
        // The adapter retains the original consumer for narrow compatibility rules; SYSTEM
        // transport identity is translated independently by ProviderCallIdentity.
        String callerPackage = route == ContentProviderStub.Route.SYSTEM ? resolveCallerPackage() : guestCaller;
        ContentProviderStub existing = ownStub(provider);
        if (route == ContentProviderStub.Route.SYSTEM && (existing instanceof UnavailableGoogleProvider
                || (info != null && com.nexbox.core.core.GmsCompat.isCorePackage(info.packageName)))) {
            if (existing instanceof UnavailableGoogleProvider) return provider;
            return new UnavailableGoogleProvider().wrapperSystem(unwrapOwn(provider), authority);
        }
        if (existing != null && existing.getRoute() == route
                && Objects.equals(existing.getCallerPackage(), callerPackage)
                && Objects.equals(existing.getAuthority(), authority)
                && adapterMatches(existing, authority, route)) return provider;

        IInterface base = unwrapOwn(provider);
        if (route == ContentProviderStub.Route.GUEST) {
            return new ContentProviderStub().wrapperGuest(base, callerPackage, authority);
        }
        switch (authority) {
            case "media":
                return new VirtualMediaProviderStub().wrapperSystem(base, authority);
            case "settings":
                return new SettingsProviderStub().wrapperSystem(base, authority);
            case "com.vivo.vms.IdProvider":
            case "com.meizu.flyme.openidsdk":
            case "cn.nubia.identity":
                return new OaidProviderStub(authority).wrapperSystem(base, authority);
            case "telephony":
                return new SystemProviderStub().wrapperSystem(base, authority);
            default:
                return new ContentProviderStub().wrapperSystem(base, authority);
        }
    }

    private static final class UnavailableGoogleProvider extends ContentProviderStub {
        @Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("asBinder".equals(method.getName())) return getBaseProvider().asBinder();
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "UnavailableProfileGoogleProvider";
            }
            throw new SecurityException("Google provider must belong to this virtual profile");
        }
    }

    /** Compatibility entry point; source comes from the holder, never an authority substring. */
    public static void update(Object holder, String auth) {
        if (holder == null) return;
        IInterface provider = BRContentProviderHolderOreo.get(holder).provider();
        ContentProviderStub.Route route = cachedRoute(provider, BRContentProviderHolderOreo.get(holder).info(), auth);
        if (route != null) update(holder, auth, route, resolveCallerPackage());
    }

    public static void updateSystem(Object holder, String authority) {
        update(holder, authority, ContentProviderStub.Route.SYSTEM, null);
    }

    public static void updateGuest(Object holder, String authority, String callerPackage) {
        update(holder, authority, ContentProviderStub.Route.GUEST, callerPackage);
    }

    /** Close the cache window before the next provider's onCreate can call this local provider. */
    public static void onGuestProviderInstalled(Object holder, ProviderInfo expected) {
        if (holder == null || expected == null) return;
        ProviderInfo installed = BRContentProviderHolderOreo.get(holder).info();
        if (!sameProvider(expected, installed)) return;
        IInterface original = BRContentProviderHolderOreo.get(holder).provider();
        if (original == null) return;
        String[] authorities = TextUtils.isEmpty(expected.authority) ? new String[0] : expected.authority.split(";");
        String authority = authorities.length == 0 ? "" : authorities[0];
        IInterface wrapped = wrapProvider(original, authority, ContentProviderStub.Route.GUEST, resolveCallerPackage(), expected);
        IBinder binder = original.asBinder();
        BRContentProviderHolderOreo.get(holder)._set_provider(wrapped);
        for (Object record : providerRecords()) {
            if (record == null) continue;
            IInterface cached = BRActivityThreadProviderClientRecordP.get(record).mProvider();
            if (cached == null || cached.asBinder() != binder) continue;
            BRActivityThreadProviderClientRecordP.get(record)._set_mProvider(wrapped);
            Object cachedHolder = BRActivityThreadProviderClientRecordJB.get(record).mHolder();
            if (cachedHolder != null) BRContentProviderHolderOreo.get(cachedHolder)._set_provider(wrapped);
        }
    }

    private static void update(Object holder, String authority, ContentProviderStub.Route route,
            String callerPackage) {
        if (holder == null) return;
        IInterface provider = BRContentProviderHolderOreo.get(holder).provider();
        if (provider == null) return;
        BRContentProviderHolderOreo.get(holder)._set_provider(wrapProvider(provider, authority, route, callerPackage,
                BRContentProviderHolderOreo.get(holder).info()));
    }

    private static ContentProviderStub.Route cachedRoute(IInterface provider, ProviderInfo info, String authority) {
        ContentProviderStub stub = ownStub(provider);
        if (stub != null) return stub.getRoute(); // Fresh acquisition explicitly recorded its backend.
        if (info == null || info.applicationInfo == null || TextUtils.isEmpty(info.packageName)
                || TextUtils.isEmpty(info.name)) return null;
        String dataDir = info.applicationInfo.dataDir;
        String guestDataDir = BEnvironment.getDataDir(info.packageName, BActivityThread.getUserId()).getAbsolutePath();
        if (ProviderRoutePolicy.sameDataDirectory(dataDir, guestDataDir)) return ContentProviderStub.Route.GUEST;
        // An identical package/class or APK path can exist on both sides. Confirm the actual
        // host data directory before giving an unmarked cached binder a host caller identity.
        // Virtual media deliberately presents the public authority "media" while its actual
        // component is our private media provider. Confirm the component, not that public alias.
        ProviderInfo host = readHostProviderInfo(new ComponentName(info.packageName, info.name));
        return sameProvider(info, host) ? ContentProviderStub.Route.SYSTEM : null;
    }

    private static ProviderInfo readHostProviderInfo(ComponentName component) {
        try {
            IInterface current = BRActivityThread.get().sPackageManager();
            if (current == null) throw new IllegalStateException("Package service unavailable");
            // Our PackageManager proxy does not intercept asBinder(): it returns its original
            // system binder. Rebuild the interface instead of using a guest-first cached PM.
            IBinder binder = current.asBinder();
            if (binder == null || binder instanceof BinderInvocationStub) {
                throw new IllegalStateException("Unconfirmed host package-service binder");
            }
            Method asInterface = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object host = asInterface.invoke(null, binder);
            if (host == null) throw new IllegalStateException("Host package service unavailable");
            for (Method method : host.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"getProviderInfo".equals(method.getName()) || parameters.length != 3
                        || parameters[0] != ComponentName.class || parameters[2] != int.class
                        || (parameters[1] != int.class && parameters[1] != long.class)
                        || method.getReturnType() != ProviderInfo.class) continue;
                Object flags;
                if (parameters[1] == long.class) flags = 0L;
                else flags = 0;
                method.setAccessible(true);
                return (ProviderInfo) method.invoke(host, component, flags, NexBoxCore.getHostUserId());
            }
            throw new NoSuchMethodException("IPackageManager.getProviderInfo");
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Host provider metadata read failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Host provider metadata API unavailable", failure);
        }
    }

    private static boolean sameProvider(ProviderInfo first, ProviderInfo second) {
        return first != null && second != null && first.applicationInfo != null && second.applicationInfo != null
                && Objects.equals(first.packageName, second.packageName) && Objects.equals(first.name, second.name)
                && ProviderRoutePolicy.sameDataDirectory(first.applicationInfo.dataDir, second.applicationInfo.dataDir);
    }

    private static List<Object> providerRecords() {
        Map<?, ?> map = BRActivityThread.get(NexBoxCore.mainThread()).mProviderMap();
        if (map == null) return Collections.emptyList();
        synchronized (map) {
            return new ArrayList<>(map.values());
        }
    }

    /** A same-process request may reuse an already published local holder, never a fake one. */
    public static Object findLocalGuestHolder(String authority, ProviderInfo expected) {
        for (Object record : providerRecords()) {
            if (record == null) continue;
            try {
                String[] names = BRActivityThreadProviderClientRecordP.get(record).mNames();
                boolean matches = false;
                if (names != null) for (String name : names) if (Objects.equals(authority, name)) matches = true;
                if (!matches || Reflector.with(record).field("mLocalProvider").get() == null) continue;
                Object holder = BRActivityThreadProviderClientRecordJB.get(record).mHolder();
                if (holder != null && sameProvider(expected, BRContentProviderHolderOreo.get(holder).info())
                        && BRContentProviderHolderOreo.get(holder).provider() != null) return holder;
            } catch (Exception failure) {
                Slog.w(TAG, "action=readLocalProvider authority=" + authority + " error=" + failure.getClass().getSimpleName());
            }
        }
        return null;
    }

    public static void init() {
        try {
            clearSettingProvider();
            NexBoxCore.getContext().getContentResolver().call(Uri.parse("content://settings"), "", null, null);
        } catch (RuntimeException failure) {
            // Cache warm-up is optional. A real provider refusal must not fabricate a result,
            // nor prevent repairing the other already acquired providers in this process.
            Slog.w(TAG, "action=warmSettingsProvider error=" + failure.getClass().getSimpleName());
        }
        for (Object value : providerRecords()) {
            if (value == null) continue;
            String providerName = "";
            try {
                String[] names = BRActivityThreadProviderClientRecordP.get(value).mNames();
                if (names != null) for (String name : names) {
                    if (!TextUtils.isEmpty(name)) { providerName = name; break; }
                }
                if (providerName.isEmpty()) continue;
                IInterface provider = BRActivityThreadProviderClientRecordP.get(value).mProvider();
                if (provider == null) continue;
                Object holder = BRActivityThreadProviderClientRecordJB.get(value).mHolder();
                ProviderInfo info = holder == null ? null : BRContentProviderHolderOreo.get(holder).info();
                ContentProviderStub.Route route = cachedRoute(provider, info, providerName);
                if (route == null) {
                    Slog.w(TAG, "action=wrapCachedProvider authority=" + providerName + " skipped=unconfirmed_backend");
                    continue;
                }
                IInterface wrapped = wrapProvider(provider, providerName, route, resolveCallerPackage(), info);
                BRActivityThreadProviderClientRecordP.get(value)._set_mProvider(wrapped);
                if (holder != null) BRContentProviderHolderOreo.get(holder)._set_provider(wrapped);
                // Keep the framework's complete authority aliases and map/refcount entries.
                // A new binder or lost wrapper is inspected again, even for a known authority.
            } catch (Exception failure) {
                Slog.w(TAG, "action=wrapCachedProvider authority=" + providerName + " error=" + failure.getClass().getSimpleName());
            }
        }
    }

    public static void clearSettingProvider() {
        Object cache;
        cache = BRSettingsSystem.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        cache = BRSettingsSecure.get().sNameValueCache();
        if (cache != null) {
            clearContentProvider(cache);
        }
        if (BRSettingsGlobal.getRealClass() != null) {
            cache = BRSettingsGlobal.get().sNameValueCache();
            if (cache != null) {
                clearContentProvider(cache);
            }
        }
    }

    private static void clearContentProvider(Object cache) {
        Object holder = BRSettingsNameValueCacheOreo.get(cache).mProviderHolder();
        if (holder != null) {
            BRSettingsContentProviderHolder.get(holder)._set_mContentProvider(null);
        }
    }
}
