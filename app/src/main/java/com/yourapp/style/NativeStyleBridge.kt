package com.yourapp.yamahaarranger.style

import javax.inject.Inject

/**
 * Thin JNI wrapper around StyleParser (native/style_parser.cpp). Loading a
 * style is not on any realtime path, so allocation here (arrays, strings)
 * is fine — this only runs when the user picks a style from the browser.
 */
class NativeStyleBridge @Inject constructor() {

    companion object {
        init {
            System.loadLibrary("yamaha_arranger_native")
        }
    }

    external fun nativeParseStyle(styBytes: ByteArray): Boolean
    external fun nativeGetPpq(): Int
    external fun nativeGetSectionNames(): Array<String>
    external fun nativeGetSectionLengthTicks(sectionName: String): Int
    external fun nativeGetPartCount(sectionName: String): Int
    external fun nativeGetPartName(sectionName: String, partIndex: Int): String
    /** Flattened [tick, status, data1, data2] quadruples, note on/off only. */
    external fun nativeGetPartEvents(sectionName: String, partIndex: Int): IntArray
}
