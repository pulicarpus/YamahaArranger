#pragma once
using jclass=void*; using jmethodID=void*; using jstring=void*;
constexpr int JNI_VERSION_1_6=0x10006, JNI_OK=0;
struct JNIEnv {
    jstring NewStringUTF(const char*) { return nullptr; }
    void CallStaticVoidMethod(jclass, jmethodID, jstring) {}
    void DeleteLocalRef(jstring) {}
};
struct JavaVM {
    int GetEnv(void**, int) { return -1; }
    int AttachCurrentThread(JNIEnv**, void*) { return -1; }
    void DetachCurrentThread() {}
};
