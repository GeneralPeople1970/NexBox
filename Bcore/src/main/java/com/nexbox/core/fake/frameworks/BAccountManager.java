package com.nexbox.core.fake.frameworks;

import android.accounts.Account;
import android.accounts.AuthenticatorDescription;
import android.accounts.IAccountManagerResponse;
import android.os.Bundle;
import android.os.RemoteException;

import java.util.Map;

import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.system.ServiceManager;
import com.nexbox.core.core.system.accounts.AccountVisibilityMap;
import com.nexbox.core.core.system.accounts.IBAccountManagerService;
import com.nexbox.core.core.system.accounts.PackageVisibilityMap;


public class BAccountManager extends CoreServiceManager<IBAccountManagerService> {
    private static final BAccountManager sBAccountManager = new BAccountManager();

    public static BAccountManager get() {
        return sBAccountManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.ACCOUNT_MANAGER;
    }

    private IBAccountManagerService requireService() throws RemoteException {
        IBAccountManagerService service = getService();
        if (service == null) throw new RemoteException("Virtual account service is unavailable");
        return service;
    }

    public String getPassword(Account account) throws RemoteException {
        return requireService().getPassword(account, BActivityThread.getUserId());
    }


    public String getUserData(Account account, String key) throws RemoteException {
        return requireService().getUserData(account, key, BActivityThread.getUserId());
    }

    public AuthenticatorDescription[] getAuthenticatorTypes() throws RemoteException {
        return requireService().getAuthenticatorTypes(BActivityThread.getUserId());
    }

    public Account[] getAccountsForPackage(String packageName, int uid) throws RemoteException {
        return requireService().getAccountsForPackage(packageName, uid, BActivityThread.getUserId());
    }

    public Account[] getAccountsByTypeForPackage(String type, String packageName) throws RemoteException {
        return requireService().getAccountsByTypeForPackage(type, packageName, BActivityThread.getUserId());
    }

    public Account[] getAccountsAsUser(String type) throws RemoteException {
        return requireService().getAccountsAsUser(type, BActivityThread.getUserId());
    }

    public void getAccountByTypeAndFeatures(IAccountManagerResponse response, String accountType,
                                            String[] features) throws RemoteException {
        requireService().getAccountByTypeAndFeatures(response, accountType, features, BActivityThread.getUserId());
    }
    
    public void getAccountsByFeatures(IAccountManagerResponse response, String accountType,
                               String[] features) throws RemoteException {
        requireService().getAccountsByFeatures(response, accountType, features, BActivityThread.getUserId());
    }

    public boolean addAccountExplicitly(Account account, String password, Bundle extras) throws RemoteException {
        return requireService().addAccountExplicitly(account, password, extras, BActivityThread.getUserId());
    }

    public void removeAccountAsUser(IAccountManagerResponse response, Account account,
                             boolean expectActivityLaunch) throws RemoteException {
        requireService().removeAccountAsUser(response, account, expectActivityLaunch, BActivityThread.getUserId());
    }

    public boolean removeAccountExplicitly(Account account) throws RemoteException {
        return requireService().removeAccountExplicitly(account, BActivityThread.getUserId());
    }

    public void copyAccountToUser(IAccountManagerResponse response, Account account,
                           int userFrom, int userTo) throws RemoteException {
        requireService().copyAccountToUser(response, account, userFrom, userTo);
    }

    public void invalidateAuthToken(String accountType, String authToken) throws RemoteException {
        requireService().invalidateAuthToken(accountType, authToken, BActivityThread.getUserId());
    }

    public String peekAuthToken(Account account, String authTokenType) throws RemoteException {
        return requireService().peekAuthToken(account, authTokenType, BActivityThread.getUserId());
    }

    public void setAuthToken(Account account, String authTokenType, String authToken) throws RemoteException {
        requireService().setAuthToken(account, authTokenType, authToken, BActivityThread.getUserId());
    }


    public void setPassword(Account account, String password) throws RemoteException {
        requireService().setPassword(account, password, BActivityThread.getUserId());
    }

    public void clearPassword(Account account) throws RemoteException {
        requireService().clearPassword(account, BActivityThread.getUserId());
    }

    public void setUserData(Account account, String key, String value) throws RemoteException {
        requireService().setUserData(account, key, value, BActivityThread.getUserId());
    }

    public void updateAppPermission(Account account, String authTokenType, int uid, boolean value) throws RemoteException {
        requireService().updateAppPermission(account, authTokenType, uid, value);
    }

    public void getAuthToken(IAccountManagerResponse response, Account account,
                      String authTokenType, boolean notifyOnAuthFailure, boolean expectActivityLaunch,
                      Bundle options) throws RemoteException {
        requireService().getAuthToken(response, account, authTokenType, notifyOnAuthFailure, expectActivityLaunch, options, BActivityThread.getUserId());
    }

    public void addAccount(IAccountManagerResponse response, String accountType,
                    String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch,
                    Bundle options) throws RemoteException {
        requireService().addAccount(response, accountType, authTokenType, requiredFeatures, expectActivityLaunch, options, BActivityThread.getUserId());
    }

    public void addAccountAsUser(IAccountManagerResponse response, String accountType,
                          String authTokenType, String[] requiredFeatures, boolean expectActivityLaunch,
                          Bundle options) throws RemoteException {
        requireService().addAccountAsUser(response, accountType, authTokenType, requiredFeatures, expectActivityLaunch, options, BActivityThread.getUserId());
    }

    public void updateCredentials(IAccountManagerResponse response, Account account,
                           String authTokenType, boolean expectActivityLaunch, Bundle options) throws RemoteException {
        requireService().updateCredentials(response, account, authTokenType, expectActivityLaunch, options, BActivityThread.getUserId());
    }

    public void editProperties(IAccountManagerResponse response, String accountType,
                        boolean expectActivityLaunch) throws RemoteException {
        requireService().editProperties(response, accountType, expectActivityLaunch, BActivityThread.getUserId());
    }

    public void confirmCredentialsAsUser(IAccountManagerResponse response, Account account,
                                  Bundle options, boolean expectActivityLaunch) throws RemoteException {
        requireService().confirmCredentialsAsUser(response, account, options, expectActivityLaunch, BActivityThread.getUserId());
    }

    public boolean accountAuthenticated(Account account) throws RemoteException {
        return requireService().accountAuthenticated(account, BActivityThread.getUserId());
    }

    public void getAuthTokenLabel(IAccountManagerResponse response, String accountType,
                           String authTokenType) throws RemoteException {
        requireService().getAuthTokenLabel(response, accountType, authTokenType, BActivityThread.getUserId());
    }

    
    public Map<String, Integer> getPackagesAndVisibilityForAccount(Account account) throws RemoteException {
        PackageVisibilityMap result = requireService().getPackagesAndVisibilityForAccount(
                account, BActivityThread.getUserId());
        return result == null ? null : result.toMap();
    }

    public boolean addAccountExplicitlyWithVisibility(Account account, String password, Bundle extras,
                                               Map<?, ?> visibility) throws RemoteException {
        return requireService().addAccountExplicitlyWithVisibility(account, password, extras,
                PackageVisibilityMap.fromMap(visibility), BActivityThread.getUserId());
    }

    public boolean setAccountVisibility(Account account, String packageName, int newVisibility) throws RemoteException {
        return requireService().setAccountVisibility(account, packageName, newVisibility, BActivityThread.getUserId());
    }

    public int getAccountVisibility(Account account, String packageName) throws RemoteException {
        return requireService().getAccountVisibility(account, packageName, BActivityThread.getUserId());
    }

    
    public Map<Account, Integer> getAccountsAndVisibilityForPackage(String packageName, String accountType) throws RemoteException {
        AccountVisibilityMap result = requireService().getAccountsAndVisibilityForPackage(
                packageName, accountType, BActivityThread.getUserId());
        return result == null ? null : result.toMap();
    }

    public void registerAccountListener(String[] accountTypes, String opPackageName) throws RemoteException {
        requireService().registerAccountListener(accountTypes, opPackageName, BActivityThread.getUserId());
    }

    public void unregisterAccountListener(String[] accountTypes, String opPackageName) throws RemoteException {
        requireService().unregisterAccountListener(accountTypes, opPackageName, BActivityThread.getUserId());
    }

    public void startAddAccountSession(IAccountManagerResponse response, String type, String tokenType,
                                      String[] features, boolean expectActivity, Bundle options) throws RemoteException {
        requireService().startAddAccountSession(response, type, tokenType, features, expectActivity, options,
                BActivityThread.getUserId());
    }

    public void startUpdateCredentialsSession(IAccountManagerResponse response, Account account, String tokenType,
                                             boolean expectActivity, Bundle options) throws RemoteException {
        requireService().startUpdateCredentialsSession(response, account, tokenType, expectActivity, options,
                BActivityThread.getUserId());
    }

    public void finishSessionAsUser(IAccountManagerResponse response, Bundle session, boolean expectActivity,
                                    Bundle appInfo) throws RemoteException {
        requireService().finishSessionAsUser(response, session, expectActivity, appInfo, BActivityThread.getUserId());
    }

    public void isCredentialsUpdateSuggested(IAccountManagerResponse response, Account account, String statusToken)
            throws RemoteException {
        requireService().isCredentialsUpdateSuggested(response, account, statusToken, BActivityThread.getUserId());
    }
}
