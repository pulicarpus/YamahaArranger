package com.yourapp.yamahaarranger.style

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StyleRepository @Inject constructor(
    private val bridge: NativeStyleBridge
) {
    /** Returns null if the file couldn't be parsed as an SMF-based .sty. */
    fun loadStyle(fileName: String, rawBytes: ByteArray): ParsedStyle? {
        if (!bridge.nativeParseStyle(rawBytes)) {
            Timber.w("Failed to parse style: $fileName")
            return null
        }

        val ppq = bridge.nativeGetPpq()
        val sectionNames = bridge.nativeGetSectionNames()
        val sections = sectionNames.associateWith { sectionName ->
            val partCount = bridge.nativeGetPartCount(sectionName)
            val parts = (0 until partCount).map { partIndex ->
                val name = bridge.nativeGetPartName(sectionName, partIndex)
                val flat = bridge.nativeGetPartEvents(sectionName, partIndex)
                val events = buildList {
                    var i = 0
                    while (i + 3 < flat.size) {
                        val tick = flat[i]
                        val status = flat[i + 1]
                        val data1 = flat[i + 2]
                        val data2 = flat[i + 3]
                        val isNoteOn = (status and 0xF0) == 0x90 && data2 > 0
                        add(StyleNoteEvent(tick, isNoteOn, data1, data2))
                        i += 4
                    }
                }
                StylePartModel(name, events)
            }
            StyleSectionModel(
                name = sectionName,
                lengthTicks = bridge.nativeGetSectionLengthTicks(sectionName),
                parts = parts
            )
        }

        if (sections.isEmpty()) {
            Timber.w("Style parsed but yielded no recognizable sections: $fileName")
            return null
        }

        return ParsedStyle(fileName, ppq, sections)
    }
}
