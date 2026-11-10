







#include <cstring>
#include <atomic>
#include "VMClassLoaderHook.h"
#import "JniHook/JniHook.h"
static std::atomic<bool> hideXposedClass{false};

HOOK_JNI(jobject, findLoadedClass, JNIEnv *env, jobject obj, jobject class_loader, jstring name) {
    if (!hideXposedClass.load(std::memory_order_acquire) || name == nullptr) {
        return orig_findLoadedClass(env, obj, class_loader, name);
    }
    const char * nameC = env->GetStringUTFChars(name, JNI_FALSE);
    if (nameC == nullptr) return nullptr;

    if (strstr(nameC, "de/robv/android/xposed/") ||
            strstr(nameC, "me/weishu/epic") ||
            strstr(nameC, "me/weishu/exposed") ||
            strstr(nameC, "de.robv.android") ||
            strstr(nameC, "me.weishu.epic") ||
            strstr(nameC, "me.weishu.exposed")) {
        env->ReleaseStringUTFChars(name, nameC);
        return nullptr;
    }
    env->ReleaseStringUTFChars(name, nameC);
    return orig_findLoadedClass(env, obj, class_loader, name);
}

void VMClassLoaderHook::init(JNIEnv *env) {
    const char *className = "java/lang/VMClassLoader";
    JniHook::HookJniFun(env, className, "findLoadedClass", "(Ljava/lang/ClassLoader;Ljava/lang/String;)Ljava/lang/Class;",
                        (void *) new_findLoadedClass,
                        (void **) (&orig_findLoadedClass), true);
}

void VMClassLoaderHook::hideXposed() {
    hideXposedClass.store(true, std::memory_order_release);
}
