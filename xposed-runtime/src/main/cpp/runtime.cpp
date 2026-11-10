#include <jni.h>
#include <dobby.h>
#include <lsplant.hpp>
#include <mutex>
#include <cstring>
#include <new>
#include <limits>
#include <string>
#include <vector>
#include "xdl.h"

namespace {
bool initialized = false;
void* art_handle = nullptr; // Resolvers retained by LSPlant must outlive JNI_OnLoad.
std::mutex symbol_mutex;

void report_initialization_failure(JNIEnv* env, jclass bridge, const char* detail, jthrowable failure = nullptr) {
    auto report = env->GetStaticMethodID(bridge, "reportInitializationFailure", "(Ljava/lang/String;Ljava/lang/Throwable;)V");
    if (report == nullptr) { env->ExceptionClear(); return; }
    auto message = env->NewStringUTF(detail);
    if (message != nullptr) {
        env->CallStaticVoidMethod(bridge, report, message, failure);
        env->DeleteLocalRef(message);
    }
    // Reporting is best-effort and must not replace the original initialization result.
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void* resolve(const std::string_view name, bool prefix) {
    std::lock_guard lock(symbol_mutex);
    const std::string symbol(name); // string_view need not be nul-terminated.
    if (prefix) return nexbox_xdl_sym_prefix(art_handle, symbol.c_str());
    if (auto* value = xdl_sym(art_handle, symbol.c_str(), nullptr)) return value;
    return xdl_dsym(art_handle, symbol.c_str(), nullptr);
}

jboolean ready(JNIEnv*, jclass) { return initialized; }
jobject hook(JNIEnv* env, jclass, jobject target, jobject dispatcher, jobject callback) {
    if (!initialized || !target || !dispatcher || !callback) return nullptr;
    return lsplant::Hook(env, target, dispatcher, callback);
}
jboolean deoptimize(JNIEnv* env, jclass, jobject target) {
    return initialized && target && lsplant::Deoptimize(env, target);
}

jobject allocate_instance(JNIEnv* env, jclass, jclass type) {
    return env->AllocObject(type);
}

jobject class_initializer(JNIEnv* env, jclass, jclass type) {
    return initialized ? lsplant::GetClassInitializer(env, type) : nullptr;
}

char primitive_type(JNIEnv* env, jobject type) {
    auto cls = env->FindClass("java/lang/Class");
    if (cls == nullptr) return '\0';
    auto is_primitive = env->GetMethodID(cls, "isPrimitive", "()Z");
    if (is_primitive == nullptr) { env->DeleteLocalRef(cls); return '\0'; }
    bool primitive = env->CallBooleanMethod(type, is_primitive);
    if (env->ExceptionCheck()) { env->DeleteLocalRef(cls); return '\0'; }
    if (!primitive) { env->DeleteLocalRef(cls); return 'L'; }
    auto get_name = env->GetMethodID(cls, "getName", "()Ljava/lang/String;");
    if (get_name == nullptr) { env->DeleteLocalRef(cls); return '\0'; }
    auto name = static_cast<jstring>(env->CallObjectMethod(type, get_name));
    if (name == nullptr || env->ExceptionCheck()) { env->DeleteLocalRef(cls); return '\0'; }
    const char* chars = env->GetStringUTFChars(name, nullptr);
    char kind = '\0';
    if (chars != nullptr) {
        kind = std::strcmp(chars, "boolean") == 0 ? 'Z'
                : std::strcmp(chars, "byte") == 0 ? 'B'
                : std::strcmp(chars, "char") == 0 ? 'C'
                : std::strcmp(chars, "short") == 0 ? 'S'
                : std::strcmp(chars, "int") == 0 ? 'I'
                : std::strcmp(chars, "long") == 0 ? 'J'
                : std::strcmp(chars, "float") == 0 ? 'F'
                : std::strcmp(chars, "double") == 0 ? 'D' : 'V';
        env->ReleaseStringUTFChars(name, chars);
    }
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(cls);
    return kind;
}

// Only called after Java validates receiver, arity and reflection-compatible primitive widening.
jobject invoke_special(JNIEnv* env, jclass, jobject method, jclass owner, jobject receiver,
                       jobjectArray args, jobjectArray types, jobject return_type) {
    const auto count = env->GetArrayLength(args);
    if (count > (std::numeric_limits<jint>::max() - 32) / 3) {
        auto failure = env->FindClass("java/lang/OutOfMemoryError");
        if (failure != nullptr) {
            env->ThrowNew(failure, "Native invocation has too many local references");
            env->DeleteLocalRef(failure);
        }
        return nullptr;
    }
    if (env->PushLocalFrame(count * 3 + 32) < 0) return nullptr;
    auto number = env->FindClass("java/lang/Number");
    if (number == nullptr) return env->PopLocalFrame(nullptr);
    auto character = env->FindClass("java/lang/Character");
    if (character == nullptr) return env->PopLocalFrame(nullptr);
    auto boolean = env->FindClass("java/lang/Boolean");
    if (boolean == nullptr) return env->PopLocalFrame(nullptr);
    auto char_value = env->GetMethodID(character, "charValue", "()C");
    if (char_value == nullptr) return env->PopLocalFrame(nullptr);
    std::vector<jvalue> values;
    try {
        values.resize(count);
    } catch (const std::bad_alloc&) {
        auto failure = env->FindClass("java/lang/OutOfMemoryError");
        if (failure != nullptr) env->ThrowNew(failure, "Could not allocate native invocation arguments");
        return env->PopLocalFrame(nullptr);
    }
    for (jsize i = 0; i < count; ++i) {
        jobject value = env->GetObjectArrayElement(args, i);
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
        jobject type = env->GetObjectArrayElement(types, i);
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
        const char kind = primitive_type(env, type);
        if (kind == '\0' || env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
        const bool is_char = value && env->IsInstanceOf(value, character);
        const jchar c = is_char ? env->CallCharMethod(value, char_value) : 0;
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
        switch (kind) {
            case 'L': values[i].l = value; break;
            case 'C': values[i].c = c; break;
#define UNBOX_ARGUMENT(code, member, Type, owner, method, signature, allow_char) \
            case code: { \
                if (allow_char && is_char) values[i].member = c; \
                else { \
                    auto accessor = env->GetMethodID(owner, method, signature); \
                    if (accessor == nullptr) return env->PopLocalFrame(nullptr); \
                    values[i].member = env->Call##Type##Method(value, accessor); \
                } \
                break; \
            }
            UNBOX_ARGUMENT('Z', z, Boolean, boolean, "booleanValue", "()Z", false)
            UNBOX_ARGUMENT('B', b, Byte, number, "byteValue", "()B", false)
            UNBOX_ARGUMENT('S', s, Short, number, "shortValue", "()S", false)
            UNBOX_ARGUMENT('I', i, Int, number, "intValue", "()I", true)
            UNBOX_ARGUMENT('J', j, Long, number, "longValue", "()J", true)
            UNBOX_ARGUMENT('F', f, Float, number, "floatValue", "()F", true)
            UNBOX_ARGUMENT('D', d, Double, number, "doubleValue", "()D", true)
#undef UNBOX_ARGUMENT
        }
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    jmethodID id = env->FromReflectedMethod(method);
    if (id == nullptr || env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    jobject result = nullptr;
    char result_kind = primitive_type(env, return_type);
    if (result_kind == '\0' || env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    switch (result_kind) {
        case 'V': env->CallNonvirtualVoidMethodA(receiver, owner, id, values.data()); break;
        case 'L': result = env->CallNonvirtualObjectMethodA(receiver, owner, id, values.data()); break;
#define BOX_RESULT(code, Type, wrapper, signature) \
        case code: { \
            auto value = env->CallNonvirtual##Type##MethodA(receiver, owner, id, values.data()); \
            if (!env->ExceptionCheck()) { \
                auto box = env->FindClass("java/lang/" wrapper); \
                if (box == nullptr) break; \
                auto factory = env->GetStaticMethodID(box, "valueOf", "(" signature ")Ljava/lang/" wrapper ";"); \
                if (factory == nullptr) break; \
                result = env->CallStaticObjectMethod(box, factory, value); \
            } \
            break; \
        }
        BOX_RESULT('Z', Boolean, "Boolean", "Z")
        BOX_RESULT('B', Byte, "Byte", "B")
        BOX_RESULT('C', Char, "Character", "C")
        BOX_RESULT('S', Short, "Short", "S")
        BOX_RESULT('I', Int, "Integer", "I")
        BOX_RESULT('J', Long, "Long", "J")
        BOX_RESULT('F', Float, "Float", "F")
        BOX_RESULT('D', Double, "Double", "D")
#undef BOX_RESULT
    }
    return env->PopLocalFrame(result);
}
const JNINativeMethod methods[] = {
    {"nativeReady", "()Z", reinterpret_cast<void*>(ready)},
    {"nativeHook", "(Ljava/lang/reflect/Member;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;", reinterpret_cast<void*>(hook)},
    {"nativeDeoptimize", "(Ljava/lang/reflect/Member;)Z", reinterpret_cast<void*>(deoptimize)},
    {"nativeAllocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", reinterpret_cast<void*>(allocate_instance)},
    {"nativeClassInitializer", "(Ljava/lang/Class;)Ljava/lang/reflect/Method;", reinterpret_cast<void*>(class_initializer)},
    {"nativeInvokeSpecial", "(Ljava/lang/reflect/Method;Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;[Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/Object;", reinterpret_cast<void*>(invoke_special)},
};
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto bridge = env->FindClass("com/nexbox/core/xposed/NativeBridge");
    if (!bridge || env->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK)
        return JNI_ERR;
    art_handle = xdl_open("libart.so", XDL_DEFAULT);
    if (!art_handle) {
        report_initialization_failure(env, bridge, "xdl_open(libart.so) failed; cannot resolve ART symbols");
        env->DeleteLocalRef(bridge);
        return JNI_VERSION_1_6; // Java reports failure, not a fake loaded state.
    }
    try {
        lsplant::InitInfo info{
            .inline_hooker = [](void* target, void* replacement) -> void* {
                void* original = nullptr;
                return DobbyHook(target, replacement, &original) == RT_SUCCESS ? original : nullptr;
            },
            .inline_unhooker = [](void* target) { return DobbyDestroy(target) == RT_SUCCESS; },
            .art_symbol_resolver = [](auto name) { return resolve(name, false); },
            .art_symbol_prefix_resolver = [](auto name) { return resolve(name, true); },
        };
        initialized = lsplant::Init(env, info);
        if (!initialized) {
            // Init returns false (no throw) when it cannot verify/patch ART — most often because the
            // process runs under ARM binary translation (x86 emulator) so Dobby cannot hook the real ART.
            if (!env->ExceptionCheck()) report_initialization_failure(env, bridge,
                    "lsplant::Init returned false; inline ART hooks unavailable (verify on a native ARM device)");
        }
    } catch (...) {
        initialized = false;
        if (!env->ExceptionCheck()) report_initialization_failure(env, bridge, "LSPlant initialization failed");
    }
    if (env->ExceptionCheck()) {
        auto failure = env->ExceptionOccurred();
        env->ExceptionClear();
        report_initialization_failure(env, bridge, "Java exception during LSPlant initialization", failure);
        if (failure != nullptr) env->DeleteLocalRef(failure);
        initialized = false;
    }
    env->DeleteLocalRef(bridge);
    return JNI_VERSION_1_6;
}
