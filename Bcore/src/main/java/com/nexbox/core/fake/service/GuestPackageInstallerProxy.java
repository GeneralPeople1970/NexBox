package com.nexbox.core.fake.service;

import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcelable;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.pm.IBGuestPackageInstaller;
import com.nexbox.core.core.system.pm.IBPackageManagerService;
import com.nexbox.core.utils.compat.BundleCompat;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

/** Uses the running platform's interface types, never a guessed AIDL transaction table. */
final class GuestPackageInstallerProxy implements InvocationHandler {
    private final IBGuestPackageInstaller service;
    private final int sessionId;
    private final LocalBinder binder = new LocalBinder();

    static Object create(Class<?> installerInterface) throws Exception {
        IBPackageManagerService manager = IBPackageManagerService.Stub.asInterface(
                NexBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER));
        if (manager == null) throw new IllegalStateException("Container package manager unavailable");
        IBGuestPackageInstaller service = IBGuestPackageInstaller.Stub.asInterface(manager.getGuestPackageInstaller());
        if (service == null) throw new IllegalStateException("Container installer unavailable");
        return new GuestPackageInstallerProxy(service, 0).proxy(installerInterface);
    }

    private GuestPackageInstallerProxy(IBGuestPackageInstaller service, int sessionId) {
        this.service = service;
        this.sessionId = sessionId;
    }

    private Object proxy(Class<?> contract) {
        if (!contract.isInterface() || !IInterface.class.isAssignableFrom(contract)) {
            throw new UnsupportedOperationException("Unknown package installer interface");
        }
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, this);
        binder.attach((IInterface) proxy, contract.getName());
        return proxy;
    }

    private Bundle call(int id, String operation, Bundle arguments) throws Exception {
        Bundle result = service.call(id, operation, arguments);
        if (result == null) throw new IllegalStateException("Container installer returned no result");
        result.setClassLoader(PackageInstaller.class.getClassLoader());
        return result;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] values) throws Throwable {
        String name = method.getName();
        Object[] args = values == null ? new Object[0] : values;
        if ("asBinder".equals(name)) return binder;
        if ("toString".equals(name)) return "ContainerPackageInstaller(" + sessionId + ")";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return args.length == 1 && proxy == args[0];
        Bundle request = new Bundle();
        if (sessionId == 0) {
            switch (name) {
                case "createSession":
                    if (args.length < 2 || !(args[0] instanceof PackageInstaller.SessionParams) || !(args[1] instanceof String)) break;
                    request.putParcelable("params", (Parcelable) args[0]);
                    request.putString("installer", (String) args[1]);
                    return call(0, "create", request).getInt("id");
                case "openSession":
                    int id = (Integer) args[0];
                    call(id, "open", request);
                    return new GuestPackageInstallerProxy(service, id).proxy(method.getReturnType());
                case "getSessionInfo":
                    return BundleCompat.getParcelable(call((Integer) args[0], "info", request),
                            "info", Parcelable.class);
                case "getMySessions":
                case "getAllSessions":
                    ArrayList<?> infos = BundleCompat.getParcelableArrayList(call(0, "list", request), "infos");
                    return ParceledListSliceCompat.create(infos == null ? new ArrayList<>() : infos);
                case "getStagedSessions":
                    return ParceledListSliceCompat.create(new ArrayList<>());
                case "registerCallback":
                case "unregisterCallback":
                    request.putBinder("callback", ((IInterface) args[0]).asBinder());
                    call(0, "registerCallback".equals(name) ? "register" : "unregister", request);
                    return null;
                case "abandonSession":
                    call((Integer) args[0], "abandon", request);
                    return null;
                case "updateSessionAppIcon":
                    request.putParcelable("icon", (Parcelable) args[1]);
                    call((Integer) args[0], "icon", request);
                    return null;
                case "updateSessionAppLabel":
                    request.putString("label", (String) args[1]);
                    call((Integer) args[0], "label", request);
                    return null;
            }
        } else {
            switch (name) {
                case "setClientProgress":
                case "addClientProgress":
                    request.putFloat("progress", (Float) args[0]);
                    request.putBoolean("add", "addClientProgress".equals(name));
                    call(sessionId, "progress", request);
                    return null;
                case "getNames":
                    return call(sessionId, "names", request).getStringArray("names");
                case "openWrite":
                    request.putString("name", (String) args[0]);
                    request.putLong("offset", (Long) args[1]);
                    request.putLong("length", (Long) args[2]);
                    return BundleCompat.getParcelable(call(sessionId, "write", request), "fd", Parcelable.class);
                case "openRead":
                    request.putString("name", (String) args[0]);
                    return BundleCompat.getParcelable(call(sessionId, "read", request), "fd", Parcelable.class);
                case "removeSplit":
                    request.putString("name", (String) args[0]);
                    call(sessionId, "removeSplit", request);
                    return null;
                case "commit":
                    if (args.length < 1 || args.length > 2 || !(args[0] instanceof IntentSender)
                            || (args.length == 2 && !(args[1] instanceof Boolean))) break;
                    request.putParcelable("receiver", (IntentSender) args[0]);
                    request.putBoolean("transferred", args.length == 2 && (Boolean) args[1]);
                    call(sessionId, "commit", request);
                    return null;
                case "close":
                case "abandon":
                    call(sessionId, name, request);
                    return null;
                case "isMultiPackage":
                case "isStaged":
                    call(sessionId, "info", request);
                    return false;
                case "getParentSessionId":
                    call(sessionId, "info", request);
                    return -1;
                case "getChildSessionIds":
                    call(sessionId, "info", request);
                    return new int[0];
                case "getDataLoaderParams":
                    call(sessionId, "info", request);
                    return null;
            }
        }
        // This also covers uninstall, transfer, preapproval, APEX, rollback,
        // incremental/data-loader writes and future unknown platform methods.
        throw new UnsupportedOperationException("Container PackageInstaller does not support " + name);
    }

    private static final class LocalBinder extends Binder {
        void attach(IInterface owner, String descriptor) { attachInterface(owner, descriptor); }
    }
}
