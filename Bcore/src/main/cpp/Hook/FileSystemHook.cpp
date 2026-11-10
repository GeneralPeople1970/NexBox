#include "FileSystemHook.h"
#include "IO.h"
#include "Log.h"
#include "xdl.h"
#include "Dobby/dobby.h"
#include <array>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <mutex>
#include <limits.h>
#include <new>
#include <sys/stat.h>
#include <type_traits>
#include <unistd.h>
#include <utility>

namespace {
thread_local bool inFileHook = false;
std::once_flag installed;

class FileCall {
public:
    FileCall() : previous(inFileHook) { inFileHook = true; }
    ~FileCall() { inFileHook = previous; }
private:
    bool previous;
};

// Recover the logical parent before applying the longest mapping: a shared
// Download subtree can differ from the backing directory of an opened /sdcard.
const char *mappedPath(int dirfd, const char *path, std::string &storage) {
    if (path == nullptr) return path;
    if (path[0] == '/') return IO::redirectPath(path, storage);
    if (path[0] == '\0') return path; // Preserve AT_EMPTY_PATH semantics.
    char parent[PATH_MAX + 1];
    if (dirfd == AT_FDCWD) {
        if (getcwd(parent, sizeof(parent)) == nullptr) return path;
    } else {
        char descriptor[64];
        snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%d", dirfd);
        ssize_t size = readlink(descriptor, parent, PATH_MAX);
        if (size < 0 || size == PATH_MAX) return path;
        parent[size] = '\0';
    }
    std::string logical;
    const char *logicalParent = IO::logicalPath(parent, logical);
    if (logicalParent == parent || logical == parent) return path;
    logical.append("/").append(path);
    const char *mapped = IO::redirectPath(logical.c_str(), storage);
    if (mapped == logical.c_str()) storage = std::move(logical);
    return storage.c_str();
}

template<typename Result, typename Call>
Result routeAt(int dirfd, const char *path, Result failure, Call call) {
    if (inFileHook || path == nullptr) return call(path);
    FileCall scope;
    const int savedErrno = errno;
    try {
        std::string storage;
        const char *redirected = mappedPath(dirfd, path, storage);
        errno = savedErrno;
        return call(redirected);
    } catch (const std::bad_alloc &) {
        errno = ENOMEM;
    } catch (...) {
        errno = EIO;
    }
    return failure;
}

template<typename Result, typename Call>
Result route(const char *path, Result failure, Call call) {
    return routeAt(AT_FDCWD, path, failure, call);
}

template<typename Call>
int routePair(int oldfd, const char *oldpath, int newfd, const char *newpath, Call call) {
    if (inFileHook || oldpath == nullptr || newpath == nullptr) return call(oldpath, newpath);
    FileCall scope;
    const int savedErrno = errno;
    try {
        std::string fromStorage;
        std::string toStorage;
        const char *from = mappedPath(oldfd, oldpath, fromStorage);
        const char *to = mappedPath(newfd, newpath, toStorage);
        errno = savedErrno;
        return call(from, to);
    } catch (const std::bad_alloc &) { errno = ENOMEM; }
    catch (...) { errno = EIO; }
    return -1;
}

bool needsMode(int flags) {
    return (flags & O_CREAT) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
}

mode_t readMode(va_list args) {
    // On 32-bit Android mode_t is an unsigned short, so the vararg has
    // undergone default argument promotion to int before it reaches us.
    using ModeArg = std::conditional_t<(sizeof(mode_t) < sizeof(int)), int, mode_t>;
    return static_cast<mode_t>(va_arg(args, ModeArg));
}

int (*original_open)(const char *, int, ...) = nullptr;
int mapped_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (needsMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = readMode(args);
        va_end(args);
    }
    return route(path, -1, [&](const char *mapped) { return original_open(mapped, flags, mode); });
}

int (*original_open64)(const char *, int, ...) = nullptr;
int mapped_open64(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (needsMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = readMode(args);
        va_end(args);
    }
    return route(path, -1, [&](const char *mapped) { return original_open64(mapped, flags, mode); });
}

int (*original_openat)(int, const char *, int, ...) = nullptr;
int mapped_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (needsMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = readMode(args);
        va_end(args);
    }
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original_openat(dirfd, mapped, flags, mode); });
}

int (*original_openat64)(int, const char *, int, ...) = nullptr;
int mapped_openat64(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (needsMode(flags)) {
        va_list args;
        va_start(args, flags);
        mode = readMode(args);
        va_end(args);
    }
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original_openat64(dirfd, mapped, flags, mode); });
}

int (*original___open_2)(const char *, int) = nullptr;
int mapped___open_2(const char *path, int flags) {
    return route(path, -1, [&](const char *mapped) { return original___open_2(mapped, flags); });
}

int (*original___openat_2)(int, const char *, int) = nullptr;
int mapped___openat_2(int dirfd, const char *path, int flags) {
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original___openat_2(dirfd, mapped, flags); });
}

int (*original_stat)(const char *, struct stat *) = nullptr;
int mapped_stat(const char *path, struct stat *status) {
    return route(path, -1, [&](const char *mapped) { return original_stat(mapped, status); });
}

int (*original_lstat)(const char *, struct stat *) = nullptr;
int mapped_lstat(const char *path, struct stat *status) {
    return route(path, -1, [&](const char *mapped) { return original_lstat(mapped, status); });
}

int (*original_fstatat)(int, const char *, struct stat *, int) = nullptr;
int mapped_fstatat(int dirfd, const char *path, struct stat *status, int flags) {
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original_fstatat(dirfd, mapped, status, flags); });
}

int (*original_stat64)(const char *, struct stat64 *) = nullptr;
int mapped_stat64(const char *path, struct stat64 *status) {
    return route(path, -1, [&](const char *mapped) { return original_stat64(mapped, status); });
}

int (*original_lstat64)(const char *, struct stat64 *) = nullptr;
int mapped_lstat64(const char *path, struct stat64 *status) {
    return route(path, -1, [&](const char *mapped) { return original_lstat64(mapped, status); });
}

int (*original_fstatat64)(int, const char *, struct stat64 *, int) = nullptr;
int mapped_fstatat64(int dirfd, const char *path, struct stat64 *status, int flags) {
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original_fstatat64(dirfd, mapped, status, flags); });
}

int (*original_access)(const char *, int) = nullptr;
int mapped_access(const char *path, int mode) {
    return route(path, -1, [&](const char *mapped) { return original_access(mapped, mode); });
}

int (*original_faccessat)(int, const char *, int, int) = nullptr;
int mapped_faccessat(int dirfd, const char *path, int mode, int flags) {
    return routeAt(dirfd, path, -1, [&](const char *mapped) { return original_faccessat(dirfd, mapped, mode, flags); });
}

DIR * (*original_opendir)(const char *) = nullptr;
DIR * mapped_opendir(const char *path) {
    return route(path, static_cast<DIR *>(nullptr), [&](const char *mapped) { return original_opendir(mapped); });
}

ssize_t (*original_readlink)(const char *, char *, size_t) = nullptr;
ssize_t mapped_readlink(const char *path, char *buffer, size_t size) {
    return route(path, static_cast<ssize_t>(-1), [&](const char *mapped) { return original_readlink(mapped, buffer, size); });
}

ssize_t (*original_readlinkat)(int, const char *, char *, size_t) = nullptr;
ssize_t mapped_readlinkat(int dirfd, const char *path, char *buffer, size_t size) {
    return routeAt(dirfd, path, static_cast<ssize_t>(-1), [&](const char *mapped) { return original_readlinkat(dirfd, mapped, buffer, size); });
}

FILE * (*original_fopen)(const char *, const char *) = nullptr;
FILE * mapped_fopen(const char *path, const char *mode) {
    return route(path, static_cast<FILE *>(nullptr), [&](const char *mapped) { return original_fopen(mapped, mode); });
}

FILE * (*original_fopen64)(const char *, const char *) = nullptr;
FILE * mapped_fopen64(const char *path, const char *mode) {
    return route(path, static_cast<FILE *>(nullptr), [&](const char *mapped) { return original_fopen64(mapped, mode); });
}

struct InstalledHook { void *address; void *trampoline; int signature; };
int (*original_unlink)(const char *) = nullptr;
int mapped_unlink(const char *path) { return route(path, -1, [&](const char *p) { return original_unlink(p); }); }
int (*original_unlinkat)(int, const char *, int) = nullptr;
int mapped_unlinkat(int fd, const char *path, int flags) { return routeAt(fd, path, -1, [&](const char *p) { return original_unlinkat(fd, p, flags); }); }
int (*original_rename)(const char *, const char *) = nullptr;
int mapped_rename(const char *from, const char *to) { return routePair(AT_FDCWD, from, AT_FDCWD, to, [&](const char *a, const char *b) { return original_rename(a, b); }); }
int (*original_renameat)(int, const char *, int, const char *) = nullptr;
int mapped_renameat(int a, const char *from, int b, const char *to) { return routePair(a, from, b, to, [&](const char *x, const char *y) { return original_renameat(a, x, b, y); }); }
int (*original_renameat2)(int, const char *, int, const char *, unsigned int) = nullptr;
int mapped_renameat2(int a, const char *from, int b, const char *to, unsigned int flags) { return routePair(a, from, b, to, [&](const char *x, const char *y) { return original_renameat2(a, x, b, y, flags); }); }
int (*original_mkdir)(const char *, mode_t) = nullptr;
int mapped_mkdir(const char *path, mode_t mode) { return route(path, -1, [&](const char *p) { return original_mkdir(p, mode); }); }
int (*original_mkdirat)(int, const char *, mode_t) = nullptr;
int mapped_mkdirat(int fd, const char *path, mode_t mode) { return routeAt(fd, path, -1, [&](const char *p) { return original_mkdirat(fd, p, mode); }); }
int (*original_rmdir)(const char *) = nullptr;
int mapped_rmdir(const char *path) { return route(path, -1, [&](const char *p) { return original_rmdir(p); }); }
std::array<InstalledHook, 32> hooks{};
size_t hookCount = 0;
int missingCount = 0, failedCount = 0;
int forwardedCount = 0;
bool ready = false;

#if defined(__aarch64__)
// The bundled Dobby uses a 16-byte literal branch when libc and the detour are
// more than 4 GiB apart. A short symbol must not consume its neighbour's entry.
constexpr size_t arm64PatchBytes = 16;

bool readLibrary(const xdl_info_t &info, uintptr_t address, void *result, size_t size,
                 ElfW(Word) permissions) {
    const uintptr_t base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    if (address < base) return false;
    const uintptr_t offset = address - base;
    for (size_t index = 0; index < info.dlpi_phnum; ++index) {
        const auto &segment = info.dlpi_phdr[index];
        if (segment.p_type != PT_LOAD || (segment.p_flags & permissions) != permissions
                || offset < segment.p_vaddr) continue;
        const uintptr_t within = offset - segment.p_vaddr;
        if (within > segment.p_memsz || size > segment.p_memsz - within) continue;
        memcpy(result, reinterpret_cast<const void *>(address), size);
        return true;
    }
    return false;
}

bool branchesTo(const xdl_info_t &info, uintptr_t destination, uintptr_t expected) {
    if (destination == expected) return true;
    // Accept only a resolved AArch64 PLT entry in this libc, not an assumed
    // callee based on a package, ROM name, or the wrapper's symbol name alone.
    uint32_t plt[4];
    if (!readLibrary(info, destination, plt, sizeof(plt), PF_R | PF_X)) return false;
    if (plt[0] == 0xd503245f || plt[0] == 0xd503249f) { // Optional BTI c / j.
        destination += sizeof(uint32_t);
        if (!readLibrary(info, destination, plt, sizeof(plt), PF_R | PF_X)) return false;
    }
    if ((plt[0] & 0x9f00001f) != 0x90000010 // adrp x16, page
            || (plt[1] & 0xffc003ff) != 0xf9400211 // ldr x17, [x16, offset]
            || (plt[2] & 0xffc003ff) != 0x91000210 // add x16, x16, offset
            || plt[3] != 0xd61f0220) return false; // br x17
    const uintptr_t slotOffset = ((plt[1] >> 10) & 0xfff) * sizeof(uintptr_t);
    if (slotOffset != ((plt[2] >> 10) & 0xfff)) return false;
    int64_t pageDelta = ((plt[0] >> 29) & 3) | (((plt[0] >> 5) & 0x7ffff) << 2);
    if ((pageDelta & (1 << 20)) != 0) pageDelta -= (1 << 21);
    const uintptr_t slot = (destination & ~uintptr_t{0xfff}) + pageDelta * 4096 + slotOffset;
    uintptr_t resolved = 0;
    return readLibrary(info, slot, &resolved, sizeof(resolved), PF_R) && resolved == expected;
}

bool coveredRenameAt(void *library, void *address, size_t symbolSize) {
    if (symbolSize != 8 && symbolSize != 12) return false;
    void *callee = xdl_sym(library, "renameat2", nullptr);
    bool calleeInstalled = false;
    for (size_t index = 0; index < hookCount; ++index) {
        if (hooks[index].address == callee && hooks[index].signature == 19) {
            calleeInstalled = true;
            break;
        }
    }
    if (!calleeInstalled) return false;
    xdl_info_t info{};
    if (xdl_info(library, XDL_DI_DLINFO, &info) != 0) return false;
    uint32_t code[3]{};
    const uintptr_t entry = reinterpret_cast<uintptr_t>(address);
    if (!readLibrary(info, entry, code, symbolSize, PF_R | PF_X)) return false;
    const size_t first = symbolSize == 12 ? 1 : 0;
    if (first != 0 && code[0] != 0xd503245f) return false;
    // renameat(dirfd, from, dirfd, to) -> renameat2(..., flags=0).
    if (code[first] != 0x2a1f03e4 || (code[first + 1] & 0xfc000000) != 0x14000000) return false;
    int64_t branchDelta = code[first + 1] & 0x03ffffff;
    if ((branchDelta & (1 << 25)) != 0) branchDelta -= (1 << 26);
    const uintptr_t destination = entry + (first + 1) * sizeof(uint32_t) + branchDelta * 4;
    return branchesTo(info, destination, reinterpret_cast<uintptr_t>(callee));
}
#endif

void install(void *library, const char *name, void *replacement, void **original, int signature) {
    size_t symbolSize = 0;
    void *address = xdl_sym(library, name, &symbolSize);
    if (address == nullptr) {
        ++missingCount;
        nexbox_log_print(ANDROID_LOG_DEBUG, "FileSystemHook", "stage=unavailable symbol=%s reason=missing", name);
        return;
    }
    for (size_t index = 0; index < hookCount; index++) {
        if (hooks[index].address == address) {
            // Bionic exports 32/64 aliases at the same address. Never hook our own detour.
            if (hooks[index].signature != signature) {
                ++failedCount;
                nexbox_log_print(ANDROID_LOG_ERROR, "FileSystemHook", "stage=failed symbol=%s reason=alias_signature", name);
                return;
            }
            *original = hooks[index].trampoline;
            return;
        }
    }
#if defined(__aarch64__)
    if (symbolSize < arm64PatchBytes) {
        if (strcmp(name, "renameat") == 0 && coveredRenameAt(library, address, symbolSize)) {
            *original = address;
            ++forwardedCount;
            nexbox_log_print(ANDROID_LOG_INFO, "FileSystemHook",
                            "stage=covered symbol=%s symbolBytes=%zu via=renameat2 mode=verified_tail_call", name, symbolSize);
        } else {
            ++failedCount;
            nexbox_log_print(ANDROID_LOG_ERROR, "FileSystemHook",
                            "stage=failed symbol=%s reason=unsafe_symbol_size symbolBytes=%zu requiredBytes=%zu",
                            name, symbolSize, arm64PatchBytes);
        }
        return;
    }
#endif
    if (hookCount == hooks.size()) {
        ++failedCount;
        nexbox_log_print(ANDROID_LOG_ERROR, "FileSystemHook", "stage=failed symbol=%s reason=capacity", name);
        return;
    }
    *original = address;
    const int result = DobbyHook(address, replacement, original);
    if (result != 0 || *original == nullptr) {
        ++failedCount;
        nexbox_log_print(ANDROID_LOG_ERROR, "FileSystemHook", "stage=failed symbol=%s reason=hook_install result=%d", name, result);
        return;
    }
    hooks[hookCount++] = {address, *original, signature};
}
}

bool FileSystemHook::init() {
    std::call_once(installed, [] {
        FileCall scope;
        void *library = xdl_open("libc.so", XDL_DEFAULT);
        if (library == nullptr) {
            nexbox_log_print(ANDROID_LOG_ERROR, "FileSystemHook", "stage=failed reason=libc_unavailable");
            return;
        }
        install(library, "open", reinterpret_cast<void *>(mapped_open),
                reinterpret_cast<void **>(&original_open), 1);
        install(library, "open64", reinterpret_cast<void *>(mapped_open64),
                reinterpret_cast<void **>(&original_open64), 1);
        install(library, "openat", reinterpret_cast<void *>(mapped_openat),
                reinterpret_cast<void **>(&original_openat), 2);
        install(library, "openat64", reinterpret_cast<void *>(mapped_openat64),
                reinterpret_cast<void **>(&original_openat64), 2);
        install(library, "__open_2", reinterpret_cast<void *>(mapped___open_2),
                reinterpret_cast<void **>(&original___open_2), 3);
        install(library, "__openat_2", reinterpret_cast<void *>(mapped___openat_2),
                reinterpret_cast<void **>(&original___openat_2), 4);
        install(library, "stat", reinterpret_cast<void *>(mapped_stat),
                reinterpret_cast<void **>(&original_stat), 5);
        install(library, "lstat", reinterpret_cast<void *>(mapped_lstat),
                reinterpret_cast<void **>(&original_lstat), 5);
        install(library, "fstatat", reinterpret_cast<void *>(mapped_fstatat),
                reinterpret_cast<void **>(&original_fstatat), 6);
        install(library, "stat64", reinterpret_cast<void *>(mapped_stat64),
                reinterpret_cast<void **>(&original_stat64), sizeof(struct stat) == sizeof(struct stat64) ? 5 : 13);
        install(library, "lstat64", reinterpret_cast<void *>(mapped_lstat64),
                reinterpret_cast<void **>(&original_lstat64), sizeof(struct stat) == sizeof(struct stat64) ? 5 : 13);
        install(library, "fstatat64", reinterpret_cast<void *>(mapped_fstatat64),
                reinterpret_cast<void **>(&original_fstatat64), sizeof(struct stat) == sizeof(struct stat64) ? 6 : 14);
        install(library, "access", reinterpret_cast<void *>(mapped_access),
                reinterpret_cast<void **>(&original_access), 7);
        install(library, "faccessat", reinterpret_cast<void *>(mapped_faccessat),
                reinterpret_cast<void **>(&original_faccessat), 8);
        install(library, "opendir", reinterpret_cast<void *>(mapped_opendir),
                reinterpret_cast<void **>(&original_opendir), 9);
        install(library, "readlink", reinterpret_cast<void *>(mapped_readlink),
                reinterpret_cast<void **>(&original_readlink), 10);
        install(library, "readlinkat", reinterpret_cast<void *>(mapped_readlinkat),
                reinterpret_cast<void **>(&original_readlinkat), 11);
        install(library, "fopen", reinterpret_cast<void *>(mapped_fopen),
                reinterpret_cast<void **>(&original_fopen), 12);
        install(library, "fopen64", reinterpret_cast<void *>(mapped_fopen64),
                reinterpret_cast<void **>(&original_fopen64), 12);
        install(library, "unlink", reinterpret_cast<void *>(mapped_unlink), reinterpret_cast<void **>(&original_unlink), 15);
        install(library, "unlinkat", reinterpret_cast<void *>(mapped_unlinkat), reinterpret_cast<void **>(&original_unlinkat), 16);
        install(library, "rename", reinterpret_cast<void *>(mapped_rename), reinterpret_cast<void **>(&original_rename), 17);
        // Hook the callee first so short renameat wrappers retain the same IO routing.
        install(library, "renameat2", reinterpret_cast<void *>(mapped_renameat2), reinterpret_cast<void **>(&original_renameat2), 19);
        install(library, "renameat", reinterpret_cast<void *>(mapped_renameat), reinterpret_cast<void **>(&original_renameat), 18);
        install(library, "mkdir", reinterpret_cast<void *>(mapped_mkdir), reinterpret_cast<void **>(&original_mkdir), 20);
        install(library, "mkdirat", reinterpret_cast<void *>(mapped_mkdirat), reinterpret_cast<void **>(&original_mkdirat), 21);
        install(library, "rmdir", reinterpret_cast<void *>(mapped_rmdir), reinterpret_cast<void **>(&original_rmdir), 15);
        xdl_close(library);
        ready = failedCount == 0;
        nexbox_log_print(ready ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "FileSystemHook",
                        "stage=%s installed=%zu forwarded=%d missing=%d failed=%d",
                        ready ? "ready" : "failed", hookCount, forwardedCount, missingCount, failedCount);
    });
    return ready;
}
