package com.nexbox.core.utils;

import android.content.AttributionSource;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import black.android.content.BRAttributionSource;
import com.nexbox.core.NexBoxCore;
import com.nexbox.core.app.BActivityThread;
import com.nexbox.core.utils.Slog;


public class AttributionSourceUtils {
    private static final String TAG = "AttributionSourceUtils";

    /**
     * Copy a verified IContentProvider calling-identity slot to the real Binder caller.
     * Existing compatibility helpers below intentionally keep their separate call contracts.
     * Never use AttributionSource.CREATOR here: it verifies the Binder calling UID, and newer
     * versions additionally require an incoming Binder transaction. Its State codec has no such
     * boundary check and preserves this platform version's complete fields and next chain.
     */
    public static Object copyForProviderCaller(Object source, String callerPackage, int callerUid) {
        return copyProviderIdentity(source, callerPackage, callerUid, false, false);
    }

    /** Guest transports keep a declared tag but use the current sending guest, not its launcher. */
    public static Object copyForGuestProviderCaller(Object source, String callerPackage, int callerUid) {
        return copyProviderIdentity(source, callerPackage, callerUid, true, false);
    }

    public static Object copyForGuestOnewayProviderCaller(Object source, String callerPackage, int callerUid) {
        return copyProviderIdentity(source, callerPackage, callerUid, true, true);
    }

    public static Object copyForOnewayProviderCaller(Object source, String callerPackage, int callerUid) {
        return copyProviderIdentity(source, callerPackage, callerUid, false, true);
    }

    private static Object copyProviderIdentity(Object source, String callerPackage, int callerUid,
            boolean guest, boolean unknownPid) {
        if (!(source instanceof AttributionSource)
                || !"android.content.AttributionSource".equals(source.getClass().getName())) {
            throw new IllegalArgumentException("Expected a platform AttributionSource identity");
        }
        if (callerPackage == null || callerPackage.isEmpty() || callerUid < 0) {
            throw new IllegalArgumentException("A provider caller identity is required");
        }
        try {
            if (BRAttributionSource.get(source)._check_mAttributionSourceState() == null) {
                throw new IllegalStateException("AttributionSource state is not accessible");
            }
            Object state = BRAttributionSource.get(source).mAttributionSourceState();
            if (!(state instanceof Parcelable)
                    || !"android.content.AttributionSourceState".equals(state.getClass().getName())) {
                throw new IllegalStateException("Unexpected AttributionSource state layout");
            }

            Class<?> stateType = state.getClass();
            Field creatorField = stateType.getField("CREATOR");
            creatorField.setAccessible(true);
            Object codec = creatorField.get(null);
            if (!(codec instanceof Parcelable.Creator<?>)) {
                throw new IllegalStateException("AttributionSource state codec is unavailable");
            }
            Object copiedState;
            Parcel parcel = Parcel.obtain();
            try {
                ((Parcelable) state).writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                copiedState = ((Parcelable.Creator<?>) codec).createFromParcel(parcel);
                if (copiedState == null || copiedState == state || copiedState.getClass() != stateType
                        || parcel.dataPosition() != parcel.dataSize()) {
                    throw new IllegalStateException("Could not copy complete AttributionSource state");
                }
            } finally {
                parcel.recycle();
            }

            // These are the exact AIDL fields, not guessed fields on AttributionSource itself.
            Field uid = stateType.getField("uid");
            Field packageName = stateType.getField("packageName");
            Field attributionTag = stateType.getField("attributionTag");
            if (uid.getType() != int.class || packageName.getType() != String.class
                    || attributionTag.getType() != String.class) {
                throw new IllegalStateException("Unexpected AttributionSource identity field types");
            }
            uid.setAccessible(true);
            packageName.setAccessible(true);
            attributionTag.setAccessible(true);
            uid.setInt(copiedState, callerUid);
            packageName.set(copiedState, callerPackage);
            AttributionSource original = (AttributionSource) source;
            String tag = guest && callerPackage.equals(original.getPackageName())
                    ? original.getAttributionTag() : null;
            attributionTag.set(copiedState, tag);
            // A forwarded guest head may still carry the previous hop's PID. Never copy that
            // PID into a new outgoing hop. Preserve an unspecified PID (also needed by oneway
            // calls); older Android State layouts have no PID field.
            if (guest || unknownPid) {
                try {
                    Field pid = stateType.getField("pid");
                    if (pid.getType() != int.class) throw new IllegalStateException("Unexpected attribution PID type");
                    pid.setAccessible(true);
                    if (unknownPid) pid.setInt(copiedState, -1); // Platform INVALID_PID for oneway.
                    else if (pid.getInt(copiedState) > 0) pid.setInt(copiedState, Process.myPid());
                } catch (NoSuchFieldException ignored) { }
            }
            // Token, deviceId, renounced permissions and every next node remain unchanged.

            Constructor<?> constructor = source.getClass().getDeclaredConstructor(stateType);
            constructor.setAccessible(true);
            AttributionSource copy = (AttributionSource) constructor.newInstance(copiedState);
            if (copy == source || copy.getUid() != callerUid
                    || !callerPackage.equals(copy.getPackageName())
                    || !java.util.Objects.equals(tag, copy.getAttributionTag())
                    || BRAttributionSource.get(copy).mAttributionSourceState() != copiedState) {
                throw new IllegalStateException("Mapped provider identity failed read-back verification");
            }
            return copy;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not copy provider AttributionSource identity", failure);
        }
    }

    private static String getAttributionPackageName() {
        try {
            String appPackageName = BActivityThread.getAppPackageName();
            if (appPackageName != null && !appPackageName.isEmpty()) {
                return appPackageName;
            }
        } catch (Throwable ignored) {
        }
        return NexBoxCore.getHostPkg();
    }

    
    public static void fixAttributionSourceInArgs(Object[] args) {
        if (args == null) return;
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("AttributionSource")) {
                try {
                    fixAttributionSourceUid(arg);
                    Slog.d(TAG, "Fixed AttributionSource UID in method arguments");
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in args: " + e.getMessage(), e);
                }
            }
        }
        
        
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && arg.getClass().getName().contains("Bundle")) {
                try {
                    fixAttributionSourceInBundle(arg);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to fix AttributionSource in Bundle: " + e.getMessage(), e);
                }
            }
        }
    }

    
    public static void fixAttributionSourceUid(Object attributionSource) {
        try {
            if (attributionSource == null) return;
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            
            
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    uidField.set(attributionSource, NexBoxCore.getHostUid());
                    Slog.d(TAG, "Fixed AttributionSource UID via field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    
                }
            }
            
            
            try {
                java.lang.reflect.Method setUidMethod = attributionSourceClass.getDeclaredMethod("setUid", int.class);
                setUidMethod.setAccessible(true);
                setUidMethod.invoke(attributionSource, NexBoxCore.getHostUid());
                Slog.d(TAG, "Fixed AttributionSource UID via setter method");
            } catch (Exception e) {
                
            }
            
            
            String[] packageFieldNames = {"mPackageName", "packageName", "mSourcePackage", "sourcePackage"};
            
            for (String fieldName : packageFieldNames) {
                try {
                    java.lang.reflect.Field packageField = attributionSourceClass.getDeclaredField(fieldName);
                    packageField.setAccessible(true);
                    packageField.set(attributionSource, getAttributionPackageName());
                    Slog.d(TAG, "Fixed AttributionSource package name via field: " + fieldName);
                    break;
                } catch (NoSuchFieldException e) {
                    
                }
            }
            
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource UID: " + e.getMessage(), e);
        }
    }

    
    public static void fixAttributionSourceInBundle(Object bundle) {
        try {
            if (bundle == null) return;
            
            
            java.lang.reflect.Method keySetMethod = bundle.getClass().getMethod("keySet");
            java.util.Set<?> keys = (java.util.Set<?>) keySetMethod.invoke(bundle);
            
            for (Object candidate : keys) {
                String key = String.class.cast(candidate);
                try {
                    java.lang.reflect.Method getMethod = bundle.getClass().getMethod("get", String.class);
                    Object value = getMethod.invoke(bundle, key);
                    
                    if (value != null && value.getClass().getName().contains("AttributionSource")) {
                        fixAttributionSourceUid(value);
                        Slog.d(TAG, "Fixed AttributionSource UID in Bundle key: " + key);
                    }
                } catch (Exception e) {
                    
                }
            }
        } catch (Exception e) {
            Slog.w(TAG, "Error fixing AttributionSource in Bundle: " + e.getMessage(), e);
        }
    }

    
    public static Object createSafeAttributionSource() {
        try {
            
            Class<?> attributionSourceClass = Class.forName("android.content.AttributionSource");
            
            
            Object attributionSource = null;
            
            try {
                
                java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor(int.class, String.class);
                constructor.setAccessible(true);
                attributionSource = constructor.newInstance(NexBoxCore.getHostUid(), getAttributionPackageName());
            } catch (Exception e) {
                try {
                    
                    java.lang.reflect.Constructor<?> constructor = attributionSourceClass.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    attributionSource = constructor.newInstance();
                    
                    
                    fixAttributionSourceUid(attributionSource);
                } catch (Exception e2) {
                    Slog.w(TAG, "Could not create safe AttributionSource: " + e2.getMessage(), e2);
                    return null;
                }
            }
            
            return attributionSource;
        } catch (Exception e) {
            Slog.w(TAG, "Error creating safe AttributionSource: " + e.getMessage(), e);
            return null;
        }
    }

    
    public static boolean validateAttributionSource(Object attributionSource) {
        try {
            if (attributionSource == null) return false;
            
            
            Class<?> attributionSourceClass = attributionSource.getClass();
            String[] uidFieldNames = {"mUid", "uid", "mCallingUid", "callingUid", "mSourceUid", "sourceUid"};
            
            for (String fieldName : uidFieldNames) {
                try {
                    java.lang.reflect.Field uidField = attributionSourceClass.getDeclaredField(fieldName);
                    uidField.setAccessible(true);
                    Object uidValue = uidField.get(attributionSource);
                    if (uidValue instanceof Integer) {
                        int uid = (Integer) uidValue;
                        if (uid > 0) {
                            Slog.d(TAG, "AttributionSource UID validation passed: " + uid);
                            return true;
                        }
                    }
                } catch (Exception e) {
                    
                }
            }
            
            
            Slog.w(TAG, "AttributionSource validation failed, attempting to fix");
            fixAttributionSourceUid(attributionSource);
            return true;
            
        } catch (Exception e) {
            Slog.w(TAG, "Error validating AttributionSource: " + e.getMessage(), e);
            return false;
        }
    }
}
