package com.yourapp.yamahaarranger.style

import com.yourapp.yamahaarranger.ui.DebugLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StyleRepository @Inject constructor(
    private val bridge: NativeStyleBridge
) {
    fun loadStyle(fileName: String, rawBytes: ByteArray): ParsedStyle? {
        if (!bridge.nativeParseStyle(rawBytes)) {
            Timber.w("Failed to parse style: $fileName")
            return null
        }

        // ═══ Dump SEMUA marker text asli ═══
        try {
            val markers = bridge.nativeDumpMarkers(rawBytes)
            DebugLog.add("🔍 Markers in file:")
            markers.split("]").forEach { m ->
                val trimmed = m.trim()
                if (trimmed.isNotEmpty()) DebugLog.add("  [${trimmed.replace("[", "")}]")
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Dump error: ${e.message}")
        }

        // ═══ Section names yang parser detect ═══
        val detectedSections = bridge.nativeGetSectionNames()
        DebugLog.add("📋 Detected sections: ${detectedSections.joinToString(", ")}")

        // ═══ Voice map ═══
        val voiceMap = mutableMapOf<Int, String>()
        try {
            val raw = bridge.nativeExtractVoiceMap(rawBytes)
            if (raw.isNotEmpty()) {
                raw.split(";").forEach { entry ->
                    if (entry.isBlank()) return@forEach
                    val parts = entry.split(":", limit = 2)
                    if (parts.size == 2) {
                        val partNum = parts[0].toIntOrNull()
                        val voiceName = parts[1].trim()
                        if (partNum != null && partNum in 1..16 && voiceName.isNotEmpty()) {
                            voiceMap[partNum] = voiceName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Voice map error: ${e.message}")
        }
        DebugLog.add("🎼 Voice map: $voiceMap")

        val ppq = bridge.nativeGetPpq()
        val sections = detectedSections.associateWith { sectionName ->
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
                        val midiChannel = status and 0x0F
                        add(StyleNoteEvent(tick, isNoteOn, data1, data2, midiChannel))
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
            Timber.w("Style parsed but yielded no sections: $fileName")
            return null
        }

        return ParsedStyle(fileName, ppq, sections, voiceMap)
    }
}