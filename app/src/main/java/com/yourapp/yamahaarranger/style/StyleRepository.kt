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
        val meter = detectStyleMeter(rawBytes, ppq)
        DebugLog.add("🥁 Style meter: ${meter.numerator}/${meter.denominator}")
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
                    DebugLog.add("🎛 $sectionName src=${policies.first().sourceChannel} policies=${policies.size} ranges=${policies.joinToString { "${it.sourceNoteLow}-${it.sourceNoteHigh}:NTR${it.ntr}/NTT${it.ntt}/MASK${it.chordMuteMask.toString(16)}${if (it.bassOn) "+BASS" else ""}" }}")
                }
                val setup = extractVoiceSetup(events)
                val sourceCh = events.firstOrNull()?.channel ?: -1
                DebugLog.add(
                    "🎚 SETUP $sectionName part=$partIndex srcCh=$sourceCh bank=${setup.bankMsb}/${setup.bankLsb} pc=${setup.program} " +
                        "mix=${setup.volume}/${setup.pan}/${setup.expression}/${setup.reverbSend}/${setup.chorusSend} " +
                        "tick0=${events.count { it.tick == 0 && (it.isControlChange || it.isProgramChange) }}"
                )
                StylePartModel(
                    name = bridge.nativeGetPartName(sectionName, partIndex),
                    events = events,
                    casm = policies.firstOrNull(),
                    casmPolicies = policies,
                    program = setup.program,
                    bankMsb = setup.bankMsb,
                    bankLsb = setup.bankLsb,
                    volume = setup.volume,
                    pan = setup.pan,
                    expression = setup.expression,
                    reverbSend = setup.reverbSend,
                    chorusSend = setup.chorusSend
                )
            }
            StyleSectionModel(sectionName, bridge.nativeGetSectionLengthTicks(sectionName), parts)
        }

        if (sections.isEmpty()) { Timber.w("Style parsed but yielded no sections: $fileName"); return null }
        return /* SFF_CASM_METADATA_BEGIN */withCasmSemanticMetadata(/* SFF_CASM_METADATA_END *//* SFF_DIALECT_METADATA_BEGIN */withDialectMetadata(/* SFF_DIALECT_METADATA_END */ParsedStyle(fileName, ppq, sections, voiceMap, defaultTempoBpm, meter)/* SFF_DIALECT_METADATA_BEGIN */, bridge.nativeGetDialectCode())/* SFF_DIALECT_METADATA_END *//* SFF_CASM_METADATA_BEGIN */, bridge.nativeGetCasmSemanticMetadata())/* SFF_CASM_METADATA_END */
    }

/* SFF_DIALECT_METADATA_BEGIN */    /** Snapshot identity once; share the same immutable value with sections. */
    private fun withDialectMetadata(style: ParsedStyle, nativeCode: Int): ParsedStyle {
        val identity = StyleDialectIdentity.fromNativeCode(nativeCode)
        return style.copy(dialectIdentity = identity, sections = style.sections.mapValues { (_, section) ->
            section.copy(dialectIdentity = identity)
        })
    }

/* SFF_DIALECT_METADATA_END *//* SFF_CASM_METADATA_BEGIN */    /** Separate read-only provenance snapshot: legacy policy objects stay identical. */
    private fun withCasmSemanticMetadata(style: ParsedStyle, protocol: String): ParsedStyle {
        val descriptors = ArrayList<CasmSemanticDescriptor>()
        val bindings = ArrayList<CasmSemanticBinding>()
        var valid = style.dialectIdentity.dialect == StyleDialect.SFF1
        try {
            val rows = protocol.lineSequence().filter { it.isNotEmpty() }.toList()
            require(rows.firstOrNull() == "S3\t1\t1")
            var sawBinding = false
            for(row in rows.drop(1)) {
                val fields = row.split('\t')
                when(fields.first()) {
                    "D" -> {
                        require(!sawBinding && fields.size == 6)
                        val index = fields[1].toInt(); val cseg = fields[2].toInt(); val offset = fields[4].toLong()
                        require(index == descriptors.size && cseg >= 0 && offset >= 0)
                        require(fields[3].length == 4 && fields[5].length % 2 == 0 && fields[5].all { it in "0123456789abcdef" })
                        descriptors += CasmSemanticDescriptor(index,cseg,fields[3],offset,fields[5])
                    }
                    "B" -> {
                        sawBinding = true
                        require(fields.size == 6)
                        val section = fields[1]; val part = fields[2].toInt(); val policy = fields[3].toInt()
                        val descriptor = fields[4].toInt(); val cntt = fields[5].toInt()
                        val effective = style.sections.getValue(section).parts[part].casmPolicies[policy]
                        val raw = descriptors[descriptor]
                        require(raw.tag == "Ctab" && raw.ctab != null && raw.ctab!!.source == effective.sourceChannel)
                        require(cntt == -1 || descriptors[cntt].tag == "Cntt")
                        require(bindings.none { it.section == section && it.partIndex == part && it.policyIndex == policy })
                        bindings += CasmSemanticBinding(section,part,policy,descriptor,cntt,effective,raw,if(cntt == -1) null else descriptors[cntt])
                    }
                    else -> require(false) { "Unknown metadata row" }
                }
            }
            val policyCount = style.sections.values.sumOf { section -> section.parts.sumOf { it.casmPolicies.size } }
            require(bindings.size == policyCount)
        } catch (_: IllegalArgumentException) { valid = false
        } catch (_: IndexOutOfBoundsException) { valid = false
        } catch (_: NoSuchElementException) { valid = false }
        val snapshot = if(valid) CasmSemanticSnapshot(CasmSemanticStatus.PRESERVED,
            java.util.Collections.unmodifiableList(descriptors),java.util.Collections.unmodifiableList(bindings),protocol)
            else CasmSemanticSnapshot(rawProtocol=protocol)
        return style.copy(casmSemanticSnapshot=snapshot, sections=style.sections.mapValues { (name,section) ->
            section.copy(casmSemanticBindings=java.util.Collections.unmodifiableList(snapshot.bindings.filter { it.section == name }))
        })
    }

/* SFF_CASM_METADATA_END */    /** Read MIDI time-signature meta FF 58 04 nn dd cc bb. */
    private fun detectStyleMeter(rawBytes: ByteArray, ppq: Int): StyleMeter {
        for (i in 0 until rawBytes.size - 7) {
            if ((rawBytes[i].toInt() and 0xFF) == 0xFF &&
                (rawBytes[i + 1].toInt() and 0xFF) == 0x58 &&
                (rawBytes[i + 2].toInt() and 0xFF) == 0x04) {
                val numerator = (rawBytes[i + 3].toInt() and 0xFF).coerceIn(1, 32)
                val denominatorPower = (rawBytes[i + 4].toInt() and 0xFF).coerceIn(0, 5)
                val denominator = 1 shl denominatorPower
                return StyleMeter(numerator, denominator, ppq.coerceAtLeast(1))
            }
        }
        return StyleMeter(4, 4, ppq.coerceAtLeast(1))
    }

    private data class VoiceSetup(
        val program: Int, val bankMsb: Int, val bankLsb: Int,
        val volume: Int, val pan: Int, val expression: Int,
        val reverbSend: Int, val chorusSend: Int
    )

    private fun extractVoiceSetup(events: List<StyleNoteEvent>): VoiceSetup {
        var msb = 0; var lsb = 0; var program = -1
        var volume = -1; var pan = -1; var expression = -1
        var reverb = -1; var chorus = -1
        events.sortedBy { it.tick }.forEach { e ->
            if (e.tick != 0) return@forEach
            when {
                e.isControlChange && e.note == 0 -> msb = e.velocity
                e.isControlChange && e.note == 32 -> lsb = e.velocity
                e.isControlChange && e.note == 7 -> volume = e.velocity
                e.isControlChange && e.note == 10 -> pan = e.velocity
                e.isControlChange && e.note == 11 -> expression = e.velocity
                e.isControlChange && e.note == 91 -> reverb = e.velocity
                e.isControlChange && e.note == 93 -> chorus = e.velocity
                e.isProgramChange -> program = e.note
            }
        }
        return VoiceSetup(program, msb, lsb, volume, pan, expression, reverb, chorus)
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
            val p = record.split('|', limit = 15)
            if (p.size != 12 && p.size != 14 && p.size != 15) return@mapNotNull null
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
                    chordMuteMask = if (p.size >= 15) p[12].toLong() else -1L,
                    sourceNoteLow = if (p.size >= 15) p[13].toInt() else if (p.size >= 14) p[12].toInt() else 0,
                    sourceNoteHigh = if (p.size >= 15) p[14].toInt() else if (p.size >= 14) p[13].toInt() else 127
                )
            } catch (_: NumberFormatException) { null }
        }
    }
}
