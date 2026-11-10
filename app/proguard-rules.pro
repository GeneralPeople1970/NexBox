-keep class com.nexbox.core.jnihook.** {*; }
-keep class mirror.** {*; }
-keep class android.** {*; }
-keep class com.android.** {*; }

-keep class com.nexbox.reflection.** {*; }
-keep @com.nexbox.reflection.annotation.BClass class * {*;}
-keep @com.nexbox.reflection.annotation.BClassName class * {*;}
-keep @com.nexbox.reflection.annotation.BClassNameNotProcess class * {*;}
-keepclasseswithmembernames class * {
    @com.nexbox.reflection.annotation.BField.* <methods>;
    @com.nexbox.reflection.annotation.BFieldNotProcess.* <methods>;
    @com.nexbox.reflection.annotation.BFieldSetNotProcess.* <methods>;
    @com.nexbox.reflection.annotation.BFieldCheckNotProcess.* <methods>;
    @com.nexbox.reflection.annotation.BMethod.* <methods>;
    @com.nexbox.reflection.annotation.BStaticField.* <methods>;
    @com.nexbox.reflection.annotation.BStaticMethod.* <methods>;
    @com.nexbox.reflection.annotation.BMethodCheckNotProcess.* <methods>;
    @com.nexbox.reflection.annotation.BConstructor.* <methods>;
    @com.nexbox.reflection.annotation.BConstructorNotProcess.* <methods>;
}
