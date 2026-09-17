package com.yourapp.yamahaarranger.style

import javax.inject.Inject

class NativeStyleBridge @Inject constructor() {

    companion object {
        init {
            System.loadLibrary("yamaha_arranger_native")
        }
    }

    external fun nativeParseStyle(styBytes: ByteArray): Boolean
    external fun nativeGetPpq(): Int
    external fun nativeGetDefaultTempoBpm(): Double
    external fun nativeGetSectionNames(): Array<String>
    external fun nativeGetSectionLengthTicks(sectionName: String): Int
    external fun nativeGetPartCount(sectionName: String): Int
    external fun nativeGetPartName(sectionName: String, partIndex: Int): String
    external fun nativeGetPartEvents(sectionName: String, partIndex: Int): IntArray
    external fun nativeFindCasm(styBytes: ByteArray): String
    external fun nativeExtractVoiceMap(styBytes: ByteArray): String
    external fun nativeDumpMarkers(styBytes: ByteArray): String
}
