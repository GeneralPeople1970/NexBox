package com.nexbox.core.fake.service;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.IAccountManagerResponse;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import black.android.accounts.BRIAccountManagerStub;
import black.android.accounts.BRAccountManager;
import black.android.os.BRServiceManager;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.frameworks.BAccountManager;
import com.nexbox.core.fake.hook.BinderInvocationStub;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.hook.ProxyMethods;
import com.nexbox.core.utils.Slog;


public class IAccountManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IAccountManagerProxy";
    private static volatile IAccountManagerProxy installed;
    private static final Map<Object, Boolean> CACHED_MANAGERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> VIRTUAL_METHODS = virtualMethods();
    private final IInterface platformService;

    public IAccountManagerProxy() {
        this(originalBinder());
    }

    private IAccountManagerProxy(IBinder binder) {
        super(binder);
        platformService = BRIAccountManagerStub.getWithException().asInterface(binder);
    }

    private static IBinder originalBinder() {
        IBinder binder = BRServiceManager.get().getService(Context.ACCOUNT_SERVICE);
        while (binder instanceof IAccountManagerProxy) {
            IInterface original = ((IAccountManagerProxy) binder).platformService;
            binder = original == null ? null : original.asBinder();
        }
        return binder;
    }

    private static Set<String> virtualMethods() {
        Set<String> names = new HashSet<>();
        for (Class<?> type : IAccountManagerProxy.class.getDeclaredClasses()) {
            ProxyMethod method = type.getAnnotation(ProxyMethod.class);
            if (method != null) names.add(method.value());
            ProxyMethods methods = type.getAnnotation(ProxyMethods.class);
            if (methods != null) Collections.addAll(names, methods.value());
        }
        return Collections.unmodifiableSet(names);
    }

    @Override
    protected Object getWho() {
        return platformService;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ACCOUNT_SERVICE);
        installed = this;
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) replaceManagerService(manager);
        }
        fixContext(NexBoxCore.getContext());
        fixContext(BActivityThread.getApplication());
    }

    public static void fixContext(Context context) {
        IAccountManagerProxy hook = installed;
        if (context == null || hook == null || hook.getProxyInvocation() == null
                || !NexBoxCore.get().isGuestProcess()) return;
        Object manager = context.getSystemService(Context.ACCOUNT_SERVICE);
        if (manager == null) throw new IllegalStateException("AccountManager is unavailable");
        CACHED_MANAGERS.put(manager, Boolean.TRUE);
        hook.replaceManagerService(manager);
    }

    private void replaceManagerService(Object manager) {
        BRAccountManager.getWithException(manager)._set_mService((IInterface) getProxyInvocation());
    }

    @Override
    public boolean isBadEnv() {
        if (getProxyInvocation() == null || BRServiceManager.get().getService(Context.ACCOUNT_SERVICE) != this) return true;
        synchronized (CACHED_MANAGERS) {
            for (Object manager : CACHED_MANAGERS.keySet()) {
                if (BRAccountManager.getWithException(manager).mService() != getProxyInvocation()) return true;
            }
        }
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) return this;
        if (!NexBoxCore.get().isGuestProcess() || method.getDeclaringClass() == Object.class) {
            return super.invoke(proxy, method, args);
        }
        if (!VIRTUAL_METHODS.contains(method.getName())) {
            // An uncovered API must not query or mutate the device owner's accounts.
            if (args != null) for (Object argument : args) {
                if (argument instanceof IAccountManagerResponse) {
                    ((IAccountManagerResponse) argument).onError(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
                            "Account API is not yet supported: " + method.getName());
                    return null;
                }
            }
            throw new UnsupportedOperationException("Account API is not yet supported: " + method.getName());
        }
        Slog.dLazy(TAG, () -> "call " + method.getName());
        return super.invoke(proxy, method, args);
    }

    @ProxyMethods({"getAccounts", "getAccountsByType"})
    public static class GetAccounts extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsAsUser(args == null || args.length == 0 ? null : (String) args[0]);
        }
    }

    @ProxyMethod("getPassword")
    public static class getPassword extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getPassword((Account) args[0]);
        }
    }

    @ProxyMethod("startAddAccountSession")
    public static class StartAddAccountSession extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().startAddAccountSession((IAccountManagerResponse) args[0], (String) args[1],
                    (String) args[2], (String[]) args[3], (Boolean) args[4], (Bundle) args[5]);
            return null;
        }
    }

    @ProxyMethod("startUpdateCredentialsSession")
    public static class StartUpdateCredentialsSession extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().startUpdateCredentialsSession((IAccountManagerResponse) args[0], (Account) args[1],
                    (String) args[2], (Boolean) args[3], (Bundle) args[4]);
            return null;
        }
    }

    @ProxyMethod("finishSessionAsUser")
    public static class FinishSessionAsUser extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().finishSessionAsUser((IAccountManagerResponse) args[0], (Bundle) args[1],
                    (Boolean) args[2], (Bundle) args[3]);
            return null;
        }
    }

    @ProxyMethod("isCredentialsUpdateSuggested")
    public static class IsCredentialsUpdateSuggested extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().isCredentialsUpdateSuggested((IAccountManagerResponse) args[0], (Account) args[1],
                    (String) args[2]);
            return null;
        }
    }

    @ProxyMethod("getUserData")
    public static class getUserData extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getUserData((Account) args[0], (String) args[1]);
        }
    }

    @ProxyMethod("getAuthenticatorTypes")
    public static class getAuthenticatorTypes extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAuthenticatorTypes();
        }
    }

    @ProxyMethod("getAccountsForPackage")
    public static class getAccountsForPackage extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsForPackage((String) args[0], (int) args[1]);
        }
    }

    @ProxyMethod("getAccountsByTypeForPackage")
    public static class getAccountsByTypeForPackage extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsByTypeForPackage((String) args[0], (String) args[1]);
        }
    }

    @ProxyMethod("getAccountByTypeAndFeatures")
    public static class getAccountByTypeAndFeatures extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().getAccountByTypeAndFeatures((IAccountManagerResponse) args[0], (String) args[1], (String[]) args[2]);
            return 0;
        }
    }

    @ProxyMethod("getAccountsByFeatures")
    public static class getAccountsByFeatures extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().getAccountsByFeatures((IAccountManagerResponse) args[0], (String) args[1], (String[]) args[2]);
            return 0;
        }
    }

    @ProxyMethod("getAccountsAsUser")
    public static class getAccountsAsUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsAsUser((String) args[0]);
        }
    }

    @ProxyMethod("addAccountExplicitly")
    public static class addAccountExplicitly extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().addAccountExplicitly((Account) args[0], (String) args[1], (Bundle) args[2]);
        }
    }

    @ProxyMethod("removeAccountAsUser")
    public static class removeAccountAsUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().removeAccountAsUser((IAccountManagerResponse) args[0], (Account) args[1], (boolean) args[2]);
            return 0;
        }
    }

    @ProxyMethod("removeAccountExplicitly")
    public static class removeAccountExplicitly extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().removeAccountExplicitly((Account) args[0]);
        }
    }

    @ProxyMethod("copyAccountToUser")
    public static class copyAccountToUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().copyAccountToUser((IAccountManagerResponse) args[0], (Account) args[1], (int) args[2], (int) args[3]);
            return 0;
        }
    }

    @ProxyMethod("invalidateAuthToken")
    public static class invalidateAuthToken extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().invalidateAuthToken((String) args[0], (String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("peekAuthToken")
    public static class peekAuthToken extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().peekAuthToken((Account) args[0], (String) args[1]);
        }
    }

    @ProxyMethod("setAuthToken")
    public static class setAuthToken extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().setAuthToken((Account) args[0], (String) args[1], (String) args[2]);
            return 0;
        }
    }

    @ProxyMethod("setPassword")
    public static class setPassword extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().setPassword((Account) args[0], (String) args[1]);
            return 0;
        }
    }

    @ProxyMethod("clearPassword")
    public static class clearPassword extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().clearPassword((Account) args[0]);
            return 0;
        }
    }

    @ProxyMethod("setUserData")
    public static class setUserData extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().setUserData((Account) args[0], (String) args[1], (String) args[2]);
            return 0;
        }
    }

    @ProxyMethod("updateAppPermission")
    public static class updateAppPermission extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().updateAppPermission((Account) args[0], (String) args[1], (int) args[2], (boolean) args[3]);
            return 0;
        }
    }

    @ProxyMethod("getAuthToken")
    public static class getAuthToken extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().getAuthToken((IAccountManagerResponse) args[0],
                    (Account) args[1],
                    (String) args[2],
                    (boolean) args[3],
                    (boolean) args[4],
                    (Bundle) args[5]);
            return 0;
        }
    }

    @ProxyMethod("addAccount")
    public static class addAccount extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().addAccount((IAccountManagerResponse) args[0],
                    (String) args[1],
                    (String) args[2],
                    (String[]) args[3],
                    (boolean) args[4],
                    (Bundle) args[5]);
            return 0;
        }
    }

    @ProxyMethod("addAccountAsUser")
    public static class addAccountAsUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().addAccountAsUser((IAccountManagerResponse) args[0],
                    (String) args[1],
                    (String) args[2],
                    (String[]) args[3],
                    (boolean) args[4],
                    (Bundle) args[5]);
            return 0;
        }
    }

    @ProxyMethod("updateCredentials")
    public static class updateCredentials extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().updateCredentials((IAccountManagerResponse) args[0],
                    (Account) args[1],
                    (String) args[2],
                    (boolean) args[3],
                    (Bundle) args[4]);
            return 0;
        }
    }

    @ProxyMethod("editProperties")
    public static class editProperties extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().editProperties((IAccountManagerResponse) args[0],
                    (String) args[1],
                    (boolean) args[2]);
            return 0;
        }
    }

    @ProxyMethod("confirmCredentialsAsUser")
    public static class confirmCredentialsAsUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().confirmCredentialsAsUser((IAccountManagerResponse) args[0],
                    (Account) args[1],
                    (Bundle) args[2],
                    (boolean) args[3]);
            return 0;
        }
    }

    @ProxyMethod("accountAuthenticated")
    public static class accountAuthenticated extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().accountAuthenticated((Account) args[0]);
        }
    }

    @ProxyMethod("getAuthTokenLabel")
    public static class getAuthTokenLabel extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().getAuthTokenLabel((IAccountManagerResponse) args[0],
                    (String) args[1],
                    (String) args[2]);
            return 0;
        }
    }

    @ProxyMethod("getPackagesAndVisibilityForAccount")
    public static class getPackagesAndVisibilityForAccount extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getPackagesAndVisibilityForAccount((Account) args[0]);
        }
    }

    @ProxyMethod("addAccountExplicitlyWithVisibility")
    public static class addAccountExplicitlyWithVisibility extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().addAccountExplicitlyWithVisibility((Account) args[0],
                    (String) args[1],
                    (Bundle) args[2],
                    (Map<?, ?>) args[3]
            );
        }
    }

    @ProxyMethod("setAccountVisibility")
    public static class setAccountVisibility extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().setAccountVisibility((Account) args[0],
                    (String) args[1],
                    (int) args[2]
            );
        }
    }

    @ProxyMethod("getAccountVisibility")
    public static class getAccountVisibility extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountVisibility((Account) args[0],
                    (String) args[1]
            );
        }
    }

    @ProxyMethod("getAccountsAndVisibilityForPackage")
    public static class getAccountsAndVisibilityForPackage extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BAccountManager.get().getAccountsAndVisibilityForPackage((String) args[0],
                    (String) args[1]
            );
        }
    }

    @ProxyMethod("registerAccountListener")
    public static class registerAccountListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().registerAccountListener((String[]) args[0],
                    (String) args[1]
            );
            return 0;
        }
    }

    @ProxyMethod("unregisterAccountListener")
    public static class unregisterAccountListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BAccountManager.get().unregisterAccountListener((String[]) args[0],
                    (String) args[1]
            );
            return 0;
        }
    }
}
