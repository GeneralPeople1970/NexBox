# Module APKs link these APIs by their binary names; JNI calls callback reflectively.
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.api.** { *; }
-keep class android.app.AndroidAppHelper { *; }
-keep class android.content.res.XResources { *; }
-keep class com.nexbox.core.xposed.** { *; }
