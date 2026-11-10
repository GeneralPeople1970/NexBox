#pragma once
#include <android/log.h>

#define TAG "NativeCore"

// No unconditional Logcat output: Java owns opt-in collection, redaction and ADB mirroring.
int nexbox_log_print(int priority, const char *tag, const char *format, ...)
        __attribute__((format(printf, 3, 4)));
#define log_print_error(...) nexbox_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define log_print_debug(...) nexbox_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#define ALOGE(...) log_print_error(__VA_ARGS__)
#define ALOGD(...) log_print_debug(__VA_ARGS__)

#ifndef SPEED_LOG_H
#define SPEED_LOG_H 1

#endif
