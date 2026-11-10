



#ifndef VIRTUAL_APP_JNIHOOK_H
#define VIRTUAL_APP_JNIHOOK_H

#include "ArtMethod.h"

#define HOOK_JNI(ret, func, ...) \
  ret (*orig_##func)(__VA_ARGS__); \
  ret new_##func(__VA_ARGS__)

class JniHook {
public:
    // Uses the Android 12+ ART path; this is the host OS baseline, not the guest targetSdk.
    static void InitJniHook(JNIEnv *env);
    static void HookJniFun(JNIEnv *env, const char *class_name, const char *method_name, const char *sign, void * new_fun, void ** orig_fun,
                           bool is_static, void *critical_fun = nullptr, void **orig_critical_fun = nullptr);
    static void HookJniFun(JNIEnv *env, jobject java_method, void *new_fun, void **orig_fun, bool is_static,
                           void *critical_fun = nullptr, void **orig_critical_fun = nullptr);
};

#endif 
