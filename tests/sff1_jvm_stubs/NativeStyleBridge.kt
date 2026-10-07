package com.yourapp.yamahaarranger.style

/** Compile repository without Android JNI initialization. Every method fails
 * if used: S1 invokes only actual private decoders, not loadStyle or this facade.
 */
class NativeStyleBridge {
    fun nativeGetDialectCode(): Int = error("JNI facade must not execute")
    fun nativeParseStyle(bytes: ByteArray): Boolean = error("JNI facade must not execute")
    fun nativeGetCasmSemanticMetadata(): String = error("JNI facade must not execute")
    fun nativeGetPpq(): Int = error("JNI facade must not execute")
    fun nativeGetDefaultTempoBpm(): Double = error("JNI facade must not execute")
    fun nativeGetSectionNames(): Array<String> = error("JNI facade must not execute")
    fun nativeGetSectionLengthTicks(name: String): Int = error("JNI facade must not execute")
    fun nativeGetPartCount(name: String): Int = error("JNI facade must not execute")
    fun nativeGetPartName(name: String, index: Int): String = error("JNI facade must not execute")
    fun nativeGetPartEvents(name: String, index: Int): IntArray = error("JNI facade must not execute")
    fun nativeGetPartCasm(name: String, index: Int): String = error("JNI facade must not execute")
    fun nativeExtractVoiceMap(bytes: ByteArray): String = error("JNI facade must not execute")
    fun nativeDumpMarkers(bytes: ByteArray): String = error("JNI facade must not execute")
}
