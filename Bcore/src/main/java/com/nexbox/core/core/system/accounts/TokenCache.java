package com.nexbox.core.core.system.accounts;

import android.accounts.Account;

import java.util.Objects;


public class TokenCache {
    public int userId;
    public long userGeneration;
    public long resetEpoch;
    public String callerSigner;
    public String authenticatorSigner;
    public Account account;
    public long expiryEpochMillis;
    public String authToken;
    public String authTokenType;
    public String packageName;

    public TokenCache(int userId,Account account,
                      String callerPkg,
                      String tokenType,
                      String token,
                      long expiryMillis) {
        this.userId = userId;
        this.account = account;
        this.expiryEpochMillis = expiryMillis;
        this.authToken = token;
        this.authTokenType = tokenType;
        this.packageName = callerPkg;
    }

    public TokenCache(int userId, Account account, String authTokenType, String packageName) {
        this.userId = userId;
        this.account = account;
        this.authTokenType = authTokenType;
        this.packageName = packageName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TokenCache)) return false;
        TokenCache that = (TokenCache) o;
        return userId == that.userId && userGeneration == that.userGeneration
                && resetEpoch == that.resetEpoch
                && Objects.equals(callerSigner, that.callerSigner)
                && Objects.equals(authenticatorSigner, that.authenticatorSigner) &&
                expiryEpochMillis == that.expiryEpochMillis &&
                Objects.equals(account, that.account) &&
                Objects.equals(authToken, that.authToken) &&
                Objects.equals(authTokenType, that.authTokenType) &&
                Objects.equals(packageName, that.packageName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, userGeneration, resetEpoch, callerSigner, authenticatorSigner,
                account, expiryEpochMillis, authToken, authTokenType, packageName);
    }
}
