package com.nexbox.core.utils.compat;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Process;
import android.text.TextUtils;
import android.util.*;

import black.android.app.BRContextImpl;
import black.android.app.BRContextImplKitkat;
import black.android.content.AttributionSourceStateContext;
import black.android.content.BRAttributionSource;
import black.android.content.BRAttributionSourceState;
import black.android.content.BRContentResolver;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.fake.service.IClipboardStub;
import com.nexbox.core.fake.service.SystemTextServicesHook;
import com.nexbox.core.utils.Slog;


public class ContextCompat {
    public static final String TAG = "ContextCompat";

    public static void fixAttributionSourceState(Object obj, String packageName, int uid) {
        Object mAttributionSourceState;
        if (obj != null && BRAttributionSource.get(obj)._check_mAttributionSourceState() != null) {
            mAttributionSourceState = BRAttributionSource.get(obj).mAttributionSourceState();

            AttributionSourceStateContext attributionSourceStateContext = BRAttributionSourceState.get(mAttributionSourceState);
            attributionSourceStateContext._set_packageName(packageName);
            attributionSourceStateContext._set_uid(uid);
            // A next node belongs to another attribution participant, not to this Context.
            // Provider transport heads are copied/remapped separately for SYSTEM and GUEST.
        }
    }

    public static void fix(Context context) {
        try {
            
            if (context == null) {
                Slog.w(TAG, "Context is null, skipping ContextCompat.fix");
                return;
            }
            
            int deep = 0;
            while (context instanceof ContextWrapper) {
                context = ((ContextWrapper) context).getBaseContext();
                deep++;
                if (deep >= 10) {
                    return;
                }
            }
            
            
            if (context == null) {
                Slog.w(TAG, "Base context is null after unwrapping, skipping ContextCompat.fix");
                return;
            }
            
            BRContextImpl.get(context)._set_mPackageManager(null);
            try {
                context.getPackageManager();
            } catch (Throwable e) {
                Slog.e("ContextCompat", "Container operation failed", e);
            }

            String contextPkg = null;
            try {
                contextPkg = context.getPackageName();
            } catch (Throwable ignored) {
            }

            String threadPkg = null;
            try {
                threadPkg = BActivityThread.getAppPackageName();
            } catch (Throwable ignored) {
            }

            String hostPkg = NexBoxCore.getHostPkg();
            String pkg;
            if (!TextUtils.isEmpty(contextPkg) && !TextUtils.equals(contextPkg, hostPkg)) {
                pkg = contextPkg;
            } else if (!TextUtils.isEmpty(threadPkg)) {
                pkg = threadPkg;
            } else if (!TextUtils.isEmpty(contextPkg)) {
                pkg = contextPkg;
            } else {
                pkg = hostPkg;
            }

            BRContextImpl.get(context)._set_mBasePackageName(pkg);
            BRContextImplKitkat.get(context)._set_mOpPackageName(pkg);

            // Android 14+/16 work profiles are sensitive to userId mismatches.
            // Ensure ContextImpl user fields reflect the real Android host user.
            //
            // IMPORTANT: do not derive the "host user" from Process.myUid() here, because in guest
            // processes Process/Os hooks may already virtualize it to the NexBox user (e.g. 2),
            // which then crashes modern WindowManager with "requested userId is not valid".
            int hostUserId = 0;
            try {
                Context hostContext = NexBoxCore.getContext();
                if (hostContext != null && hostContext.getApplicationInfo() != null && hostContext.getApplicationInfo().uid > 0) {
                    hostUserId = hostContext.getApplicationInfo().uid / 100000;
                }
            } catch (Throwable ignored) {
            }
            if (hostUserId <= 0) {
                hostUserId = NexBoxCore.getHostUserId();
            }
            if (hostUserId <= 0) {
                int hostUid = NexBoxCore.getHostUid();
                if (hostUid > 0) {
                    hostUserId = hostUid / 100000;
                }
            }
            if (hostUserId > 0) {
                forceContextUser(context, hostUserId);
            }
            
            try {
                BRContentResolver.get(context.getContentResolver())._set_mPackageName(pkg);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to fix content resolver: " + e.getMessage(), e);
            }

            try {
                int uid = -1;
                try {
                    uid = BActivityThread.getUid();
                } catch (Throwable ignored) {
                }
                if (uid <= 0) {
                    uid = NexBoxCore.getHostUid();
                }

                fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), pkg, uid);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to fix attribution source state: " + e.getMessage(), e);
            }
            IClipboardStub.fixContext(context);
            SystemTextServicesHook.fixContext(context);
        } catch (Exception e) {
            Slog.e(TAG, "Error in ContextCompat.fix: " + e.getMessage(), e);
            Slog.e("ContextCompat", "Container operation failed", e);
        }
        // AccountManager instances created before the Binder hook must not retain the real service.
        com.nexbox.core.fake.service.IAccountManagerProxy.fixContext(context);
        com.nexbox.core.fake.service.IBlobStoreManagerProxy.fixContext(context);
        com.nexbox.core.fake.service.ILocaleManagerProxy.fixContext(context);
        com.nexbox.core.app.GuestLocaleController.apply(context);
    }

    private static void forceContextUser(Context context, int userId) {
        try {
            Class<?> c = context.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mUser");
                    f.setAccessible(true);
                    Object userHandle = buildUserHandle(userId);
                    if (userHandle != null) {
                        f.set(context, userHandle);
                    }
                    break;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> c = context.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField("mUserId");
                    f.setAccessible(true);
                    f.setInt(context, userId);
                    break;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object buildUserHandle(int userId) {
        try {
            Class<?> clazz = Class.forName("android.os.UserHandle");
            java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return ctor.newInstance(userId);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
