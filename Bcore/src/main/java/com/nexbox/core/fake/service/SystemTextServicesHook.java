package com.nexbox.core.fake.service;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.IInjectHook;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.TextInputDiagnostics;
import com.nexbox.core.utils.TextInputDiagnostics.Component;
import com.nexbox.reflection.utils.MemberCache;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Keep platform FloatingToolbar/TextClassifier implementations and their real callback results. */
public final class SystemTextServicesHook implements IInjectHook {
    private static volatile SystemTextServicesHook installed;
    private final ServiceProxy classifier = new ServiceProxy("textclassification",
            "android.service.textclassifier.ITextClassifierService$Stub", Component.TEXT_CLASSIFIER);
    private final ServiceProxy toolbar = new ServiceProxy("selection_toolbar",
            "android.view.selectiontoolbar.ISelectionToolbarManager$Stub", Component.SELECTION_TOOLBAR);

    @Override
    public void injectHook() {
        install(classifier);
        install(toolbar);
        installed = this;
        fixContext(NexBoxCore.getContext());
        fixContext(BActivityThread.getApplication());
    }

    private static void install(ServiceProxy proxy) {
        try {
            proxy.injectHook();
        } catch (RuntimeException failure) {
            TextInputDiagnostics.failed(proxy.component, "install", failure);
        }
    }

    public static void fixContext(Context context) {
        SystemTextServicesHook hook = installed;
        if (context == null || hook == null || hook.toolbar.getProxyInvocation() == null
                || !NexBoxCore.get().isGuestProcess()) return;
        try {
            // A cached SelectionToolbarManager must not retain an unadapted system service.
            Object manager = context.getSystemService("selection_toolbar");
            if (manager == null) return;
            Field service = MemberCache.forType(manager.getClass()).field("mService");
            service.setAccessible(true);
            service.set(manager, hook.toolbar.getProxyInvocation());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            TextInputDiagnostics.failed(Component.SELECTION_TOOLBAR, "bindCachedManager", failure);
        }
        // Android 12+ TextClassificationManager creates SystemTextClassifier instances on demand
        // from ServiceManager. Do not replace guest-provided custom classifiers or session factories.
    }

    @Override
    public boolean isBadEnv() {
        return classifier.isBadEnv() || toolbar.isBadEnv();
    }

    private static final class ServiceProxy extends BinderInvocationStub {
        private final String serviceName;
        private final String stubName;
        private final Component component;
        private final IBinder platformBinder;
        private IInterface platformService;

        ServiceProxy(String serviceName, String stubName, Component component) {
            this(serviceName, stubName, component, originalBinder(serviceName));
        }

        private ServiceProxy(String serviceName, String stubName, Component component, IBinder binder) {
            super(binder);
            this.serviceName = serviceName;
            this.stubName = stubName;
            this.component = component;
            platformBinder = binder;
        }

        private static IBinder originalBinder(String name) {
            IBinder binder = BRServiceManager.get().getService(name);
            while (binder instanceof ServiceProxy) binder = ((ServiceProxy) binder).platformBinder;
            return binder;
        }

        @Override
        protected Object getWho() {
            if (platformBinder == null) {
                Slog.dLazy(TextInputDiagnostics.TAG,
                        () -> "component=" + component + " stage=unavailable reason=serviceMissing");
                return null;
            }
            if (platformService == null) {
                try {
                    platformService = (IInterface) Class.forName(stubName)
                            .getMethod("asInterface", IBinder.class).invoke(null, platformBinder);
                    if (platformService == null) throw new IllegalStateException("System text interface is null");
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("System text service interface unavailable", failure);
                }
            }
            return platformService;
        }

        @Override
        protected void inject(Object baseInvocation, Object proxyInvocation) {
            replaceSystemService(serviceName);
            Slog.dLazy(TextInputDiagnostics.TAG,
                    () -> "component=" + component + " stage=installed route=SYSTEM");
        }

        @Override
        public Object getProxyInvocation() { return super.getProxyInvocation(); }

        @Override
        public boolean isBadEnv() {
            return platformBinder != null && (getProxyInvocation() == null
                    || BRServiceManager.get().getService(serviceName) != this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("asBinder".equals(method.getName())) return platformBinder;
            if (!NexBoxCore.get().isGuestProcess() || BActivityThread.getAppConfig() == null
                    || method.getDeclaringClass() == Object.class) return invokePlatform(method, args);
            return TextInputDiagnostics.call(component, method.getName(), () -> {
                Object[] mapped = component == Component.TEXT_CLASSIFIER
                        ? TextClassifierIdentity.rewrite(method, args,
                                NexBoxCore.getHostPkg(), NexBoxCore.getHostUserId())
                        : TextInputCallPolicy.toolbarUser(method.getName(),
                                TextInputCallPolicy.parameterNames(method), args, NexBoxCore.getHostUserId());
                long identity = Binder.clearCallingIdentity();
                try {
                    return invokePlatform(method, mapped);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            });
        }

        private Object invokePlatform(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(platformService, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }
}
