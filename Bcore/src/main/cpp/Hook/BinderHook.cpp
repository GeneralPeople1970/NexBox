



#include "BinderHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"



HOOK_JNI(jint, getCallingUid, JNIEnv *env, jclass obj) {
    int orig = orig_getCallingUid(env, obj);
    return BoxCore::getCallingUid(env, orig);
}

static jint (*orig_getCallingUidCritical)() = nullptr;

static jint getCallingUidCritical() {
    const jint orig = orig_getCallingUidCritical();
    // There are no implicit JNI arguments on this ART path. Only use the existing
    // attached thread; attaching here would change the Binder caller's lifecycle.
    JNIEnv *env = nullptr;
    JavaVM *vm = BoxCore::getJavaVM();
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK
            || env == nullptr) {
        return orig;
    }
    return BoxCore::getCallingUid(env, orig);
}


void BinderHook::init(JNIEnv *env) {
    const char *clazz = "android/os/Binder";
    JniHook::HookJniFun(env, clazz, "getCallingUid", "()I", (void *) new_getCallingUid,
                        (void **) (&orig_getCallingUid), true,
                        reinterpret_cast<void *>(getCallingUidCritical),
                        reinterpret_cast<void **>(&orig_getCallingUidCritical));
}
