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

        val detectedSections = bridge.nativeGetSectionNames()
        DebugLog.add("📋 Detected sections: ${detectedSections.joinToString(", ")}")

        val voiceMap = mutableMapOf<Int, String>()
        try {
            val raw = bridge.nativeExtractVoiceMap(rawBytes)
            raw.split(";").forEach { entry ->
                if (entry.isBlank()) return@forEach
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) {
                    val partNum = parts[0].toIntOrNull()
                    val voiceName = parts[1].trim()
                    if (partNum != null && partNum in 1..16 && voiceName.isNotEmpty()) voiceMap[partNum] = voiceName
                }
            }
        } catch (e: Exception) {
            DebugLog.add("❌ Voice map error: ${e.message}")
        }
        DebugLog.add("🎼 Legacy voice map: $voiceMap")

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
                val casm = parseCasm(bridge.nativeGetPartCasm(sectionName, partIndex))
                if (casm != null) DebugLog.add("🎛 $sectionName src=${casm.sourceChannel} → dst=${casm.destinationChannel} ${casm.voiceName} NTR=${casm.ntr} NTT=${casm.ntt} HK=${casm.highKey} LIM=${casm.noteLimitLow}-${casm.noteLimitHigh} RTR=${casm.rtr}")
                StylePartModel(name, events, casm)
            }
            StyleSectionModel(sectionName, bridge.nativeGetSectionLengthTicks(sectionName), parts)
        }

        if (sections.isEmpty()) {
            Timber.w("Style parsed but yielded no sections: $fileName")
            return null
        }
        return ParsedStyle(fileName, ppq, sections, voiceMap)
    }

    private fun parseCasm(raw: String): CasmPolicyModel? {
        if (raw.isBlank()) return null
        val p = raw.split("|", limit = 12)
        if (p.size != 12) return null
        return try {
            CasmPolicyModel(
                sourceChannel = p[0].toInt(), destinationChannel = p[1].toInt(), voiceName = p[2],
                sourceChordRoot = p[3].toInt(), sourceChordType = p[4].toInt(), ntr = p[5].toInt(),
                ntt = p[6].toInt(), highKey = p[7].toInt(), noteLimitLow = p[8].toInt(),
                noteLimitHigh = p[9].toInt(), rtr = p[10].toInt(), bassOn = p[11].toInt() != 0
            )
        } catch (_: NumberFormatException) { null }
    }
}
