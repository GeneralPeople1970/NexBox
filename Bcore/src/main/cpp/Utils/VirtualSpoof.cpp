#include <sys/system_properties.h>
#include <algorithm>
#include <cstring>
#include <mutex>
#include <string>
#include "./xdl.h"
#include <android/log.h>
#include "Dobby/dobby.h"
#include "VirtualSpoof.h"

#define LOG_TAG "VirtualSpoof"
#include "../Log.h"
#define LOGD(...) nexbox_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

static int (*orig_system_property_get)(const char *, char *) = nullptr;
using PropertyCallback = void (*)(void *, const char *, const char *, uint32_t);
static void (*orig_property_read_callback)(const prop_info *, PropertyCallback, void *) = nullptr;
static std::mutex g_spoof_mutex;
static std::unordered_map<std::string, std::string> g_device_values;
static std::unordered_map<std::string, std::string> g_sim_values;

static bool lookup_value(const char *name, std::string &value) {
    if (name == nullptr) return false;
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    auto device = g_device_values.find(name);
    if (device != g_device_values.end()) { value = device->second; return true; }
    auto sim = g_sim_values.find(name);
    if (sim != g_sim_values.end()) { value = sim->second; return true; }
    return false;
}

void setDeviceSpoofValues(const char *manufacturer, const char *brand, const char *model,
                         const char *device, const char *product, const char *fingerprint, const char *serial) {
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    auto updated = g_device_values;
    if (manufacturer != nullptr) updated["ro.product.manufacturer"] = manufacturer;
    if (brand != nullptr) updated["ro.product.brand"] = brand;
    if (model != nullptr) updated["ro.product.model"] = model;
    if (device != nullptr) updated["ro.product.device"] = device;
    if (product != nullptr) updated["ro.product.name"] = product;
    if (fingerprint != nullptr) updated["ro.build.fingerprint"] = fingerprint;
    if (serial != nullptr) {
        updated["ro.serialno"] = serial;
        updated["ro.boot.serialno"] = serial;
    }
    // An allocation failure must leave the last complete identity intact.
    g_device_values.swap(updated);
}

void setSimSpoofValues(const std::unordered_map<std::string, std::string> &values) {
    auto updated = values;
    std::lock_guard<std::mutex> lock(g_spoof_mutex);
    g_sim_values.swap(updated);
}

int my_system_property_get(const char *name, char *value) {
    std::string replacement;
    if (lookup_value(name, replacement)) {
        // __system_property_get callers traditionally allocate PROP_VALUE_MAX bytes.
        size_t length = std::min(replacement.size(), static_cast<size_t>(PROP_VALUE_MAX - 1));
        memcpy(value, replacement.data(), length);
        value[length] = '\0';
        return static_cast<int>(length);
    }
    // Do not hold our mutex across libc: libc may call the callback hook below.
    if (orig_system_property_get) return orig_system_property_get(name, value);
    value[0] = '\0';
    return 0;
}

struct ReadContext { PropertyCallback callback; void *cookie; };
static void property_callback(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *context = static_cast<ReadContext *>(cookie);
    std::string replacement;
    context->callback(context->cookie, name, lookup_value(name, replacement) ? replacement.c_str() : value, serial);
}

static void my_property_read_callback(const prop_info *info, PropertyCallback callback, void *cookie) {
    if (orig_property_read_callback == nullptr) return;
    ReadContext context{callback, cookie};
    orig_property_read_callback(info, property_callback, &context);
}

static void install_property_hooks() {
    void *handle = xdl_open("libc.so", XDL_DEFAULT);
    if (handle == nullptr) return;
    void *get = xdl_dsym(handle, "__system_property_get", nullptr);
    if (get != nullptr) DobbyHook(get, reinterpret_cast<void *>(my_system_property_get),
                                reinterpret_cast<void **>(&orig_system_property_get));
    // Modern SystemProperties/TelephonyProperties uses this path rather than property_get.
    void *read = xdl_dsym(handle, "__system_property_read_callback", nullptr);
    if (read != nullptr) DobbyHook(read, reinterpret_cast<void *>(my_property_read_callback),
                                 reinterpret_cast<void **>(&orig_property_read_callback));
    xdl_close(handle);
    LOGD("Device/SIM property hooks installed where available");
}

__attribute__((constructor)) void init_virtual_spoof() {
    install_property_hooks();
}
