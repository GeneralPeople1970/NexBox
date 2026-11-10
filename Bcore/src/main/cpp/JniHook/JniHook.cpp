#include <jni.h>
#include <cstring>
#include <new>
#include <string>
#include "JniHook.h"
#include "Log.h"
#include "ArtMethod.h"

static struct {
    size_t art_field_size;
    size_t art_field_flags_offset;
    size_t art_method_size;
    size_t art_method_flags_offset;
    size_t art_method_native_offset;
    jclass method_utils_class;
    jmethodID get_method_desc_id;
    jmethodID get_method_declaring_class_id;
    jmethodID get_method_name_id;
    bool initialized;
} HookEnv;

static void hookFailure(JNIEnv *env, const char *message) {
    ALOGE("JniHook: %s", message);
    if (env->ExceptionCheck()) return;
    jclass failure = env->FindClass("java/lang/IllegalStateException");
    if (failure != nullptr) {
        env->ThrowNew(failure, message);
        env->DeleteLocalRef(failure);
    }
}

static bool methodText(JNIEnv *env, jobject method, jmethodID accessor, std::string &value) {
    auto text = static_cast<jstring>(env->CallStaticObjectMethod(
            HookEnv.method_utils_class, accessor, method));
    if (text == nullptr || env->ExceptionCheck()) return false;
    const char *characters = env->GetStringUTFChars(text, nullptr);
    if (characters == nullptr) {
        env->DeleteLocalRef(text);
        return false;
    }
    try {
        value.assign(characters);
    } catch (const std::bad_alloc &) {
        env->ReleaseStringUTFChars(text, characters);
        env->DeleteLocalRef(text);
        jclass failure = env->FindClass("java/lang/OutOfMemoryError");
        if (failure != nullptr) {
            env->ThrowNew(failure, "Could not copy JNI hook metadata");
            env->DeleteLocalRef(failure);
        }
        return false;
    }
    env->ReleaseStringUTFChars(text, characters);
    env->DeleteLocalRef(text);
    return true;
}

static uint32_t accessFlags(const void *record, size_t offset) {
    uint32_t flags;
    std::memcpy(&flags, static_cast<const char *>(record) + offset, sizeof(flags));
    return flags;
}

static void setAccessFlags(void *record, size_t offset, uint32_t flags) {
    std::memcpy(static_cast<char *>(record) + offset, &flags, sizeof(flags));
}

// All callers run on Android 12+, independently of the cloned app's targetSdk.
static void *GetArtMethod(JNIEnv *env, jobject method) {
    if (method == nullptr || env->ExceptionCheck()) return nullptr;
    jclass executable = env->FindClass("java/lang/reflect/Executable");
    if (executable == nullptr) return nullptr;
    jfieldID artId = env->GetFieldID(executable, "artMethod", "J");
    if (artId == nullptr) {
        env->DeleteLocalRef(executable);
        return nullptr;
    }
    jlong pointer = env->GetLongField(method, artId);
    env->DeleteLocalRef(executable);
    return env->ExceptionCheck() ? nullptr : reinterpret_cast<void *>(pointer);
}

static void *GetArtMethod(JNIEnv *env, jclass clazz, jmethodID id, bool isStatic) {
    if (id == nullptr || env->ExceptionCheck()) return nullptr;
    jobject method = env->ToReflectedMethod(clazz, id, isStatic);
    if (method == nullptr) return nullptr;
    void *pointer = GetArtMethod(env, method);
    env->DeleteLocalRef(method);
    return pointer;
}

static void *GetFieldMethod(JNIEnv *env, jobject field) {
    if (field == nullptr || env->ExceptionCheck()) return nullptr;
    jclass fieldClass = env->FindClass("java/lang/reflect/Field");
    if (fieldClass == nullptr) return nullptr;
    jmethodID getArtField = env->GetMethodID(fieldClass, "getArtField", "()J");
    if (getArtField == nullptr) {
        env->DeleteLocalRef(fieldClass);
        return nullptr;
    }
    jlong pointer = env->CallLongMethod(field, getArtField);
    env->DeleteLocalRef(fieldClass);
    return env->ExceptionCheck() ? nullptr : reinterpret_cast<void *>(pointer);
}

void JniHook::HookJniFun(JNIEnv *env, jobject java_method, void *new_fun,
                       void **orig_fun, bool is_static, void *critical_fun, void **orig_critical_fun) {
    if (env->ExceptionCheck()) return;
    if (!HookEnv.initialized) {
        hookFailure(env, "ART JNI hook layout has not been initialized");
        return;
    }
    std::string className;
    std::string methodName;
    std::string signature;
    if (!methodText(env, java_method, HookEnv.get_method_declaring_class_id, className)
            || !methodText(env, java_method, HookEnv.get_method_name_id, methodName)
            || !methodText(env, java_method, HookEnv.get_method_desc_id, signature)) return;
    HookJniFun(env, className.c_str(), methodName.c_str(), signature.c_str(), new_fun,
               orig_fun, is_static, critical_fun, orig_critical_fun);
}

void JniHook::HookJniFun(JNIEnv *env, const char *class_name, const char *method_name,
                       const char *sign, void *new_fun, void **orig_fun, bool is_static,
                       void *critical_fun, void **orig_critical_fun) {
    if (env->ExceptionCheck()) return;
    if (!HookEnv.initialized || orig_fun == nullptr || new_fun == nullptr) {
        hookFailure(env, "ART JNI hook layout or entry points are unavailable");
        return;
    }
    jclass clazz = env->FindClass(class_name);
    if (clazz == nullptr) {
        ALOGD("findClass fail: %s %s", class_name, method_name);
        env->ExceptionClear();
        return;
    }
    jmethodID method = is_static ? env->GetStaticMethodID(clazz, method_name, sign)
                                : env->GetMethodID(clazz, method_name, sign);
    if (method == nullptr) {
        env->ExceptionClear();
        ALOGD("get method id fail: %s %s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }
    void *artMethod = GetArtMethod(env, clazz, method, is_static);
    if (artMethod == nullptr) {
        env->DeleteLocalRef(clazz);
        hookFailure(env, "Cannot resolve the ART method for a JNI hook");
        return;
    }
    const uint32_t flags = accessFlags(artMethod, HookEnv.art_method_flags_offset);
    if ((flags & kAccNative) == 0) {
        ALOGD("Skipping hook for non-native method: %s.%s", class_name, method_name);
        env->DeleteLocalRef(clazz);
        return;
    }
    // CriticalNative omits JNIEnv/jclass from its ABI. Keep ART's flags and compiled callers
    // unchanged; a normal JNI replacement cannot safely consume their argument registers.
    const bool critical = (flags & kAccCriticalNative) != 0;
    if (critical) {
        if (!is_static || critical_fun == nullptr || orig_critical_fun == nullptr) {
            ALOGE("CriticalNative hook entry point unavailable: %s.%s", class_name, method_name);
            env->DeleteLocalRef(clazz);
            hookFailure(env, "CriticalNative method requires an ABI-compatible hook entry point");
            return;
        }
        new_fun = critical_fun;
        orig_fun = orig_critical_fun;
    }
    // Re-registering a hook must not replace its original pointer with our trampoline.
    if (*orig_fun != nullptr) {
        env->DeleteLocalRef(clazz);
        return;
    }
    void *original;
    std::memcpy(&original, static_cast<char *>(artMethod)
            + HookEnv.art_method_native_offset * sizeof(uintptr_t), sizeof(original));
    if (original == nullptr || original == new_fun) {
        env->DeleteLocalRef(clazz);
        hookFailure(env, "JNI hook original entry point is unavailable");
        return;
    }
    JNINativeMethod methods[] = {{method_name, sign, new_fun}};
    // Another thread can enter as soon as RegisterNatives publishes the replacement.
    *orig_fun = original;
    if (env->RegisterNatives(clazz, methods, 1) < 0) {
        *orig_fun = nullptr;
        env->DeleteLocalRef(clazz);
        hookFailure(env, "Could not register a JNI hook");
        return;
    }
    env->DeleteLocalRef(clazz);
    ALOGD("register class: %s, method: %s abi=%s success", class_name, method_name,
            critical ? "critical-native" : "jni");
}

__attribute__((section(".mytext"))) JNICALL void native_offset(JNIEnv *, jclass) {}
__attribute__((section(".mytext"))) JNICALL void native_offset2(JNIEnv *, jclass) {}

__attribute__((section(".mytext"))) JNICALL void set_method_accessible(
        JNIEnv *env, jclass, jclass, jobject method) {
    if (!HookEnv.initialized) {
        hookFailure(env, "ART JNI hook layout has not been initialized");
        return;
    }
    void *artMethod = GetArtMethod(env, method);
    if (artMethod == nullptr) {
        hookFailure(env, "Cannot resolve ART method access flags");
        return;
    }
    setAccessFlags(artMethod, HookEnv.art_method_flags_offset,
            accessFlags(artMethod, HookEnv.art_method_flags_offset) | kAccPublic | kAccPublicApi);
}

__attribute__((section(".mytext"))) JNICALL void set_field_accessible(
        JNIEnv *env, jclass, jclass, jobject field) {
    if (!HookEnv.initialized) {
        hookFailure(env, "ART JNI hook layout has not been initialized");
        return;
    }
    void *artField = GetFieldMethod(env, field);
    if (artField == nullptr) {
        hookFailure(env, "Cannot resolve ART field access flags");
        return;
    }
    setAccessFlags(artField, HookEnv.art_field_flags_offset,
            (accessFlags(artField, HookEnv.art_field_flags_offset) | kAccPublic | kAccPublicApi)
            & ~kAccFinal);
}

static bool registerNative(JNIEnv *env) {
    jclass clazz = env->FindClass("com/nexbox/core/jnihook/jni/JniHook");
    if (clazz == nullptr) return false;
    JNINativeMethod methods[] = {
            {"nativeOffset", "()V", reinterpret_cast<void *>(native_offset)},
            {"nativeOffset2", "()V", reinterpret_cast<void *>(native_offset2)},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Method;)V",
                    reinterpret_cast<void *>(set_method_accessible)},
            {"setAccessible", "(Ljava/lang/Class;Ljava/lang/reflect/Field;)V",
                    reinterpret_cast<void *>(set_field_accessible)},
    };
    bool success = env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) == JNI_OK;
    env->DeleteLocalRef(clazz);
    return success;
}

static size_t recordSize(void *first, void *second, size_t alignment) {
    uintptr_t left = reinterpret_cast<uintptr_t>(first);
    uintptr_t right = reinterpret_cast<uintptr_t>(second);
    uintptr_t distance = left < right ? right - left : left - right;
    // These adjacent probe members must describe one small, aligned ART record.
    // Reject surprising layouts instead of walking arbitrary process memory.
    constexpr size_t maximumRecordSize = 256;
    return distance != 0 && distance <= maximumRecordSize && distance % alignment == 0
            ? distance : 0;
}

void JniHook::InitJniHook(JNIEnv *env) {
    if (HookEnv.initialized || env->ExceptionCheck()) return;
    if (!registerNative(env)) {
        hookFailure(env, "Could not register ART layout probes");
        return;
    }
    if (env->PushLocalFrame(16) < 0) return;
    jclass clazz = env->FindClass("com/nexbox/core/jnihook/jni/JniHook");
    if (clazz == nullptr) { env->PopLocalFrame(nullptr); return; }
    jmethodID firstMethodId = env->GetStaticMethodID(clazz, "nativeOffset", "()V");
    if (firstMethodId == nullptr) { env->PopLocalFrame(nullptr); return; }
    jmethodID secondMethodId = env->GetStaticMethodID(clazz, "nativeOffset2", "()V");
    if (secondMethodId == nullptr) { env->PopLocalFrame(nullptr); return; }
    jfieldID firstFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET", "I");
    if (firstFieldId == nullptr) { env->PopLocalFrame(nullptr); return; }
    jfieldID secondFieldId = env->GetStaticFieldID(clazz, "NATIVE_OFFSET_2", "I");
    if (secondFieldId == nullptr) { env->PopLocalFrame(nullptr); return; }
    void *firstField = GetFieldMethod(env, env->ToReflectedField(clazz, firstFieldId, true));
    void *secondField = env->ExceptionCheck() ? nullptr
            : GetFieldMethod(env, env->ToReflectedField(clazz, secondFieldId, true));
    void *firstMethod = GetArtMethod(env, clazz, firstMethodId, true);
    void *secondMethod = GetArtMethod(env, clazz, secondMethodId, true);
    if (firstField == nullptr || secondField == nullptr || firstMethod == nullptr || secondMethod == nullptr) {
        env->PopLocalFrame(nullptr);
        hookFailure(env, "Cannot resolve ART layout probe records");
        return;
    }
    size_t methodSize = recordSize(firstMethod, secondMethod, sizeof(uintptr_t));
    size_t fieldSize = recordSize(firstField, secondField, sizeof(uint32_t));
    if (methodSize == 0 || fieldSize == 0) {
        env->PopLocalFrame(nullptr);
        hookFailure(env, "Unsupported ART layout probe record sizes");
        return;
    }
    constexpr size_t missing = static_cast<size_t>(-1);
    size_t nativeOffset = missing;
    size_t methodFlagsOffset = missing;
    size_t fieldFlagsOffset = missing;
    for (size_t index = 0; index < methodSize / sizeof(uintptr_t); ++index) {
        void *entry;
        std::memcpy(&entry, static_cast<char *>(firstMethod) + index * sizeof(uintptr_t), sizeof(entry));
        if (entry == reinterpret_cast<void *>(native_offset)) {
            nativeOffset = index;
            break;
        }
    }
    // Match both probes' Java modifier bits; ART's extra runtime bits vary by release.
    constexpr uint32_t javaModifierMask = 0xffff;
    constexpr uint32_t methodFlags = kAccPublic | kAccStatic | kAccNative;
    for (size_t offset = 0; offset + sizeof(uint32_t) <= methodSize; offset += sizeof(uint32_t)) {
        if ((accessFlags(firstMethod, offset) & javaModifierMask) == (methodFlags | kAccFinal)
                && (accessFlags(secondMethod, offset) & javaModifierMask) == methodFlags) {
            methodFlagsOffset = offset;
            break;
        }
    }
    constexpr uint32_t fieldFlags = kAccPublic | kAccStatic | kAccFinal;
    for (size_t offset = 0; offset + sizeof(uint32_t) <= fieldSize; offset += sizeof(uint32_t)) {
        if ((accessFlags(firstField, offset) & javaModifierMask) == fieldFlags
                && (accessFlags(secondField, offset) & javaModifierMask) == fieldFlags) {
            fieldFlagsOffset = offset;
            break;
        }
    }
    if (nativeOffset == missing || methodFlagsOffset == missing || fieldFlagsOffset == missing) {
        env->PopLocalFrame(nullptr);
        hookFailure(env, "Could not locate JNI entry point or access flags in ART probe records");
        return;
    }
    jclass methodUtils = env->FindClass("com/nexbox/core/jnihook/MethodUtils");
    if (methodUtils == nullptr) { env->PopLocalFrame(nullptr); return; }
    jmethodID description = env->GetStaticMethodID(methodUtils, "getDesc", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    if (description == nullptr) { env->PopLocalFrame(nullptr); return; }
    jmethodID declaringClass = env->GetStaticMethodID(methodUtils, "getDeclaringClass", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    if (declaringClass == nullptr) { env->PopLocalFrame(nullptr); return; }
    jmethodID name = env->GetStaticMethodID(methodUtils, "getMethodName", "(Ljava/lang/reflect/Method;)Ljava/lang/String;");
    if (name == nullptr) { env->PopLocalFrame(nullptr); return; }
    auto globalMethodUtils = static_cast<jclass>(env->NewGlobalRef(methodUtils));
    env->PopLocalFrame(nullptr);
    if (globalMethodUtils == nullptr) return;
    // Publish only a complete layout; no partially initialized offsets reach a native hook.
    HookEnv.art_field_size = fieldSize;
    HookEnv.art_field_flags_offset = fieldFlagsOffset;
    HookEnv.art_method_size = methodSize;
    HookEnv.art_method_flags_offset = methodFlagsOffset;
    HookEnv.art_method_native_offset = nativeOffset;
    HookEnv.method_utils_class = globalMethodUtils;
    HookEnv.get_method_desc_id = description;
    HookEnv.get_method_declaring_class_id = declaringClass;
    HookEnv.get_method_name_id = name;
    HookEnv.initialized = true;
    ALOGD("JniHook initialized: methodSize=%zu fieldSize=%zu nativeSlot=%zu methodFlags=%zu fieldFlags=%zu",
            methodSize, fieldSize, nativeOffset, methodFlagsOffset, fieldFlagsOffset);
}
