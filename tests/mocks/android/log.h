#pragma once
constexpr int ANDROID_LOG_INFO=1;
inline int __android_log_print(int, const char*, const char*, ...) { return 0; }
