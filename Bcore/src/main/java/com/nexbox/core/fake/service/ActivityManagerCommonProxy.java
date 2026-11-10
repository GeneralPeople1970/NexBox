package com.nexbox.core.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.text.TextUtils;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.core.env.AppSystemEnv;
import com.nexbox.core.core.env.ExternalActivityGuard;
import com.nexbox.core.core.env.SamsungHealthCompat;
import com.nexbox.core.fake.hook.MethodHook;
import com.nexbox.core.fake.hook.ProxyMethod;
import com.nexbox.core.fake.provider.FileProviderHandler;
import com.nexbox.core.proxy.record.ProxyActivityRecord;
import com.nexbox.core.utils.ComponentUtils;
import com.nexbox.core.utils.ActivityIntentLog;
import com.nexbox.core.utils.MethodParameterUtils;
import com.nexbox.core.utils.Slog;
import com.nexbox.core.utils.compat.BundleCompat;
import com.nexbox.core.utils.compat.StartActivityCompat;

import static android.content.pm.PackageManager.GET_META_DATA;


public class ActivityManagerCommonProxy {
    public static final String TAG = "CommonStub";
    private static final int START_RESULT_UNRESOLVED = Integer.MIN_VALUE;
    private static volatile int sStartIntentNotResolved = START_RESULT_UNRESOLVED;
    private static final String PKG_SAMSUNG_HEALTH = "com.sec.android.app.shealth";
    private static final String PKG_SAMSUNG_ACCOUNT = "com.osp.app.signin";
    private static final String ACTION_SAMSUNG_ACCOUNT_SIGNIN_POPUP = "com.msc.action.samsungaccount.SIGNIN_POPUP";
    private static final String ACTION_SAMSUNG_ACCOUNT_REQUEST_SIGN_IN_FROM_WEB_SDK = "com.samsung.android.samsungaccount.action.REQUEST_SIGN_IN_FROM_WEB_SDK";
    private static final String ACTION_SAMSUNG_ACCOUNT_REQUEST_CONFIRM_PASSWORD_FROM_WEB_SDK = "com.samsung.android.samsungaccount.action.REQUEST_CONFIRM_PASSWORD_FROM_WEB_SDK";
    private static final String ACTIVITY_SAMSUNG_HEALTH_AUTHENTICATOR = "com.samsung.android.app.shealth.accounts.AuthenticatorActivity";
    private static final String ACTIVITY_SAMSUNG_HEALTH_ACCOUNT_HANDLER = "com.samsung.android.app.shealth.jwt.AccountHandlerActivity";

    private static int getStartIntentNotResolvedCode() {
        int cached = sStartIntentNotResolved;
        if (cached != START_RESULT_UNRESOLVED) {
            return cached;
        }
        int resolved = -1;
        try {
            Field f = ActivityManager.class.getDeclaredField("START_INTENT_NOT_RESOLVED");
            f.setAccessible(true);
            resolved = f.getInt(null);
        } catch (Throwable ignored) {
        }
        sStartIntentNotResolved = resolved;
        return resolved;
    }

    @ProxyMethod("startActivity")
    public static class StartActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            Intent intent = getIntent(args);
            if (intent == null) throw new IllegalArgumentException("Missing activity intent");
            ClassLoader guestLoader = BActivityThread.getApplication() == null
                    ? Thread.currentThread().getContextClassLoader() : BActivityThread.getApplication().getClassLoader();
            intent.setExtrasClassLoader(guestLoader);
            com.nexbox.core.entity.AppConfig caller = BActivityThread.getAppConfig();
            ActivityIntentLog.log("startRequested", intent, BActivityThread.getUserId(),
                    caller == null ? 0L : caller.userGeneration, true, "method=" + method.getName());
            
            
            if (ProxyActivityRecord.hasTarget(intent)) {
                ComponentName component = intent.getComponent();
                boolean transport = false;
                if (component != null && NexBoxCore.getHostPkg().equals(component.getPackageName())) {
                    for (int i = 0; i < com.nexbox.core.proxy.ProxyManifest.FREE_COUNT; i++) {
                        if (component.getClassName().equals(com.nexbox.core.proxy.ProxyManifest.getProxyActivity(i))
                                || component.getClassName().equals(com.nexbox.core.proxy.ProxyManifest.TransparentProxyActivity(i))) {
                            transport = true;
                            break;
                        }
                    }
                }
                if (!transport) throw new SecurityException("Invalid virtual activity transport");
                return method.invoke(who, args);
            }
            if (ComponentUtils.isRequestInstall(intent)) {
                File file = FileProviderHandler.convertFile(BActivityThread.getApplication(), intent.getData());
                
                
                if (file != null && file.exists()) {
                    try {
                        PackageInfo packageInfo = NexBoxCore.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
                        if (packageInfo != null) {
                            String packageName = packageInfo.packageName;
                            String hostPackageName = NexBoxCore.getHostPkg();
                            if (packageName.equals(hostPackageName)) {
                                Slog.w(TAG, "Blocked attempt to install NexBox app from within NexBox: " + packageName);
                                
                                return 0;
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Could not verify if this is NexBox app: " + e.getMessage(), e);
                    }
                }
                
                if (NexBoxCore.get().requestInstallPackage(file, BActivityThread.getUserId())) {
                    return 0;
                }
                intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                return method.invoke(who, args);
            }
            String dataString = intent.getDataString();
            if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {
                intent.setData(Uri.parse("package:" + NexBoxCore.getHostPkg()));
            }

            // Samsung Health default behavior: keep sign-in inside NexBox.
            // If it attempts to launch Samsung Account's SIGNIN_POPUP and the host fallback is
            // disabled, rewrite to Samsung Health's own AuthenticatorActivity (Custom Tab / web
            // flow) so the result is delivered back into Samsung Health inside the profile.
            Intent rewritten = maybeRewriteSamsungHealthSigninPopupToAuthenticator(intent);
            if (rewritten != null) {
                replaceIntentArg(args, intent, rewritten);
                intent = rewritten;
            }

            // Samsung Health AuthenticatorActivity uses SaSDKManager, which normally launches the
            // host Samsung Account app's WebSdkActivity (REQUEST_SIGN_IN_FROM_WEB_SDK). That host
            // activity enforces caller signature checks and will see the NexBox host as caller.
            // When fallback is disabled, prefer a pure in-profile web flow by rewriting the SaSDK
            // request into an ACTION_VIEW https://... browser launch (so the sasdk:// redirect
            // returns back into Samsung Health's ResponseReceiverActivity inside the profile).
            Intent webSdkRewritten = maybeRewriteSamsungHealthSaSdkToBrowser(intent);
            if (webSdkRewritten != null) {
                replaceIntentArg(args, intent, webSdkRewritten);
                intent = webSdkRewritten;
            }

            if (MediaPickerRouting.needsPrivatePicker(intent)) {
                Intent picker = MediaPickerRouting.privatePicker(NexBoxCore.getHostPkg(), intent);
                replaceIntentArg(args, intent, picker);
                args[StartActivityCompat.getResolvedTypeIndex()] = null;
                return method.invoke(who, args);
            }

            if (Intent.ACTION_CHOOSER.equals(intent.getAction())
                    && !intent.hasExtra(Intent.EXTRA_INITIAL_INTENTS)) {
                Object candidate = BundleCompat.getParcelable(intent, Intent.EXTRA_INTENT, Parcelable.class);
                if (candidate instanceof Intent) {
                    Intent target = (Intent) candidate;
                    if (target.getComponent() != null
                            && (Intent.ACTION_GET_CONTENT.equals(target.getAction())
                            || Intent.ACTION_PICK.equals(target.getAction()))) {
                        ResolveInfo guestTarget = NexBoxCore.getBPackageManager().resolveActivity(
                                target, GET_META_DATA, target.getType(), BActivityThread.getUserId());
                        if (guestTarget != null) {
                            Intent explicitGuest = new Intent(target);
                            replaceIntentArg(args, intent, explicitGuest);
                            args[StartActivityCompat.getResolvedTypeIndex()] = explicitGuest.getType();
                            intent = explicitGuest;
                        }
                    }
                }
            }

            ResolveInfo resolveInfo = resolveGuestActivity(intent, StartActivityCompat.getResolvedType(args));
            if (resolveInfo == null) {
                String origPackage = intent.getPackage();
                if (intent.getPackage() == null && intent.getComponent() == null) {
                    intent.setPackage(BActivityThread.getAppPackageName());
                } else {
                    origPackage = intent.getPackage();
                }
                resolveInfo = resolveGuestActivity(intent, StartActivityCompat.getResolvedType(args));
                if (resolveInfo == null) {
                    intent.setPackage(origPackage);
                    ResolveInfo host = NexBoxCore.getPackageManager().resolveActivity(intent, GET_META_DATA);
                    if (com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intent, host == null ? null : host.activityInfo)) {
                        return getStartIntentNotResolvedCode();
                    }

                    Intent mediaPicker = MediaPickerRouting.redirect(intent, NexBoxCore.getHostPkg());
                    if (mediaPicker != null) {
                        replaceIntentArg(args, intent, mediaPicker);
                        args[StartActivityCompat.getResolvedTypeIndex()] = null;
                        Slog.d(TAG, "Routing media selection to shared library");
                        // Preserve the system result token and request code of the guest window.
                        return method.invoke(who, args);
                    }

                    // Default behavior for Samsung Health should not use the phone's Samsung
                    // Account app. If the app attempts the SamsungAccount SIGNIN_POPUP flow and
                    // the per-app fallback toggle is OFF, fail as if no activity was found so the
                    // app can take its normal browser-based path (e.g., Brave inside NexBox).
                    if (shouldBlockSamsungHealthSamsungAccount(intent)) {
                        Slog.w(TAG, "Blocked SamsungAccount SIGNIN_POPUP for Samsung Health (fallback disabled)");
                        return getStartIntentNotResolvedCode();
                    }

                    // Do NOT hard-block SamsungAccount WebSdk requests here. Returning
                    // START_INTENT_NOT_RESOLVED causes Samsung Health's AuthenticatorActivity to
                    // crash (ActivityNotFoundException), and NexBox's crash recovery can leave
                    // the window in a broken state that triggers an InputDispatcher ANR.
                    //
                    // The preferred privacy-first fix is to prevent Samsung Health's SaSDK from
                    // detecting the host Samsung Account package when fallback is disabled (see
                    // IPackageManagerProxy), so this request is never attempted.

                    launchOpenHostActivityInOwnTaskIfNeeded(intent, args);
                    ActivityIntentLog.log("hostDispatch", intent, BActivityThread.getUserId(),
                            caller == null ? 0L : caller.userGeneration, false, "");
                    return method.invoke(who, args);
                }
            }


            com.nexbox.core.utils.IntentSanitizer.sanitizeClassExtrasForIpc(intent);
            intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            ActivityIntentLog.log("virtualDispatch", intent, BActivityThread.getUserId(),
                    caller == null ? 0L : caller.userGeneration, false,
                    "package=" + resolveInfo.activityInfo.packageName);
            com.nexbox.core.core.system.am.IBActivityManagerService activities = NexBoxCore.getBActivityManager().getService();
            if (activities == null) throw new IllegalStateException("Virtual activity service is unavailable");
            return activities.startActivityAms(BActivityThread.getUserId(),
                    intent,
                    StartActivityCompat.getResolvedType(args),
                    StartActivityCompat.getResultTo(args),
                    StartActivityCompat.getResultWho(args),
                    StartActivityCompat.getRequestCode(args),
                    StartActivityCompat.getFlags(args),
                    StartActivityCompat.getOptions(args));
        }

        private ResolveInfo resolveGuestActivity(Intent intent, String type) throws android.os.RemoteException {
            com.nexbox.core.core.system.pm.IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service is unavailable");
            int userId = BActivityThread.getUserId();
            ResolveInfo result = packages.resolveActivity(intent, GET_META_DATA, type, userId);
            if (result != null) return result;
            java.util.List<ResolveInfo> candidates = packages.queryIntentActivities(intent, GET_META_DATA, type, userId);
            return candidates == null || candidates.isEmpty() ? null : candidates.get(0);
        }

        private Intent getIntent(Object[] args) {
            int index;
            index = 3;
            if (args[index] instanceof Intent) {
                return (Intent) args[index];
            }
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
            return null;
        }

        private void launchOpenHostActivityInOwnTaskIfNeeded(Intent intent, Object[] args) {
            ResolveInfo hostResolve = NexBoxCore.getContext().getPackageManager()
                    .resolveActivity(intent, GET_META_DATA);
            if (hostResolve == null || hostResolve.activityInfo == null) {
                return;
            }
            if (!AppSystemEnv.isOpenPackage(hostResolve.activityInfo.packageName)) {
                return;
            }

            boolean didRedirectSamsungAccount = false;
            // SamsungAccount SIGNIN_POPUP enforces caller signature checks and will instantly finish
            // when the launch is attributed to the NexBox host package. For Samsung Health, prefer
            // launching the SamsungAccount app's normal entry point as a compatibility fallback.
            try {
                String callerPackage = BActivityThread.getAppPackageName();
                int userId = BActivityThread.getUserId();
                boolean fallbackEnabled = SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage);
                if (fallbackEnabled
                        && PKG_SAMSUNG_HEALTH.equals(callerPackage)
                        && ACTION_SAMSUNG_ACCOUNT_SIGNIN_POPUP.equals(intent.getAction())
                        && PKG_SAMSUNG_ACCOUNT.equals(hostResolve.activityInfo.packageName)) {
                    // Samsung Account does not necessarily expose a LAUNCHER activity; use the
                    // exported AccountView entry point that handles ADD_SAMSUNG_ACCOUNT.
                    Intent redirected = new Intent("com.samsung.android.samsungaccount.action.ADD_SAMSUNG_ACCOUNT");
                    redirected.setComponent(new ComponentName(PKG_SAMSUNG_ACCOUNT, "com.osp.app.signin.AccountView"));
                    if (intent.getExtras() != null) {
                        redirected.putExtras(intent.getExtras());
                    }
                    didRedirectSamsungAccount = true;
                    redirected.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    redirected.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    replaceIntentArg(args, intent, redirected);
                    intent = redirected;
                    ResolveInfo redirectedResolve = NexBoxCore.getContext().getPackageManager()
                            .resolveActivity(intent, GET_META_DATA);
                    if (redirectedResolve != null && redirectedResolve.activityInfo != null) {
                        hostResolve = redirectedResolve;
                    }
                    Slog.d(TAG, "Redirected SamsungAccount SIGNIN_POPUP to AccountView: " + intent.getComponent());
                }
            } catch (Throwable ignored) {
            }

            int requestCode = StartActivityCompat.getRequestCode(args);
            if (requestCode >= 0 && !didRedirectSamsungAccount) {
                return;
            }

            intent.setComponent(new ComponentName(
                    hostResolve.activityInfo.packageName,
                    hostResolve.activityInfo.name));

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            // Clear any "for result" linkage (resultTo/resultWho/requestCode). Index ordering can
            // vary across framework versions, so prefer a best-effort scan after the Intent.
            stripActivityResultArgs(args, intent);
            ExternalActivityGuard.markOpenHostActivityLaunch(
                    BActivityThread.getAppPackageName(),
                    BActivityThread.getUserId(),
                    hostResolve.activityInfo.packageName);
            Slog.d(TAG, "Launching open host activity in separate task: " + intent.getComponent());
        }

        private boolean shouldBlockSamsungHealthSamsungAccount(Intent intent) {
            try {
                if (intent == null) {
                    return false;
                }
                String callerPackage = BActivityThread.getAppPackageName();
                if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                    return false;
                }
                if (!ACTION_SAMSUNG_ACCOUNT_SIGNIN_POPUP.equals(intent.getAction())) {
                    return false;
                }

                int userId = BActivityThread.getUserId();
                if (SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage)) {
                    return false;
                }

                ResolveInfo hostResolve = NexBoxCore.getContext().getPackageManager()
                        .resolveActivity(intent, GET_META_DATA);
                if (hostResolve == null || hostResolve.activityInfo == null) {
                    return false;
                }
                return PKG_SAMSUNG_ACCOUNT.equals(hostResolve.activityInfo.packageName);
            } catch (Throwable ignored) {
            }
            return false;
        }

        private boolean shouldBlockSamsungHealthSamsungAccountWebSdk(Intent intent) {
            try {
                if (intent == null) {
                    return false;
                }
                String callerPackage = BActivityThread.getAppPackageName();
                if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                    return false;
                }
                String action = intent.getAction();
                if (!ACTION_SAMSUNG_ACCOUNT_REQUEST_SIGN_IN_FROM_WEB_SDK.equals(action)
                        && !ACTION_SAMSUNG_ACCOUNT_REQUEST_CONFIRM_PASSWORD_FROM_WEB_SDK.equals(action)) {
                    return false;
                }

                int userId = BActivityThread.getUserId();
                if (SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage)) {
                    return false;
                }

                ResolveInfo hostResolve = NexBoxCore.getContext().getPackageManager()
                        .resolveActivity(intent, GET_META_DATA);
                if (hostResolve == null || hostResolve.activityInfo == null) {
                    return false;
                }
                return PKG_SAMSUNG_ACCOUNT.equals(hostResolve.activityInfo.packageName);
            } catch (Throwable ignored) {
            }
            return false;
        }

        private Intent maybeRewriteSamsungHealthSigninPopupToAuthenticator(Intent intent) {
            try {
                if (intent == null) {
                    return null;
                }
                String callerPackage = BActivityThread.getAppPackageName();
                if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                    return null;
                }
                if (!ACTION_SAMSUNG_ACCOUNT_SIGNIN_POPUP.equals(intent.getAction())) {
                    return null;
                }

                int userId = BActivityThread.getUserId();
                if (SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage)) {
                    return null;
                }

                // Remember which NexBox user initiated the SaSDK web sign-in so a host-level
                // sasdk:// redirect can be forwarded back into the correct virtual profile.
                SamsungHealthCompat.setLastSaSdkRedirectUserId(userId);

                // Diagnostics: log extras to keep visibility into request shape.
                logSamsungHealthSigninPopupExtras(intent);

                // Instead of trying to guess an OAuth redirect URL, delegate to Samsung Health's
                // own AuthenticatorActivity which is designed to return auth results back into
                // the app (Activity-for-result contract) without using the host Samsung Account.
                Intent authIntent = new Intent();
                authIntent.setComponent(new ComponentName(PKG_SAMSUNG_HEALTH, ACTIVITY_SAMSUNG_HEALTH_AUTHENTICATOR));
                authIntent.setPackage(PKG_SAMSUNG_HEALTH);
                authIntent.setFlags(intent.getFlags());
                if (intent.getExtras() != null) {
                    authIntent.putExtras(intent.getExtras());
                }

                // Samsung Health sets this extra when it calls AuthenticatorActivity normally.
                // Best-effort: identify the caller as AccountHandlerActivity (the component that
                // initiates SIGNIN_POPUP in current versions).
                try {
                    authIntent.putExtra("calling_activity", Class.forName(ACTIVITY_SAMSUNG_HEALTH_ACCOUNT_HANDLER));
                } catch (Throwable ignored) {
                }

                Slog.d(TAG, "Samsung Health SIGNIN_POPUP rewritten to AuthenticatorActivity: " + authIntent.getComponent());
                return authIntent;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Intent maybeRewriteSamsungHealthSaSdkToBrowser(Intent intent) {
            try {
                if (intent == null) {
                    return null;
                }
                String callerPackage = BActivityThread.getAppPackageName();
                if (!PKG_SAMSUNG_HEALTH.equals(callerPackage)) {
                    return null;
                }
                String action = intent.getAction();
                if (!ACTION_SAMSUNG_ACCOUNT_REQUEST_SIGN_IN_FROM_WEB_SDK.equals(action)
                        && !ACTION_SAMSUNG_ACCOUNT_REQUEST_CONFIRM_PASSWORD_FROM_WEB_SDK.equals(action)) {
                    return null;
                }

                int userId = BActivityThread.getUserId();
                if (SamsungHealthCompat.isHostSamsungAccountFallbackEnabled(userId, callerPackage)) {
                    return null;
                }

                // Diagnostics: capture request shape (without leaking query params / tokens).
                logSamsungHealthSaSdkExtras(intent);

                // Best-effort: if the SaSDK request carries an embedded https:// URL, open it in a
                // sandbox browser. This avoids the host SamsungAccount app signature check and
                // allows the OAuth redirect back to the sasdk:// receiver inside Samsung Health.
                Uri uri = extractBrowsableUri(intent);
                if (uri == null) {
                    return null;
                }

                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);
                viewIntent.setData(uri);
                viewIntent.setFlags(intent.getFlags());
                logSamsungAuthIntent("samsungBrowserRedirect", viewIntent);
                return viewIntent;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Uri extractBrowsableUri(Intent intent) {
            try {
                Uri data = intent.getData();
                Uri direct = tryParseBrowsableUri(data);
                if (direct != null) {
                    return direct;
                }

                Bundle extras = intent.getExtras();
                if (extras == null) {
                    return null;
                }

                // Prefer explicit/common key names first.
                Uri byKey = extractBrowsableUriFromBundle(extras, 0, true);
                if (byKey != null) {
                    return byKey;
                }

                // Fallback: scan all extras (including nested bundles) for embedded URLs.
                return extractBrowsableUriFromBundle(extras, 0, false);
            } catch (Throwable ignored) {
            }
            return null;
        }

        private Uri extractBrowsableUriFromBundle(Bundle bundle, int depth, boolean preferKnownKeys) {
            if (bundle == null || depth > 2) {
                return null;
            }
            try {
                java.util.ArrayList<String> keys = new java.util.ArrayList<>(bundle.keySet());
                // Keep the scan bounded; Samsung intents sometimes carry large bundles.
                int maxKeys = Math.min(keys.size(), 80);

                // Known keys that often carry a login URL.
                final String[] knownKeys = new String[]{
                        "url", "URL", "uri", "URI", "link", "deeplink", "deep_link",
                        "loginUrl", "login_url", "webUrl", "web_url", "web",
                        "redirect", "redirectUrl", "redirect_url", "callback", "callbackUrl", "callback_url",
                        "browser_fallback_url", "browserFallbackUrl", "fallbackUrl"
                };

                if (preferKnownKeys) {
                    for (String kk : knownKeys) {
                        if (!bundle.containsKey(kk)) {
                            continue;
                        }
                        Uri found = tryParseBrowsableUri(BundleCompat.getValue(bundle, kk));
                        if (found != null) {
                            return found;
                        }
                    }
                }

                for (int i = 0; i < maxKeys; i++) {
                    String key = keys.get(i);
                    Object value = BundleCompat.getValue(bundle, key);

                    if (value instanceof Bundle) {
                        Uri nested = extractBrowsableUriFromBundle((Bundle) value, depth + 1, preferKnownKeys);
                        if (nested != null) {
                            return nested;
                        }
                        continue;
                    }

                    Uri found = tryParseBrowsableUri(value);
                    if (found != null) {
                        return found;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private Uri tryParseBrowsableUri(Object value) {
            if (value == null) {
                return null;
            }
            if (value instanceof Uri) {
                Uri uri = (Uri) value;
                if (isBrowsableUri(uri)) {
                    return uri;
                }
                return null;
            }
            if (value instanceof String) {
                return tryParseBrowsableUriFromString((String) value);
            }
            return null;
        }

        private Uri tryParseBrowsableUri(Uri uri) {
            if (uri == null) {
                return null;
            }
            if (isBrowsableUri(uri)) {
                return uri;
            }
            return null;
        }

        private Uri tryParseBrowsableUriFromString(String str) {
            if (TextUtils.isEmpty(str)) {
                return null;
            }

            // Direct schemes.
            if (startsWithIgnoreCase(str, "http://")
                    || startsWithIgnoreCase(str, "https://")
                    || startsWithIgnoreCase(str, "intent://")) {
                Uri uri = Uri.parse(str.trim());
                if (isBrowsableUri(uri)) {
                    return uri;
                }
            }

            // Embedded URL inside a larger string.
            Uri embedded = findFirstBrowsableUriInText(str);
            if (embedded != null) {
                return embedded;
            }

            return null;
        }

        private Uri findFirstBrowsableUriInText(String text) {
            if (TextUtils.isEmpty(text)) {
                return null;
            }
            String lower = text.toLowerCase();
            int idx = indexOfAny(lower, "https://", "http://", "intent://");
            if (idx < 0) {
                return null;
            }
            String tail = text.substring(idx);
            // Stop at first whitespace or control char.
            int end = tail.length();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (Character.isWhitespace(c) || c == '\u0000') {
                    end = i;
                    break;
                }
            }
            String candidate = tail.substring(0, end).trim();
            if (TextUtils.isEmpty(candidate)) {
                return null;
            }
            try {
                Uri uri = Uri.parse(candidate);
                if (isBrowsableUri(uri)) {
                    return uri;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }

        private int indexOfAny(String haystackLower, String... needlesLower) {
            int best = -1;
            for (String needle : needlesLower) {
                if (TextUtils.isEmpty(needle)) {
                    continue;
                }
                int i = haystackLower.indexOf(needle);
                if (i >= 0 && (best < 0 || i < best)) {
                    best = i;
                }
            }
            return best;
        }

        private boolean startsWithIgnoreCase(String value, String prefix) {
            if (value == null || prefix == null) {
                return false;
            }
            if (value.length() < prefix.length()) {
                return false;
            }
            return value.regionMatches(true, 0, prefix, 0, prefix.length());
        }

        private boolean isBrowsableUri(Uri uri) {
            if (uri == null) {
                return false;
            }
            String scheme = uri.getScheme();
            if (TextUtils.isEmpty(scheme)) {
                return false;
            }
            return "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme)
                    || "intent".equalsIgnoreCase(scheme);
        }

        private void logSamsungHealthSigninPopupExtras(Intent intent) {
            logSamsungAuthIntent("samsungSigninPopup", intent);
        }

        private void logSamsungHealthSaSdkExtras(Intent intent) {
            logSamsungAuthIntent("samsungSaSdkRequest", intent);
        }

        private void logSamsungAuthIntent(String stage, Intent intent) {
            com.nexbox.core.entity.AppConfig owner = BActivityThread.getAppConfig();
            // Even a short string or a URL path can be a credential. Reuse the bounded
            // metadata logger; do not inspect or stringify authentication extra values.
            ActivityIntentLog.log(stage, intent, BActivityThread.getUserId(),
                    owner == null ? 0L : owner.userGeneration, true, "source=samsung_auth");
        }

        private void replaceIntentArg(Object[] args, Intent oldIntent, Intent newIntent) {
            if (args == null || newIntent == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] == oldIntent) {
                    args[i] = newIntent;
                    return;
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent) {
                    args[i] = newIntent;
                    return;
                }
            }
        }

        private void stripActivityResultArgs(Object[] args, Intent intent) {
            if (args == null || intent == null) {
                return;
            }

            int intentIndex = -1;
            for (int i = 0; i < args.length; i++) {
                if (args[i] == intent) {
                    intentIndex = i;
                    break;
                }
            }
            if (intentIndex < 0) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof Intent) {
                        intentIndex = i;
                        break;
                    }
                }
            }
            if (intentIndex < 0) {
                return;
            }

            boolean clearedResultTo = false;
            boolean clearedResultWho = false;
            boolean clearedRequestCode = false;
            for (int i = intentIndex + 1; i < args.length; i++) {
                Object arg = args[i];
                if (!clearedResultTo && arg instanceof IBinder) {
                    args[i] = null;
                    clearedResultTo = true;
                    continue;
                }
                if (clearedResultTo && !clearedResultWho && arg instanceof String) {
                    String resultWho = (String) arg;
                    if (!TextUtils.isEmpty(resultWho)) {
                        args[i] = null;
                        clearedResultWho = true;
                    }
                    continue;
                }
                if (clearedResultTo && !clearedRequestCode && arg instanceof Integer) {
                    args[i] = -1;
                    clearedRequestCode = true;
                    break;
                }
            }
        }
    }

    @ProxyMethod("startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int index = -1;
            for (int i = 0; args != null && i < args.length; i++) if (args[i] instanceof Intent[]) { index = i; break; }
            if (index < 0 || index + 3 >= args.length) throw new IllegalArgumentException("Invalid activity batch");
            Intent[] intents = (Intent[]) args[index];
            String[] types = (String[]) args[index + 1];
            IBinder resultTo = (IBinder) args[index + 2];
            Bundle options = (Bundle) args[index + 3];
            if (types == null || types.length != intents.length) throw new IllegalArgumentException("Invalid activity types");
            com.nexbox.core.core.system.pm.IBPackageManagerService packages = NexBoxCore.getBPackageManager().getService();
            if (packages == null) throw new IllegalStateException("Virtual package service is unavailable");
            Intent[] requests = new Intent[intents.length];
            int virtual = 0;
            ClassLoader loader = BActivityThread.getApplication() == null ? Thread.currentThread().getContextClassLoader()
                    : BActivityThread.getApplication().getClassLoader();
            for (int i = 0; i < intents.length; i++) {
                if (intents[i] == null) throw new IllegalArgumentException("Missing activity intent");
                ResolveInfo target = packages.resolveActivity(intents[i], GET_META_DATA, types[i], BActivityThread.getUserId());
                requests[i] = new Intent(intents[i]);
                if (target != null && target.activityInfo != null) {
                    virtual++;
                    requests[i].setComponent(new ComponentName(target.activityInfo.packageName, target.activityInfo.name));
                    requests[i].setExtrasClassLoader(loader);
                    com.nexbox.core.utils.IntentSanitizer.sanitizeClassExtrasForIpc(requests[i]);
                } else {
                    ResolveInfo host = NexBoxCore.getPackageManager().resolveActivity(intents[i], GET_META_DATA);
                    if (com.nexbox.core.core.GmsCompat.requiresVirtualComponent(intents[i], host == null ? null : host.activityInfo)) {
                        return getStartIntentNotResolvedCode();
                    }
                }
            }
            if (virtual == 0) return method.invoke(who, args);
            if (virtual != intents.length) return getStartIntentNotResolvedCode();
            com.nexbox.core.core.system.am.IBActivityManagerService activities = NexBoxCore.getBActivityManager().getService();
            if (activities == null) throw new IllegalStateException("Virtual activity service is unavailable");
            return activities.startActivities(BActivityThread.getUserId(), requests, types, resultTo, options);
        }
    }

    @ProxyMethod("startIntentSenderForResult")
    public static class StartIntentSenderForResult extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NexBoxCore.getBActivityManager().onActivityResumed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NexBoxCore.getBActivityManager().onActivityDestroyed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            NexBoxCore.getBActivityManager().onFinishActivity((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAppTasks")
    public static class GetAppTasks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getCallingPackage")
    public static class getCallingPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return NexBoxCore.getBActivityManager().getCallingPackage((IBinder) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getCallingActivity")
    public static class getCallingActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return NexBoxCore.getBActivityManager().getCallingActivity((IBinder) args[0], BActivityThread.getUserId());
        }
    }
}
