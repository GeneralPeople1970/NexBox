package com.nexbox.core.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import com.nexbox.core.fake.device.DeviceSpoofManager;
import com.nexbox.core.fake.device.OaidValue;
import com.nexbox.core.fake.hook.BinderInvocationStub;

/** Wraps known, existing OEM OAID services without claiming support for missing services. */
public final class OaidServiceBinder extends BinderInvocationStub {
    private static final String HUAWEI = "com.uodis.opendevice.aidl.OpenDeviceIdentifierService";
    private static final String SAMSUNG = "com.samsung.android.deviceidservice.IDeviceIdService";
    private static final String HEYTAP = "com.heytap.openid.IOpenID";
    private final String descriptor;

    private OaidServiceBinder(IBinder base, String descriptor) {
        super(base);
        this.descriptor = descriptor;
    }

    public static boolean matches(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        String pkg = component == null ? intent.getPackage() : component.getPackageName();
        if (pkg != null && !isOaidPackage(pkg)) return false;
        return "com.uodis.opendevice.OPENIDS_SERVICE".equals(intent.getAction())
                || "action.com.heytap.openid.OPEN_ID_SERVICE".equals(intent.getAction())
                || (component != null && ("com.samsung.android.deviceidservice.DeviceIdService".equals(component.getClassName())
                    || "com.heytap.openid.IdentifyService".equals(component.getClassName())));
    }

    private static boolean isOaidPackage(String pkg) {
        return "com.huawei.hwid".equals(pkg) || "com.huawei.hwid.tv".equals(pkg)
                || "com.huawei.hms".equals(pkg) || "com.samsung.android.deviceidservice".equals(pkg)
                || "com.heytap.openid".equals(pkg);
    }

    static final class RequestInfo {
        final String kind;
        final String requestedPackage;
        final String resolvedComponent;
        final String resolution;
        final String details;

        private RequestInfo(Intent intent, ResolveInfo resolved, Boolean simulated) {
            ComponentName component = intent.getComponent();
            String pkg = component == null ? intent.getPackage() : component.getPackageName();
            kind = "com.uodis.opendevice.OPENIDS_SERVICE".equals(intent.getAction()) ? "HUAWEI"
                    : ("action.com.heytap.openid.OPEN_ID_SERVICE".equals(intent.getAction())
                    || (component != null && "com.heytap.openid.IdentifyService".equals(component.getClassName())))
                    ? "HEYTAP" : "SAMSUNG";
            requestedPackage = identifier(pkg);
            ServiceInfo service = resolved == null ? null : resolved.serviceInfo;
            resolution = service == null ? "unresolved" : "resolved";
            resolvedComponent = service == null ? "absent"
                    : identifier(service.packageName) + "/" + identifier(service.name);
            details = " oaidKind=" + kind + " requestedPackage=" + requestedPackage
                    + " hostResolution=" + resolution + " resolvedComponent=" + resolvedComponent
                    + " deviceSimulation=" + (simulated == null ? "unknown" : simulated)
                    + (service == null ? "" : " serviceEnabled=" + service.enabled
                    + " serviceExported=" + service.exported + " servicePermission=" + identifier(service.permission));
        }

        String key() { return kind + '/' + requestedPackage + '/' + resolvedComponent; }
    }

    static RequestInfo describe(Intent intent, ResolveInfo resolved) {
        Boolean simulated = null;
        try {
            simulated = DeviceSpoofManager.isCurrentProcessEnabled();
        } catch (RuntimeException | LinkageError unavailable) {
            // A missing diagnostic snapshot is not a reason to change a system bind request.
        }
        return new RequestInfo(intent, resolved, simulated);
    }

    private static String identifier(String value) {
        if (value == null || value.isEmpty()) return "absent";
        String bounded = value.length() > 180 ? value.substring(0, 180) : value;
        return bounded.replaceAll("[^A-Za-z0-9_.$/:-]", "_");
    }

    public static IBinder wrap(ComponentName component, IBinder binder) throws RemoteException {
        if (binder == null || binder instanceof OaidServiceBinder || !DeviceSpoofManager.isCurrentProcessEnabled()) return binder;
        if (component == null || !isOaidPackage(component.getPackageName())) return binder;
        String descriptor = binder.getInterfaceDescriptor();
        if (!HUAWEI.equals(descriptor) && !SAMSUNG.equals(descriptor) && !HEYTAP.equals(descriptor)) return binder;
        return new OaidServiceBinder(binder, descriptor);
    }

    @Override protected Object getWho() { return null; }
    @Override protected void inject(Object base, Object proxy) {}
    @Override public boolean isBadEnv() { return false; }
    @Override public IInterface queryLocalInterface(String descriptor) { return null; }

    @Override
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // In these three contracts the first transaction returns OAID (or a typed HeyTap ID).
        boolean replace = code == IBinder.FIRST_CALL_TRANSACTION && reply != null
                && (flags & IBinder.FLAG_ONEWAY) == 0 && DeviceSpoofManager.isCurrentProcessEnabled()
                && (!HEYTAP.equals(descriptor) || isHeytapOaid(data));
        boolean handled = super.transact(code, data, reply, flags);
        if (!replace || !handled) return handled;

        int position = reply.dataPosition();
        String original;
        try {
            reply.setDataPosition(0);
            reply.readException();
            original = reply.readString();
            if (reply.dataAvail() != 0) return handled; // Unknown/new response schema: preserve it.
        } catch (RuntimeException e) {
            // Preserve vendor errors, permissions, and disabled/unsupported states for the caller.
            return handled;
        } finally {
            reply.setDataPosition(position);
        }
        String replacement = OaidValue.replace(original);
        if (original == null || original.equals(replacement)) return handled;
        reply.setDataSize(0);
        reply.setDataPosition(0);
        reply.writeNoException();
        reply.writeString(replacement);
        reply.setDataPosition(0);
        return handled;
    }

    private static boolean isHeytapOaid(Parcel data) {
        int position = data.dataPosition();
        try {
            data.setDataPosition(0);
            data.enforceInterface(HEYTAP);
            data.readString(); // Caller package; must not be replaced by an identifier.
            data.readString(); // Caller signature; preserve the OEM's validation.
            return "OUID".equals(data.readString());
        } catch (RuntimeException e) {
            return false;
        } finally {
            data.setDataPosition(position);
        }
    }
}
