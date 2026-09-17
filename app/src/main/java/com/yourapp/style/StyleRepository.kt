package com.yourapp.yamahaarranger.style

import com.yourapp.yamahaarranger.ui.DebugLog
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StyleRepository @Inject constructor(private val bridge: NativeStyleBridge) {
    fun loadStyle(fileName: String, rawBytes: ByteArray): ParsedStyle? {
        if (!bridge.nativeParseStyle(rawBytes)) { Timber.w("Failed to parse style: $fileName"); return null }

        try {
            val markers = bridge.nativeDumpMarkers(rawBytes)
            DebugLog.add("🔍 Markers in file:")
            markers.split("]").forEach { m ->
                val trimmed = m.trim()
                if (trimmed.isNotEmpty()) DebugLog.add("  [${trimmed.replace("[", "")}]")
            }
        } catch (e: Exception) { DebugLog.add("❌ Dump error: ${e.message}") }

        val detectedSections = bridge.nativeGetSectionNames()
        DebugLog.add("📋 Detected sections: ${detectedSections.joinToString(", ")}")

        val voiceMap = mutableMapOf<Int, String>()
        try {
            bridge.nativeExtractVoiceMap(rawBytes).split(";").forEach { entry ->
                if (entry.isBlank()) return@forEach
                val p = entry.split(":", limit = 2)
                if (p.size == 2) {
                    val channel = p[0].toIntOrNull(); val name = p[1].trim()
                    if (channel != null && channel in 1..16 && name.isNotEmpty()) voiceMap[channel] = name
                }
            }
        } catch (e: Exception) { DebugLog.add("❌ Voice map error: ${e.message}") }
        DebugLog.add("🎼 Legacy voice map: $voiceMap")

        val ppq = bridge.nativeGetPpq()
        val defaultTempoBpm = bridge.nativeGetDefaultTempoBpm()
            .toInt()
            .coerceIn(20, 280)
        DebugLog.add("🥁 Default style tempo: $defaultTempoBpm BPM")

        val sections = detectedSections.associateWith { sectionName ->
            val partCount = bridge.nativeGetPartCount(sectionName)
            val parts = (0 until partCount).map { partIndex ->
                val flat = bridge.nativeGetPartEvents(sectionName, partIndex)
                val events = decodePackedEvents(flat)
                val noteCount = events.count { it.isNoteOn || (it.status and 0xF0) == 0x80 }
                val extraCount = events.size - noteCount
                if (extraCount > 0) DebugLog.add("📦 $sectionName part=$partIndex preserved ${events.size} events ($extraCount non-note)")
                val rawCasm = bridge.nativeGetPartCasm(sectionName, partIndex)
                val policies = parseCasmPolicies(rawCasm)
                if (policies.isNotEmpty()) {
                    DebugLog.add("🎛 $sectionName src=${policies.first().sourceChannel} policies=${policies.size} ranges=${policies.joinToString { "${it.sourceNoteLow}-${it.sourceNoteHigh}:NTR${it.ntr}/NTT${it.ntt}${if (it.bassOn) "+BASS" else ""}" }}")
                }
                StylePartModel(bridge.nativeGetPartName(sectionName, partIndex), events, policies.firstOrNull(), policies)
            }
            StyleSectionModel(sectionName, bridge.nativeGetSectionLengthTicks(sectionName), parts)
        }

        if (sections.isEmpty()) { Timber.w("Style parsed but yielded no sections: $fileName"); return null }
        return ParsedStyle(fileName, ppq, sections, voiceMap, defaultTempoBpm)
    }

    private fun decodePackedEvents(flat: IntArray): List<StyleNoteEvent> {
        val result = ArrayList<StyleNoteEvent>()
        var i = 0
        while (i + 5 < flat.size) {
            val tick = flat[i]
            val status = flat[i + 1] and 0xFF
            val data1 = flat[i + 2] and 0xFF
            val data2 = flat[i + 3] and 0xFF
            val metaType = flat[i + 4] and 0xFF
            val payloadLength = flat[i + 5]
            if (payloadLength < 0 || i + 6 + payloadLength > flat.size) {
                DebugLog.add("⚠️ Invalid packed MIDI event at index $i; stopping decode")
                break
            }
            val payload = if (payloadLength == 0) ByteArray(0) else
                ByteArray(payloadLength) { offset -> (flat[i + 6 + offset] and 0xFF).toByte() }
            val channel = if (status in 0x80..0xEF) status and 0x0F else 0
            val hi = status and 0xF0
            val isNoteOn = hi == 0x90 && data2 > 0
            result += StyleNoteEvent(
                tick = tick,
                isNoteOn = isNoteOn,
                note = data1,
                velocity = if (hi == 0xC0 || hi == 0xD0) 0 else data2,
                channel = channel,
                status = status,
                metaType = metaType,
                payload = payload
            )
            i += 6 + payloadLength
        }
        return result
    }

    private fun parseCasmPolicies(raw: String): List<CasmPolicyModel> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { record ->
            val p = record.split('|', limit = 14)
            if (p.size != 12 && p.size != 14) return@mapNotNull null
            try {
                CasmPolicyModel(
                    sourceChannel = p[0].toInt(),
                    destinationChannel = p[1].toInt(),
                    voiceName = p[2],
                    sourceChordRoot = p[3].toInt(),
                    sourceChordType = p[4].toInt(),
                    ntr = p[5].toInt(),
                    ntt = p[6].toInt(),
                    highKey = p[7].toInt(),
                    noteLimitLow = p[8].toInt(),
                    noteLimitHigh = p[9].toInt(),
                    rtr = p[10].toInt(),
                    bassOn = p[11].toInt() != 0,
                    sourceNoteLow = if (p.size >= 14) p[12].toInt() else 0,
                    sourceNoteHigh = if (p.size >= 14) p[13].toInt() else 127
                )
            } catch (_: NumberFormatException) { null }
        }
    }
}
