package com.nexbox.core.fake.hook;

import com.nexbox.core.utils.Slog;

import android.text.TextUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.nexbox.core.utils.MethodParameterUtils;


public abstract class ClassInvocationStub implements InvocationHandler, IInjectHook {
    public static final String TAG = ClassInvocationStub.class.getSimpleName();

    private final Map<String, MethodHook> mMethodHookMap = new HashMap<>();
    private Object mBase;
    private Object mProxyInvocation;
    private boolean onlyProxy;

    protected abstract Object getWho();

    protected abstract void inject(Object baseInvocation, Object proxyInvocation);

    protected void onBindMethod() {

    }

    protected Object getProxyInvocation() {
        return mProxyInvocation;
    }

    protected Object getBase() {
        return mBase;
    }

    protected void onlyProxy(boolean o) {
        onlyProxy = o;
    }

    @Override
    public void injectHook() {
        mBase = getWho();
        
        if (mBase == null) {
            return;
        }
        mProxyInvocation = Proxy.newProxyInstance(mBase.getClass().getClassLoader(), MethodParameterUtils.getAllInterface(mBase.getClass()), this);
        if (!onlyProxy) {
            inject(mBase, mProxyInvocation);
        }

        onBindMethod();
        Class<?>[] declaredClasses = this.getClass().getDeclaredClasses();
        for (Class<?> declaredClass : declaredClasses) {
            initAnnotation(declaredClass);
        }
        ScanClass scanClass = this.getClass().getAnnotation(ScanClass.class);
        if (scanClass != null) {
            for (Class<?> aClass : scanClass.value()) {
                for (Class<?> declaredClass : aClass.getDeclaredClasses()) {
                    initAnnotation(declaredClass);
                }
            }
        }
    }

    private static MethodHook instantiateMethodHook(Class<?> clazz) throws Throwable {
        try {
            // Invoke here, in the original access-check caller class, without forcing accessibility.
            return (MethodHook) clazz.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException missing) {
            throw (InstantiationException) new InstantiationException(clazz.getName()).initCause(missing);
        } catch (InvocationTargetException wrapped) {
            // Keep the original constructor failure reaching the existing logging boundary.
            throw wrapped.getTargetException();
        }
    }

    protected void initAnnotation(Class<?> clazz) {
        ProxyMethod proxyMethod = clazz.getAnnotation(ProxyMethod.class);
        if (proxyMethod != null) {
            final String name = proxyMethod.value();
            if (!TextUtils.isEmpty(name)) {
                try {
                    addMethodHook(name, instantiateMethodHook(clazz));
                } catch (Throwable t) {
                    Slog.e("ClassInvocationStub", "Container operation failed", t);
                }
            }
        }
        ProxyMethods proxyMethods = clazz.getAnnotation(ProxyMethods.class);
        if (proxyMethods != null) {
            String[] value = proxyMethods.value();
            for (String name : value) {
                try {
                    addMethodHook(name, instantiateMethodHook(clazz));
                } catch (Throwable t) {
                    Slog.e("ClassInvocationStub", "Container operation failed", t);
                }
            }
        }
    }

    protected void addMethodHook(MethodHook methodHook) {
        mMethodHookMap.put(methodHook.getMethodName(), methodHook);
    }

    protected void addMethodHook(String name, MethodHook methodHook) {
        mMethodHookMap.put(name, methodHook);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {











        MethodHook methodHook = mMethodHookMap.get(method.getName());
        if (methodHook == null || !methodHook.isEnable()) {
            try {
                return method.invoke(mBase, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }

        try {
            Object result = methodHook.beforeHook(mBase, method, args);
            if (result != null) {
                return result;
            }
            result = methodHook.hook(mBase, method, args);
            result = methodHook.afterHook(result);
            return result;
        } catch (InvocationTargetException failure) {
            // Hook implementations also call reflected platform methods. Keep the same
            // exception type as the direct path without replaying a possibly committed call.
            throw failure.getCause() == null ? failure : failure.getCause();
        }
    }
}
