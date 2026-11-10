



#include "BoxCore.h"
#include "Log.h"
#include "IO.h"
#include <jni.h>
#include <JniHook/JniHook.h>
#include <Hook/VMClassLoaderHook.h>
#include <Hook/UnixFileSystemHook.h>
#include <Hook/FileSystemHook.h>
#include <Hook/BinderHook.h>
#include <Hook/DexFileHook.h>
#include "Utils/VirtualSpoof.h"
#include "hidden_api.h"
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <exception>
#include <new>
#include <chrono>
#include <condition_variable>
#include <fcntl.h>
#include <mutex>
#include <pthread.h>
#include <signal.h>
#include <string>
#include <sys/syscall.h>
#include <sys/stat.h>
#include <unistd.h>

static std::atomic<bool> logging_enabled{false};
static JavaVM *logging_vm = nullptr;
static jclass logging_class = nullptr;
static jmethodID logging_method = nullptr;
static thread_local bool inside_logging = false;

int nexbox_log_print(int priority, const char *tag, const char *format, ...) {
    if (!logging_enabled.load(std::memory_order_acquire) || inside_logging || logging_vm == nullptr) return 0;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (logging_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (logging_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }
    if (env == nullptr || env->ExceptionCheck()) {
        if (attached) logging_vm->DetachCurrentThread();
        return 0;
    }
    inside_logging = true;
    char text[8192];
    va_list args;
    va_start(args, format);
    vsnprintf(text, sizeof(text), format, args);
    va_end(args);
    const auto length = static_cast<jsize>(strlen(text));
    jbyteArray message = env->NewByteArray(length);
    jstring category = nullptr;
    if (message != nullptr) {
        env->SetByteArrayRegion(message, 0, length, reinterpret_cast<const jbyte *>(text));
        category = env->NewStringUTF(tag);
        if (category != nullptr && !env->ExceptionCheck())
            env->CallStaticVoidMethod(logging_class, logging_method, priority, category, message);
    }
    // There was no exception on entry. Never propagate diagnostic allocation/callback failures.
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (category != nullptr) env->DeleteLocalRef(category);
    if (message != nullptr) env->DeleteLocalRef(message);
    inside_logging = false;
    if (attached) logging_vm->DetachCurrentThread();
    return 0;
}

static void setLoggingEnabled(JNIEnv *, jclass, jboolean enabled) {
    logging_enabled.store(enabled && logging_class != nullptr && logging_method != nullptr, std::memory_order_release);
}

static void diagnosticLogProbe(JNIEnv *env, jclass, jstring marker) {
    if (marker == nullptr) return;
    const char *text = env->GetStringUTFChars(marker, nullptr);
    if (text == nullptr) return;
    nexbox_log_print(ANDROID_LOG_INFO, "NativeProbe", "marker=%s native diagnostic probe", text);
    env->ReleaseStringUTFChars(marker, text);
}

static void publishMediaFile(JNIEnv *env, jclass, jstring temporaryPath, jstring targetPath) {
    if (temporaryPath == nullptr || targetPath == nullptr) {
        env->ThrowNew(env->FindClass("java/io/IOException"), "Missing media publication path");
        return;
    }
    const char *temporaryChars = env->GetStringUTFChars(temporaryPath, nullptr);
    if (temporaryChars == nullptr) return;
    const char *targetChars = env->GetStringUTFChars(targetPath, nullptr);
    if (targetChars == nullptr) {
        env->ReleaseStringUTFChars(temporaryPath, temporaryChars);
        return;
    }
    const std::string temporary(temporaryChars);
    const std::string target(targetChars);
    env->ReleaseStringUTFChars(temporaryPath, temporaryChars);
    env->ReleaseStringUTFChars(targetPath, targetChars);
    const auto temporarySlash = temporary.find_last_of('/');
    const auto targetSlash = target.find_last_of('/');
    if (temporarySlash == std::string::npos || targetSlash == std::string::npos
            || temporary.substr(0, temporarySlash) != target.substr(0, targetSlash)
            || temporary == target || temporarySlash + 1 == temporary.size()
            || targetSlash + 1 == target.size()) {
        env->ThrowNew(env->FindClass("java/io/IOException"), "Media publication must stay in one directory");
        return;
    }
    // Pin the directory for both names; do not follow a substituted final-directory symlink.
    const std::string parent = temporary.substr(0, temporarySlash);
    int directory = open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    int error = directory < 0 ? errno : 0;
    if (directory >= 0) {
#ifdef __NR_renameat2
        constexpr unsigned int noReplace = 1; // RENAME_NOREPLACE, supported by Android internal ext4/f2fs.
        if (syscall(__NR_renameat2, directory, temporary.c_str() + temporarySlash + 1,
                    directory, target.c_str() + targetSlash + 1, noReplace) != 0) {
            error = errno;
        }
#else
        error = ENOSYS;
#endif
        close(directory);
    }
    if (error != 0) {
        std::string message;
        if (error == ENOSYS || error == EINVAL || error == EOPNOTSUPP) {
            message = "This storage does not support safe atomic media publication";
        } else if (error == EEXIST) {
            message = "Refusing to overwrite an existing media file";
        } else {
            message = "Could not atomically publish media";
        }
        message += ": ";
        message += strerror(error);
        // No link/ordinary-rename fallback: neither preserves both sandbox and no-overwrite semantics.
        env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
    }
}

struct MediaLeaseSignals {
    std::mutex mutex;
    std::condition_variable changed;
    pid_t tid = 0;
    int error = 0;
    bool ready = false;
};
static MediaLeaseSignals mediaLeaseSignals;
static std::once_flag mediaLeaseSignalStart;

static void *receiveMediaLeaseSignals(void *) {
    sigset_t signals;
    sigemptyset(&signals);
    sigaddset(&signals, SIGIO);
    int error = pthread_sigmask(SIG_BLOCK, &signals, nullptr);
    {
        std::lock_guard<std::mutex> guard(mediaLeaseSignals.mutex);
        mediaLeaseSignals.error = error;
        mediaLeaseSignals.tid = error == 0 ? gettid() : 0;
        mediaLeaseSignals.ready = true;
    }
    mediaLeaseSignals.changed.notify_all();
    if (error != 0) return nullptr;
    // One process-lifetime receiver; never changes Java/ART signal handlers or another thread's mask.
    int received;
    while ((error = sigwait(&signals, &received)) == 0) { }
    std::lock_guard<std::mutex> guard(mediaLeaseSignals.mutex);
    mediaLeaseSignals.error = error;
    mediaLeaseSignals.tid = 0;
    return nullptr;
}

static int mediaLeaseSignalReceiver() {
    std::call_once(mediaLeaseSignalStart, [] {
        pthread_attr_t attributes;
        int error = pthread_attr_init(&attributes);
        if (error == 0) {
            error = pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
            pthread_t thread;
            if (error == 0) error = pthread_create(&thread, &attributes, receiveMediaLeaseSignals, nullptr);
            pthread_attr_destroy(&attributes);
        }
        if (error != 0) {
            std::lock_guard<std::mutex> guard(mediaLeaseSignals.mutex);
            mediaLeaseSignals.error = error;
            mediaLeaseSignals.ready = true;
        }
    });
    std::unique_lock<std::mutex> guard(mediaLeaseSignals.mutex);
    if (!mediaLeaseSignals.changed.wait_for(guard, std::chrono::seconds(2), [] { return mediaLeaseSignals.ready; })) return -ETIMEDOUT;
    return mediaLeaseSignals.error == 0 ? mediaLeaseSignals.tid : -mediaLeaseSignals.error;
}

// File leases detect descriptors/mappings that have escaped a Provider's close callback,
// including descriptors inherited or passed to another process. No advisory-lock fallback.
static jint acquireMediaFileLease(JNIEnv *env, jclass, jstring path) {
    if (path == nullptr) return -EINVAL;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return -ENOMEM;
    int fd = open(chars, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    int error = fd < 0 ? errno : 0;
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) return -error;
    struct stat status{};
    if (fstat(fd, &status) != 0) error = errno;
    else if (!S_ISREG(status.st_mode) || status.st_nlink != 1) error = EOPNOTSUPP;
#if defined(F_SETLEASE) && defined(F_GETLEASE) && defined(F_SETOWN_EX) && defined(F_GETOWN_EX)
    int receiver = -EOPNOTSUPP;
    try { if (error == 0) receiver = mediaLeaseSignalReceiver(); }
    catch (...) { if (error == 0) error = EOPNOTSUPP; }
    if (error == 0 && receiver <= 0) error = receiver < 0 ? -receiver : EOPNOTSUPP;
    struct f_owner_ex owner{F_OWNER_TID, receiver};
    // A nonzero owner must precede F_SETLEASE: lease_setup otherwise chooses the calling process,
    // and SIGIO's default disposition could terminate it before a subsequent F_SETOWN can run.
    if (error == 0 && fcntl(fd, F_SETOWN_EX, &owner) < 0) error = errno;
    if (error == 0 && fcntl(fd, F_SETLEASE, F_WRLCK) < 0) error = errno;
    struct f_owner_ex actual{};
    if (error == 0 && fcntl(fd, F_GETOWN_EX, &actual) < 0) error = errno;
    if (error == 0 && (actual.type != F_OWNER_TID || actual.pid != receiver)) error = EOPNOTSUPP;
    if (error == 0 && fcntl(fd, F_GETLEASE) != F_WRLCK) error = EAGAIN;
#else
    if (error == 0) error = EOPNOTSUPP;
#endif
    if (error != 0) { close(fd); return -error; }
    return fd;
}

static jboolean isMediaFileLeaseHeld(JNIEnv *, jclass, jint fd) {
#if defined(F_GETLEASE)
    return fd >= 0 && fcntl(fd, F_GETLEASE) == F_WRLCK;
#else
    return JNI_FALSE;
#endif
}

struct {
    JavaVM *vm;
    jclass NativeCoreClass;
    jmethodID getCallingUidId;
    jmethodID redirectPathString;
    jmethodID redirectPathFile;
    jmethodID loadEmptyDex;
    jmethodID loadEmptyDexL;
    int api_level;
} VMEnv;


int BoxCore::getCallingUid(JNIEnv *env, int orig) {
    if (env->ExceptionCheck()) return orig;
    return env->CallStaticIntMethod(VMEnv.NativeCoreClass, VMEnv.getCallingUidId, orig);
}

jstring BoxCore::redirectPathString(JNIEnv *env, jstring path) {
    if (env->ExceptionCheck()) return nullptr;
    return (jstring) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathString, path);
}

jobject BoxCore::redirectPathFile(JNIEnv *env, jobject path) {
    if (env->ExceptionCheck()) return nullptr;
    return env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.redirectPathFile, path);
}

jlongArray BoxCore::loadEmptyDex(JNIEnv *env) {
    if (env->ExceptionCheck()) return nullptr;
    return (jlongArray) env->CallStaticObjectMethod(VMEnv.NativeCoreClass, VMEnv.loadEmptyDex);
}

int BoxCore::getApiLevel() {
    return VMEnv.api_level;
}

JavaVM *BoxCore::getJavaVM() {
    return VMEnv.vm;
}

bool nativeHook(JNIEnv *env) {
    if (env->ExceptionCheck()) return false;
    BaseHook::init(env);
    if (env->ExceptionCheck()) return false;
    UnixFileSystemHook::init(env);
    if (env->ExceptionCheck()) return false;
    if (!FileSystemHook::init()) return false;
    VMClassLoaderHook::init(env);
    if (env->ExceptionCheck()) return false;

    BinderHook::init(env);
    if (env->ExceptionCheck()) return false;
    DexFileHook::init(env);
    return !env->ExceptionCheck();
}

void hideXposed(JNIEnv *env, jclass clazz) {
    ALOGD("set hideXposed");
    VMClassLoaderHook::hideXposed();
}

void init(JNIEnv *env, jclass clazz, jint api_level) {
    if (VMEnv.NativeCoreClass != nullptr || env->ExceptionCheck()) return;
    ALOGD("NativeCore init.");
    jmethodID callingUid = env->GetStaticMethodID(clazz, "getCallingUid", "(I)I");
    if (callingUid == nullptr) return;
    jmethodID stringPath = env->GetStaticMethodID(clazz, "redirectPath", "(Ljava/lang/String;)Ljava/lang/String;");
    if (stringPath == nullptr) return;
    jmethodID filePath = env->GetStaticMethodID(clazz, "redirectPath", "(Ljava/io/File;)Ljava/io/File;");
    if (filePath == nullptr) return;
    jmethodID emptyDex = env->GetStaticMethodID(clazz, "loadEmptyDex", "()[J");
    if (emptyDex == nullptr) return;
    auto coreClass = static_cast<jclass>(env->NewGlobalRef(clazz));
    if (coreClass == nullptr) return;
    JniHook::InitJniHook(env);
    if (env->ExceptionCheck()) {
        env->DeleteGlobalRef(coreClass);
        return;
    }
    VMEnv.api_level = api_level;
    VMEnv.getCallingUidId = callingUid;
    VMEnv.redirectPathString = stringPath;
    VMEnv.redirectPathFile = filePath;
    VMEnv.loadEmptyDex = emptyDex;
    VMEnv.NativeCoreClass = coreClass;
}

void addIORule(JNIEnv *env, jclass clazz, jstring target_path,
               jstring relocate_path) {
    ALOGD("set addIORule");
    if (target_path == nullptr || relocate_path == nullptr) return;
    const char *target = env->GetStringUTFChars(target_path, nullptr);
    if (target == nullptr) return;
    const char *relocate = env->GetStringUTFChars(relocate_path, nullptr);
    if (relocate != nullptr) {
        try {
            // The rule owns its copies; JNI buffers must not remain pinned for process lifetime.
            IO::addRule(target, relocate);
        } catch (const std::exception &) {
            jclass error = env->FindClass("java/lang/OutOfMemoryError");
            if (error != nullptr) {
                env->ThrowNew(error, "Could not allocate an IO redirect rule");
                env->DeleteLocalRef(error);
            }
        }
        env->ReleaseStringUTFChars(relocate_path, relocate);
    }
    env->ReleaseStringUTFChars(target_path, target);
}

void enableIO(JNIEnv *env, jclass clazz) {
    ALOGD("set enableIO");
    IO::init(env);
    if (!nativeHook(env) && !env->ExceptionCheck()) {
        jclass error = env->FindClass("java/lang/IllegalStateException");
        if (error != nullptr) {
            env->ThrowNew(error, "Native filesystem hooks unavailable; see FileSystemHook diagnostics");
            env->DeleteLocalRef(error);
        }
    }
}

bool disableHiddenApi(JNIEnv *env, jclass clazz) {
    ALOGD("set disableHiddenApi");
    if(!disable_hidden_api(env)){
        ALOGD("set disableHiddenApi Fail!!!");
        return false;
    }
    return true;
}

bool disableResourceLoading(JNIEnv *env, jclass clazz) {
    ALOGD("set disableResourceLoading");
    if(!disable_resource_loading()){
        ALOGD("set disableResourceLoading Fail!!!");
        return false;
    }
    return true;
}

void setDeviceSpoof(JNIEnv *env, jclass clazz,
                    jstring manufacturer,
                    jstring brand,
                    jstring model,
                    jstring device,
                    jstring product,
                    jstring fingerprint,
                    jstring serial) {
    if (env->ExceptionCheck()) return;
    const jstring inputs[] = {manufacturer, brand, model, device, product, fingerprint, serial};
    const char *characters[7]{};
    bool complete = true;
    for (size_t index = 0; index < 7; ++index) {
        if (inputs[index] == nullptr) continue;
        characters[index] = env->GetStringUTFChars(inputs[index], nullptr);
        if (characters[index] == nullptr) {
            complete = false;
            break;
        }
    }
    if (complete) {
        try {
            setDeviceSpoofValues(characters[0], characters[1], characters[2], characters[3],
                                 characters[4], characters[5], characters[6]);
        } catch (const std::bad_alloc &) {
            jclass failure = env->FindClass("java/lang/OutOfMemoryError");
            if (failure != nullptr) {
                env->ThrowNew(failure, "Could not copy device properties");
                env->DeleteLocalRef(failure);
            }
        }
    }
    // Only successful acquisitions may be released, including after an allocation failure.
    for (size_t index = 0; index < 7; ++index) {
        if (characters[index] != nullptr) env->ReleaseStringUTFChars(inputs[index], characters[index]);
    }
}

void setSimSpoof(JNIEnv *env, jclass clazz, jobjectArray names, jobjectArray values) {
    if (env->ExceptionCheck()) return;
    std::unordered_map<std::string, std::string> properties;
    if ((names == nullptr) != (values == nullptr)) {
        jclass failure = env->FindClass("java/lang/IllegalArgumentException");
        if (failure != nullptr) {
            env->ThrowNew(failure, "SIM property names and values must both be supplied");
            env->DeleteLocalRef(failure);
        }
        return;
    }
    if (names != nullptr && values != nullptr) {
        jsize count = env->GetArrayLength(names);
        if (count != env->GetArrayLength(values)) {
            jclass failure = env->FindClass("java/lang/IllegalArgumentException");
            if (failure != nullptr) {
                env->ThrowNew(failure, "SIM property names and values must have the same length");
                env->DeleteLocalRef(failure);
            }
            return;
        }
        for (jsize i = 0; i < count; ++i) {
            auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
            if (env->ExceptionCheck()) return;
            auto value = static_cast<jstring>(env->GetObjectArrayElement(values, i));
            if (env->ExceptionCheck()) {
                if (name != nullptr) env->DeleteLocalRef(name);
                return;
            }
            if (name != nullptr && value != nullptr) {
                const char *key = env->GetStringUTFChars(name, nullptr);
                const char *text = key == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
                try {
                    if (key != nullptr && text != nullptr) properties.emplace(key, text);
                } catch (const std::bad_alloc &) {
                    jclass failure = env->FindClass("java/lang/OutOfMemoryError");
                    if (failure != nullptr) {
                        env->ThrowNew(failure, "Could not copy SIM properties");
                        env->DeleteLocalRef(failure);
                    }
                }
                if (key != nullptr) env->ReleaseStringUTFChars(name, key);
                if (text != nullptr) env->ReleaseStringUTFChars(value, text);
            }
            if (name != nullptr) env->DeleteLocalRef(name);
            if (value != nullptr) env->DeleteLocalRef(value);
            if (env->ExceptionCheck()) return;
        }
    }
    try {
        setSimSpoofValues(properties);
    } catch (const std::bad_alloc &) {
        jclass failure = env->FindClass("java/lang/OutOfMemoryError");
        if (failure != nullptr) {
            env->ThrowNew(failure, "Could not publish SIM properties");
            env->DeleteLocalRef(failure);
        }
    }
}

static JNINativeMethod gMethods[] = {
        {"setLoggingEnabled", "(Z)V", (void *) setLoggingEnabled},
        {"diagnosticLogProbe", "(Ljava/lang/String;)V", (void *) diagnosticLogProbe},
        {"publishMediaFile", "(Ljava/lang/String;Ljava/lang/String;)V", (void *) publishMediaFile},
        {"acquireMediaFileLease", "(Ljava/lang/String;)I", (void *) acquireMediaFileLease},
        {"isMediaFileLeaseHeld", "(I)Z", (void *) isMediaFileLeaseHeld},
        {"disableHiddenApi", "()Z",                               (void *) disableHiddenApi},
        {"disableResourceLoading", "()Z",                         (void *) disableResourceLoading},
        {"hideXposed", "()V",                                     (void *) hideXposed},
        {"addIORule",  "(Ljava/lang/String;Ljava/lang/String;)V", (void *) addIORule},
        {"enableIO",   "()V",                                     (void *) enableIO},
        {"init",       "(I)V",                                    (void *) init},
        {"setDeviceSpoof", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", (void *) setDeviceSpoof},
        {"setSimSpoof", "([Ljava/lang/String;[Ljava/lang/String;)V", (void *) setSimSpoof},
};

int registerNativeMethods(JNIEnv *env, const char *className,
                          JNINativeMethod *gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == nullptr) {
        return JNI_FALSE;
    }
    const jint result = env->RegisterNatives(clazz, gMethods, numMethods);
    env->DeleteLocalRef(clazz);
    return result == JNI_OK ? JNI_TRUE : JNI_FALSE;
}

int registerNatives(JNIEnv *env) {
    if (!registerNativeMethods(env, VMCORE_CLASS, gMethods,
                               sizeof(gMethods) / sizeof(gMethods[0])))
        return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    VMEnv.vm = vm;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_EVERSION;
    }
    if (!registerNatives(env)) return JNI_ERR;
    jclass logger = env->FindClass(VMCORE_CLASS);
    if (logger != nullptr) {
        logging_class = static_cast<jclass>(env->NewGlobalRef(logger));
        if (logging_class != nullptr) {
            logging_method = env->GetStaticMethodID(logger, "emitNativeLog", "(ILjava/lang/String;[B)V");
        }
        env->DeleteLocalRef(logger);
    }
    if (env->ExceptionCheck() || logging_class == nullptr || logging_method == nullptr) {
        if (logging_class != nullptr) env->DeleteGlobalRef(logging_class);
        logging_class = nullptr;
        logging_method = nullptr;
        return JNI_ERR;
    }
    logging_vm = vm;
    return JNI_VERSION_1_6;
}
