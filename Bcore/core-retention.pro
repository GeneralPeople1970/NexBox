# Keep all dynamic Android/JNI/Binder/Xposed boundaries. Only these directly referenced,
# non-Parcelable implementation helpers may be optimized, renamed or removed by R8.
# This one file is used by the library shrinker and exported to its consumers.
-keep class !com.nexbox.core.core.system.pm.InstallRequestPolicy,
            !com.nexbox.core.core.system.accounts.AccountWritePolicy,
            !com.nexbox.core.core.system.accounts.AccountWritePolicy$*,
            !com.nexbox.core.core.system.pm.CloneGroupSummary,
            !com.nexbox.core.core.system.pm.CloneGroupSummary$*,
            !com.nexbox.core.core.system.location.LocationBatch,
            !com.nexbox.core.core.system.location.LocationBatch$*,
            !com.nexbox.core.core.system.location.LocationTargetCache,
            !com.nexbox.core.core.system.location.LocationTargetCache$*,
            com.nexbox.core.** { *; }

# Bcore and app both run R8. Keep the member-access entry points and calibrated
# helper layouts intact through both passes, including the consumer-rule match.
-keep,allowoptimization class org.lsposed.hiddenapibypass.HiddenApiBypass {
    public static java.util.List getInstanceFields(java.lang.Class);
    public static java.lang.reflect.Constructor getDeclaredConstructor(java.lang.Class,java.lang.Class[]);
}
-keep class org.lsposed.hiddenapibypass.Helper$* { *; }
