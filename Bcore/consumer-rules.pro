-keep class com.nexbox.core.jnihook.** {*; }
-keep class mirror.** {*; }
-keep class android.** {*; }
-keep class com.android.** {*; }

# Module APKs compile their own copy of the official service interface. Keep Binder ABI names.
-keep class io.github.libxposed.service.** { *; }
