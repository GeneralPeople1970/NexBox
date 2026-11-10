package com.nexbox.core.xposed;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.telephony.TelephonyManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.widget.Toast;

import com.nexbox.xposed.runtime.R;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Built-in, guest-only capability spoofing and local activation-code copy, using HookEuicc's
 * DownloadableSubscription interception points. No LPA, automatic sharing or OMAPI bypass.
 */
public final class EuiccSpoofer {
    static final String SERVICE_ACTION = "android.service.euicc.EuiccService";
    private static final String SERVICE_PACKAGE = "com.nexbox.virtual.euicc";
    private static final String EXTRA_ACTIVATION_CODE = "android.telephony.euicc.extra.ACTIVATION_CODE";
    private static boolean installed;
    private static volatile Context clipboardContext;
    private static byte[] lastCopiedDigest;
    private static long lastCopyTime;

    private EuiccSpoofer() {}

    /** Caller must be binding a virtual application; no external Xposed module is needed. */
    public static synchronized boolean install() {
        return install(null);
    }

    /** The retained context is the host context, never the guest Application/Activity context. */
    public static synchronized boolean install(Context hostContext) {
        if (hostContext != null) clipboardContext = hostContext;
        if (installed) return true;
        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            if (!NativeBridge.initializeGuest()) throw new IllegalStateException(NativeBridge.getError());
            Class<?> packageManager = Class.forName("android.app.ApplicationPackageManager");
            hookOverloads(hooks, packageManager, "hasSystemFeature", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (PackageManager.FEATURE_TELEPHONY_EUICC.equals(param.args[0])) {
                        param.setResult(true);
                    }
                }
            });
            hooks.add(XposedBridge.hookMethod(EuiccManager.class.getDeclaredMethod("isEnabled"),
                    constant(true)));
            hooks.add(XposedBridge.hookMethod(TelephonyManager.class.getDeclaredMethod("getCardIdForDefaultEuicc"),
                    constant(0)));
            hookOverloads(hooks, packageManager, "queryIntentServices", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    Object result = param.getResult();
                    if (result == null || result instanceof List<?>) {
                        param.setResult(servicesFor((Intent) param.args[0], (List<?>) result));
                    }
                }
            });
            hooks.add(XposedBridge.hookMethod(DownloadableSubscription.class.getDeclaredMethod(
                    "forActivationCode", String.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) copyActivationCode((String) param.args[0]);
                }
            }));
            hooks.add(XposedBridge.hookMethod(DownloadableSubscription.class.getDeclaredMethod(
                    "getEncodedActivationCode"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable() && param.getResult() instanceof String) {
                        copyActivationCode((String) param.getResult());
                    }
                }
            }));
            // Also cover subscriptions received via a Parcel, without a local factory invocation.
            hookOverloads(hooks, EuiccManager.class, "downloadSubscription", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object argument : param.args) {
                        if (argument instanceof DownloadableSubscription) {
                            copyActivationCode(((DownloadableSubscription) argument).getEncodedActivationCode());
                        }
                    }
                    // Preserve the real result/callback. Copying a code is not a successful install.
                }
            });
            // ContextImpl covers Activity/context starts and Intent[] variants, on all supported
            // Android versions, without retaining Activity objects or auto-opening a share sheet.
            Class<?> contextImpl = Class.forName("android.app.ContextImpl");
            XC_MethodHook outgoingIntent = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (Object argument : param.args) {
                        if (argument instanceof Intent) copyFromInstallIntent((Intent) argument);
                        else if (argument instanceof Intent[]) {
                            for (Intent intent : (Intent[]) argument) copyFromInstallIntent(intent);
                        }
                    }
                }
            };
            hookOverloads(hooks, contextImpl, "startActivity", outgoingIntent);
            hookOverloads(hooks, contextImpl, "startActivities", outgoingIntent);
            // Activity's single- and multi-Intent entry points use Instrumentation directly,
            // not ContextImpl. Observe both without consuming the launch or changing results.
            hookOverloads(hooks, android.app.Instrumentation.class, "execStartActivity", outgoingIntent);
            hookOverloads(hooks, android.app.Instrumentation.class, "execStartActivities", outgoingIntent);
            installed = true;
            return true;
        } catch (Throwable failure) {
            // A failed installation must not leave a device reporting only half the capabilities.
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            RuntimeLog.e("NexBox-eSIM", "Unable to install guest eSIM spoofing", failure);
            return false;
        }
    }

    private static void copyFromInstallIntent(Intent intent) {
        if (intent == null) return;
        try {
            String action = intent.getAction();
            boolean euiccAction = action != null && (action.startsWith("android.telephony.euicc.action.")
                    || action.startsWith("android.service.euicc.action."));
            boolean lpaUri = intent.getData() != null && "lpa".equalsIgnoreCase(intent.getData().getScheme());
            if (!euiccAction && !(Intent.ACTION_VIEW.equals(action) && lpaUri)) return;
            if (euiccAction) copyActivationCode(intent.getStringExtra(EXTRA_ACTIVATION_CODE));
            // Some LPAs accept an LPA URI instead of an activation-code extra.
            if (lpaUri) {
                copyActivationCode(intent.getDataString());
            }
        } catch (RuntimeException failure) {
            // Malformed parcelables are owned by the original call; never leak their contents.
            RuntimeLog.w("NexBox-eSIM", "Could not inspect an eSIM install intent");
        }
    }

    private static void copyActivationCode(String candidate) {
        Context context = clipboardContext;
        if (context == null || candidate == null) return;
        String code = candidate.trim();
        if (code.isEmpty() || code.length() > 4096) return;
        Runnable copy = () -> {
            ClipboardManager clipboard;
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8));
                synchronized (EuiccSpoofer.class) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastCopyTime < 1500 && Arrays.equals(lastCopiedDigest, digest)) return;
                    clipboard = context.getSystemService(ClipboardManager.class);
                    if (clipboard == null) {
                        RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=copyFailed reason=clipboardUnavailable");
                        return;
                    }
                    ClipData clip = ClipData.newPlainText("eSIM", code);
                    PersistableBundle extras = new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
                    clip.getDescription().setExtras(extras);
                    clipboard.setPrimaryClip(clip);
                    // Retain only a digest for burst deduplication, not the activation code itself.
                    // A failed write remains retryable; a denied read-back must not repeat writes.
                    lastCopiedDigest = digest;
                    lastCopyTime = now;
                }
            } catch (Exception failure) {
                // Framework exceptions can include their inputs; log no message, code or stack.
                RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=copyFailed reason="
                        + failure.getClass().getSimpleName());
                return;
            }
            confirmCopyAndShowToast(context, clipboard, code);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) copy.run();
        else if (!new Handler(Looper.getMainLooper()).post(copy)) {
            RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=copyDispatchFailed reason=mainLooperUnavailable");
        }
    }

    private static void confirmCopyAndShowToast(Context context, ClipboardManager clipboard, String code) {
        try {
            // A void clipboard write can be silently denied. Compare text once without coercing
            // URI/Intent payloads, retaining clipboard contents or bypassing foreground access.
            ClipData copied = clipboard.getPrimaryClip();
            CharSequence copiedText = copied != null && copied.getItemCount() == 1
                    ? copied.getItemAt(0).getText() : null;
            if (copiedText == null || !code.contentEquals(copiedText)) {
                RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=copyUnconfirmed reason=clipboardUnavailableOrChanged");
                return;
            }
        } catch (RuntimeException failure) {
            RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=copyUnconfirmed reason="
                    + failure.getClass().getSimpleName());
            return;
        }
        RuntimeLog.i("NexBox-eSIM", "action=copyActivationCode stage=copyConfirmed");
        try {
            // The host configuration is independent of a guest's requested/default locale.
            Toast.makeText(context, context.getText(R.string.esim_activation_code_copied), Toast.LENGTH_SHORT).show();
            RuntimeLog.d("NexBox-eSIM", "action=copyActivationCode stage=toastRequested");
        } catch (RuntimeException failure) {
            RuntimeLog.w("NexBox-eSIM", "action=copyActivationCode stage=toastFailed reason="
                    + failure.getClass().getSimpleName());
        }
    }

    private static XC_MethodHook constant(Object value) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(value);
            }
        };
    }

    private static void hookOverloads(List<XC_MethodHook.Unhook> hooks, Class<?> owner,
            String name, XC_MethodHook callback) throws NoSuchMethodException {
        int before = hooks.size();
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(name)) hooks.add(XposedBridge.hookMethod(method, callback));
        }
        if (hooks.size() == before) throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    static List<?> servicesFor(Intent intent, List<?> existing) {
        if (intent == null || !SERVICE_ACTION.equals(intent.getAction())
                || (existing != null && !existing.isEmpty())
                || intent.getComponent() != null
                || (intent.getPackage() != null && !SERVICE_PACKAGE.equals(intent.getPackage()))) {
            return existing;
        }
        ServiceInfo service = new ServiceInfo();
        service.packageName = SERVICE_PACKAGE;
        service.name = SERVICE_PACKAGE + ".EuiccService";
        service.permission = "android.permission.BIND_EUICC_SERVICE";
        service.enabled = true;
        service.exported = true;
        service.applicationInfo = new ApplicationInfo();
        service.applicationInfo.packageName = SERVICE_PACKAGE;
        service.applicationInfo.enabled = true;
        ResolveInfo info = new ResolveInfo();
        info.serviceInfo = service;
        return Collections.singletonList(info);
    }
}
