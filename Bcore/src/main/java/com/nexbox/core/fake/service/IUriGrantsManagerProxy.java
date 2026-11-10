package com.nexbox.core.fake.service;

import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.utils.Reflector;
import com.nexbox.core.utils.compat.BundleCompat;
import com.nexbox.core.utils.compat.ParceledListSliceCompat;

public final class IUriGrantsManagerProxy extends BinderInvocationStub {
    public IUriGrantsManagerProxy() { super(BRServiceManager.get().getService("uri_grants")); }
    @Override protected Object getWho() {
        try { return Class.forName("android.app.IUriGrantsManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, BRServiceManager.get().getService("uri_grants")); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @Override protected void inject(Object base, Object proxy) {
        replaceSystemService("uri_grants");
        try {
            Object singleton = Reflector.on("android.app.UriGrantsManager").field("IUriGrantsManagerSingleton").get();
            Reflector.with(singleton).field("mInstance").set(proxy);
        } catch (Exception e) { throw new IllegalStateException("Cannot install URI grant adapter", e); }
    }
    @Override public boolean isBadEnv() { return false; }
    public static boolean handles(String name) {
        return "takePersistableUriPermission".equals(name) || "releasePersistableUriPermission".equals(name) || "getPersistedUriPermissions".equals(name) || "getUriPermissions".equals(name);
    }
    public static Object handle(String name, Object[] args) throws Exception {
        Bundle request = new Bundle();
        if (name.startsWith("get")) {
            if (args != null && args.length > 1 && Boolean.FALSE.equals(args[1])) return ParceledListSliceCompat.create(new ArrayList<>());
            Bundle result = NexBoxCore.getBActivityManager().getService().documentOperation("listGrants", request);
            ArrayList<?> saved = BundleCompat.getParcelableArrayList(result, "grants");
            ArrayList<UriPermission> permissions = new ArrayList<>();
            // The public parcel format is stable and avoids a hidden constructor dependency.
            if (saved != null) for (Object item : saved) {
                Bundle entry = (Bundle) item;
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.writeParcelable(Uri.parse(entry.getString("uri")), 0); parcel.writeInt(entry.getInt("flags")); parcel.writeLong(entry.getLong("time")); parcel.setDataPosition(0);
                    permissions.add(UriPermission.CREATOR.createFromParcel(parcel));
                } finally { parcel.recycle(); }
            }
            return ParceledListSliceCompat.create(permissions);
        }
        for (int i = 0; i < args.length; i++) if (args[i] instanceof Uri) {
            request.putParcelable("uri", (Uri) args[i]);
            if (i + 1 >= args.length || !(args[i + 1] instanceof Integer)) throw new IllegalArgumentException("Missing URI modes");
            request.putInt("flags", (Integer) args[i + 1]); break;
        }
        NexBoxCore.getBActivityManager().getService().documentOperation(name.startsWith("take") ? "take" : "release", request);
        return null;
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) return this;
        if (NexBoxCore.get().isGuestProcess() && handles(method.getName())) return handle(method.getName(), args);
        if (NexBoxCore.get().isGuestProcess()) throw new SecurityException("Guest cannot manage host URI grants");
        try { return method.invoke(getBase(), args); } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }
    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
        if (NexBoxCore.get().isGuestProcess()) throw new SecurityException("Raw URI grant IPC denied");
        return super.transact(code, data, reply, flags);
    }
}
