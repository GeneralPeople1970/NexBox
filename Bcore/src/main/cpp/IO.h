



#ifndef VIRTUALM_IO_H
#define VIRTUALM_IO_H

#include <jni.h>

#include <string>
#include "BoxCore.h"

using namespace std;

class IO {
public:
    static void init(JNIEnv *env);

    struct RelocateInfo {
        std::string targetPath;
        std::string relocatePath;
    };

    static void addRule(const char *targetPath, const char *relocatePath);

    static jstring redirectPath(JNIEnv *env, jstring path);

    static jobject redirectPath(JNIEnv *env, jobject path);

    static std::string redirectPath(const char *__path);
    static std::string logicalPath(const char *path);

    // Return the original pointer without allocating on a miss. A redirected pointer
    // belongs to storage and remains valid until the caller modifies that storage.
    static const char *redirectPath(const char *path, std::string &storage);
    static const char *logicalPath(const char *path, std::string &storage);
};


#endif 
