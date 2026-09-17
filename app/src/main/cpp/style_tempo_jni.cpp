#include <jni.h>
#include <vector>
#include <cstdint>
#include "smf_reader.h"

extern "C" JNIEXPORT jdouble JNICALL
Java_com_yourapp_yamahaarranger_style_NativeStyleBridge_nativeGetDefaultTempoBpm(
    JNIEnv* env, jobject, jbyteArray styBytes) {
    if (styBytes == nullptr) return 120.0;

    const jsize len = env->GetArrayLength(styBytes);
    if (len <= 0) return 120.0;

    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(
        styBytes, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    SmfReader reader;
    if (!reader.parse(buf.data(), buf.size())) return 120.0;
    return reader.defaultTempoBpm();
}
