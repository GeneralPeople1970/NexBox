package com.nexbox.core.core.system.accounts;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import black.com.android.internal.BRRstyleable;
import black.com.android.internal.RstyleableStatic;

import com.nexbox.core.core.system.pm.BPackageManagerService;
import com.nexbox.core.core.system.user.BUserInfo;
import com.nexbox.core.core.system.user.BUserManagerService;
import com.nexbox.core.utils.Slog;

/** Discovers installed authenticators and publishes immutable snapshots per user generation. */
final class AccountAuthenticatorRegistry {
    private static final String TAG = "AccountManagerService";
    private final Context mContext;
    private final BPackageManagerService mPms;
    private final AccountPackageIdentity mIdentity;
    private final Map<Integer, AuthenticatorCache> mAuthenticatorCaches = new HashMap<>();
    private long mAuthenticatorRevision;

    AccountAuthenticatorRegistry(Context context, BPackageManagerService pms, AccountPackageIdentity identity) {
        mContext = context;
        mPms = pms;
        mIdentity = identity;
    }

    Set<String> cachedOwnerTypes(String packageName, int userId) {
        Set<String> ownerTypes = new HashSet<>();
        synchronized (mAuthenticatorCaches) {
            AuthenticatorCache cache = mAuthenticatorCaches.get(userId);
            if (cache != null) cache.authenticators.forEach((type, info) -> {
                if (packageName.equals(info.serviceInfo.packageName)) ownerTypes.add(type);
            });
        }
        return ownerTypes;
    }

    void invalidate() {
        // Package changes invalidate the complete discovery snapshot.
        synchronized (mAuthenticatorCaches) {
            mAuthenticatorRevision++;
            mAuthenticatorCaches.clear();
        }
    }

    void removeUser(int userId) {
        synchronized (mAuthenticatorCaches) {
            mAuthenticatorRevision++;
            mAuthenticatorCaches.remove(userId);
        }
    }

    static final class AuthenticatorInfo {
        final AuthenticatorDescription desc;
        final ServiceInfo serviceInfo;

        AuthenticatorInfo(AuthenticatorDescription desc, ServiceInfo info) {
            this.desc = desc;
            this.serviceInfo = info;
        }
    }

    private static final class AuthenticatorCache {
        final long userGeneration;
        final Map<String, AuthenticatorInfo> authenticators;

        AuthenticatorCache(long userGeneration, Map<String, AuthenticatorInfo> authenticators) {
            this.userGeneration = userGeneration;
            this.authenticators = Collections.unmodifiableMap(new LinkedHashMap<>(authenticators));
        }
    }

    private static AuthenticatorDescription parseAuthenticatorDescription(Resources resources, String packageName,
                                                                          AttributeSet attributeSet) {
        RstyleableStatic rstyleableStatic = BRRstyleable.get();
        TypedArray array = resources.obtainAttributes(attributeSet, rstyleableStatic.AccountAuthenticator());
        try {
            String accountType = array.getString(rstyleableStatic.AccountAuthenticator_accountType());
            int label = array.getResourceId(rstyleableStatic.AccountAuthenticator_label(), 0);
            int icon = array.getResourceId(rstyleableStatic.AccountAuthenticator_icon(), 0);
            int smallIcon = array.getResourceId(rstyleableStatic.AccountAuthenticator_smallIcon(), 0);
            int accountPreferences = array.getResourceId(rstyleableStatic.AccountAuthenticator_accountPreferences(), 0);
            boolean customTokens = array.getBoolean(rstyleableStatic.AccountAuthenticator_customTokens(), false);
            if (TextUtils.isEmpty(accountType)) {
                return null;
            }
            return new AuthenticatorDescription(accountType, packageName, label, icon, smallIcon, accountPreferences,
                    customTokens);
        } finally {
            array.recycle();
        }
    }

    Map<String, AuthenticatorInfo> getAuthenticators(int userId) {
        for (int attempt = 0; attempt < 2; attempt++) {
            BUserInfo user = BUserManagerService.get().getUserInfo(userId);
            if (user == null) throw new IllegalStateException("Account user no longer exists");
            long revision;
            synchronized (mAuthenticatorCaches) {
                AuthenticatorCache cached = mAuthenticatorCaches.get(userId);
                if (cached != null && cached.userGeneration == user.createTime) return cached.authenticators;
                revision = mAuthenticatorRevision;
            }
            Map<String, AuthenticatorInfo> discovered = new LinkedHashMap<>();
            Intent intent = new Intent(AccountManager.ACTION_AUTHENTICATOR_INTENT);
            List<ResolveInfo> services = mPms.queryIntentServices(intent, PackageManager.GET_META_DATA, userId);
            generateServicesMap(services, discovered, new RegisteredServicesParser(), userId);
            BUserInfo current = BUserManagerService.get().getUserInfo(userId);
            if (current == null || current.createTime != user.createTime) {
                throw new IllegalStateException("Account user changed during authenticator discovery");
            }
            synchronized (mAuthenticatorCaches) {
                if (revision != mAuthenticatorRevision) continue;
                AuthenticatorCache cache = new AuthenticatorCache(user.createTime, discovered);
                mAuthenticatorCaches.put(userId, cache);
                return cache.authenticators;
            }
        }
        throw new IllegalStateException("Authenticator packages changed during discovery");
    }

    private void generateServicesMap(List<ResolveInfo> services, Map<String, AuthenticatorInfo> map,
                                     RegisteredServicesParser accountParser, int userId) {
        if (services == null) throw new IllegalStateException("Authenticator service query failed");
        Set<String> ambiguousTypes = new HashSet<>();
        for (ResolveInfo info : services) {
            if (info == null || info.serviceInfo == null || !info.serviceInfo.enabled
                    || info.serviceInfo.applicationInfo == null || !info.serviceInfo.applicationInfo.enabled) continue;
            XmlResourceParser parser = accountParser.getParser(mContext, info.serviceInfo,
                    AccountManager.AUTHENTICATOR_META_DATA_NAME);
            if (parser != null) {
                try {
                    AttributeSet attributeSet = Xml.asAttributeSet(parser);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT && type != XmlPullParser.START_TAG) {

                    }
                    if (AccountManager.AUTHENTICATOR_ATTRIBUTES_NAME.equals(parser.getName())) {
                        AuthenticatorDescription desc = parseAuthenticatorDescription(
                                accountParser.getResources(mContext, info.serviceInfo.applicationInfo),
                                info.serviceInfo.packageName, attributeSet);
                        if (desc != null && !ambiguousTypes.contains(desc.type)) {
                            AuthenticatorInfo previous = map.get(desc.type);
                            if (previous == null) {
                                map.put(desc.type, new AuthenticatorInfo(desc, info.serviceInfo));
                            } else if (!mIdentity.sameAccountSigner(previous.serviceInfo.packageName, info.serviceInfo.packageName, userId)) {
                                map.remove(desc.type);
                                ambiguousTypes.add(desc.type);
                                Slog.w(TAG, "Ambiguous authenticator type in user=" + userId);
                            }
                        }
                    }
                } catch (Exception e) {
                    Slog.e("BAccountManagerService", "Container operation failed", e);
                } finally {
                    parser.close();
                }
            }
        }
    }
}
