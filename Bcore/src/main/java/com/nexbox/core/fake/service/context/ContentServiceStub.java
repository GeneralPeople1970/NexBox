package com.nexbox.core.fake.service.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import android.net.Uri;

import black.android.content.BRIContentServiceStub;
import black.android.os.BRServiceManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.media.NexBoxMediaContract;
import com.nexbox.core.utils.MethodParameterUtils;


public class ContentServiceStub extends BinderInvocationStub {

    public ContentServiceStub() {
        super(BRServiceManager.get().getService("content"));
    }

    @Override
    protected Object getWho() {
        return BRIContentServiceStub.get().asInterface(BRServiceManager.get().getService("content"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("content");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("registerContentObserver")
    public static class RegisterContentObserver extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!rewriteObserverUris(args)) return 0;
            if (args.length > 3 && args[3] instanceof Integer) args[3] = NexBoxCore.getHostUserId();
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("notifyChange")
    public static class NotifyChange extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!rewriteObserverUris(args)) return 0;
            if (args.length > 4 && args[4] instanceof Integer) args[4] = NexBoxCore.getHostUserId();
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    private static boolean rewriteObserverUris(Object[] args) {
        if (args == null) return false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Uri) {
                Uri uri = routeObserverUri((Uri) args[i]);
                if (uri == null) return false;
                args[i] = uri;
                return true;
            }
            if (args[i] instanceof Uri[]) {
                ArrayList<Uri> allowed = new ArrayList<>();
                for (Uri uri : (Uri[]) args[i]) {
                    Uri routed = routeObserverUri(uri);
                    if (routed != null) allowed.add(routed);
                }
                args[i] = allowed.toArray(new Uri[0]);
                return !allowed.isEmpty();
            }
        }
        return false;
    }

    private static Uri routeObserverUri(Uri uri) {
        if (uri == null) return null;
        switch (ContentObserverRouting.destination(uri.getScheme(), uri.getAuthority(), NexBoxCore.getHostPkg(),
                NexBoxMediaContract.PUBLIC_AUTHORITY, NexBoxMediaContract.getPrivateAuthority())) {
            case DIAGNOSTICS:
                return uri;
            case MEDIA:
                return NexBoxMediaContract.toPrivateUri(uri, NexBoxMediaContract.SHARED_USER_ID);
            default:
                return null;
        }
    }
}
