#pragma once
#include <cstdarg>
#include <cstdio>
#include <string>
#include <vector>
constexpr int ANDROID_LOG_INFO=1;
namespace mock_bass { inline std::vector<std::string> logs; }
inline int __android_log_print(int, const char*, const char* fmt, ...) {
    char buf[2048]; va_list args; va_start(args,fmt); vsnprintf(buf,sizeof(buf),fmt,args); va_end(args);
    mock_bass::logs.emplace_back(buf); return 0;
}
