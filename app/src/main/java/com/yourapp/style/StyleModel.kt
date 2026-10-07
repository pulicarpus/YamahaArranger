package com.yourapp.yamahaarranger.style

data class StyleNoteEvent(
    val tick: Int,
    val isNoteOn: Boolean,
    val note: Int,
    val velocity: Int,
    val channel: Int = 0,
    val status: Int = if (isNoteOn) 0x90 or channel else 0x80 or channel,
    val metaType: Int = 0,
    val payload: ByteArray = ByteArray(0)
) {
    val isChannelVoice: Boolean get() = status in 0x80..0xEF
    val isControlChange: Boolean get() = (status and 0xF0) == 0xB0
    val isProgramChange: Boolean get() = (status and 0xF0) == 0xC0
    val isPitchBend: Boolean get() = (status and 0xF0) == 0xE0
    val isChannelPressure: Boolean get() = (status and 0xF0) == 0xD0
    val isPolyPressure: Boolean get() = (status and 0xF0) == 0xA0
    val isSysEx: Boolean get() = status == 0xF0 || status == 0xF7
}

data class CasmPolicyModel(
    val sourceChannel: Int,
    val destinationChannel: Int,
    val voiceName: String,
    val sourceChordRoot: Int,
    val sourceChordType: Int,
    val ntr: Int,
    val ntt: Int,
    val highKey: Int,
    val noteLimitLow: Int,
    val noteLimitHigh: Int,
    val rtr: Int,
    val bassOn: Boolean,
    /** 34-bit Yamaha CASM chord-mute/play mask. -1 means unavailable in legacy data. */
    val chordMuteMask: Long = -1L,
    val sourceNoteLow: Int = 0,
    val sourceNoteHigh: Int = 127
)

/** Style-only CC7/CC11 trims (127 = unity); optional controller overrides. Source MIDI stays intact. */
data class StyleChannelOverride(
    val volume: Int = 127,
    val program: Int? = null,
    val bank: Int? = null,
    val transpose: Int = 0,
    val muted: Boolean = false,
    val pan: Int? = null,
    val expression: Int = 127,
    val reverbSend: Int? = null,
    val chorusSend: Int? = null
)

data class StylePartModel(
    val name: String,
    val events: List<StyleNoteEvent>,
    val casm: CasmPolicyModel? = null,
    val casmPolicies: List<CasmPolicyModel> = emptyList(),
    /** Actual MIDI setup from the style track. */
    val program: Int = -1,
    val bankMsb: Int = 0,
    val bankLsb: Int = 0,
    val volume: Int = -1,
    val pan: Int = -1,
    val expression: Int = -1,
    val reverbSend: Int = -1,
    val chorusSend: Int = -1
)

data class StyleSectionModel(
    val name: String,
    val lengthTicks: Int,
    val parts: List<StylePartModel>/* SFF_DIALECT_METADATA_BEGIN */,
    val dialectIdentity: StyleDialectIdentity = StyleDialectIdentity()/* SFF_DIALECT_METADATA_END *//* SFF_CASM_METADATA_BEGIN */,
    val casmSemanticBindings: List<CasmSemanticBinding> = emptyList()/* SFF_CASM_METADATA_END */
)

data class StyleMeter(
    val numerator: Int,
    val denominator: Int,
    val ticksPerQuarter: Int
) {
    val ticksPerBeat: Int
        get() = (ticksPerQuarter * 4 / denominator.coerceAtLeast(1)).coerceAtLeast(1)
    val ticksPerBar: Int
        get() = (numerator.coerceAtLeast(1) * ticksPerBeat).coerceAtLeast(1)
}

data class ParsedStyle(
    val fileName: String,
    val ppq: Int,
    val sections: Map<String, StyleSectionModel>,
    val voiceMap: Map<Int, String> = emptyMap(),
    val defaultTempoBpm: Int = 120,
    val meter: StyleMeter = StyleMeter(4, 4, ppq)/* SFF_DIALECT_METADATA_BEGIN */,
    val dialectIdentity: StyleDialectIdentity = StyleDialectIdentity()/* SFF_DIALECT_METADATA_END *//* SFF_CASM_METADATA_BEGIN */,
    val casmSemanticSnapshot: CasmSemanticSnapshot = CasmSemanticSnapshot()/* SFF_CASM_METADATA_END */
)/* SFF_DIALECT_METADATA_BEGIN */

enum class StyleDialect { UNKNOWN, SFF1, SFF2 }
enum class StyleDialectEvidence { NONE, SMF_TICK0_MARKER_SFF1 }

/** Identity is evidence, never a promise of musical semantic support. */
data class StyleDialectIdentity(
    val dialect: StyleDialect = StyleDialect.UNKNOWN,
    val evidence: StyleDialectEvidence = StyleDialectEvidence.NONE
) {
    companion object {
        const val SFF2_DETECTION_STATUS = "UNVERIFIED_NEEDS_FIXTURE"
        fun fromNativeCode(code: Int): StyleDialectIdentity = when(code) {
            1 -> StyleDialectIdentity(StyleDialect.SFF1, StyleDialectEvidence.SMF_TICK0_MARKER_SFF1)
            else -> StyleDialectIdentity() // Includes reserved SFF2 code: no verified positive detector.
        }
    }
}
/* SFF_DIALECT_METADATA_END *//* SFF_CASM_METADATA_BEGIN */
/** S3 observation/preservation only. None of these fields drives playback. */
enum class CasmSemanticStatus { PRESERVED, SOURCE_ABSENT, PARSED_BUT_UNATTACHED, PARSED_BUT_FILTERED, PROJECTION_LOSS, COLLAPSED, UNSUPPORTED, UNKNOWN }
data class CasmRawNtr(val raw: Int) { val executionCode: Int get() = raw and 127; val hasExplicitExecutionBranch: Boolean get() = executionCode in 0..3 }
data class CasmRawNtt(val raw: Int) { val executionCode: Int get() = raw and 127; val hasExplicitExecutionBranch: Boolean get() = executionCode in 0..5 }
data class CasmRawRtr(val raw: Int) { val executionCode: Int get() = raw and 127; val hasExplicitExecutionBranch: Boolean get() = executionCode in 0..5 }
data class CasmRawCtab(
    val source: Int, val destination: Int, val flagByte10: Int, val rootSelectionWordRaw: Int,
    val chordFieldHex: String, val sourceChordRoot: Int, val sourceChordType: Int,
    val ntr: CasmRawNtr, val nttByte: CasmRawNtt, val highKey: Int,
    val noteLow: Int, val noteHigh: Int, val rtr: CasmRawRtr, val tailHex: String
)
data class CasmSemanticDescriptor(val index: Int, val cseg: Int, val tag: String, val payloadOffset: Long, val rawHex: String) {
    private fun byte(offset: Int): Int = rawHex.substring(offset*2,offset*2+2).toInt(16)
    val ctab: CasmRawCtab? get() = if(tag != "Ctab" || rawHex.length < 54) null else CasmRawCtab(
        byte(0),byte(9),byte(10),(byte(11) shl 8) or byte(12),rawHex.substring(26,36),byte(18),byte(19),
        CasmRawNtr(byte(20)),CasmRawNtt(byte(21)),byte(22),byte(23),byte(24),CasmRawRtr(byte(25)),rawHex.substring(52))
    val status: CasmSemanticStatus get() = when {
        tag == "Ctb2" -> CasmSemanticStatus.UNSUPPORTED // No verified fixture/typed SFF2 schema.
        tag == "Ctab" && ctab != null -> CasmSemanticStatus.PRESERVED
        tag == "Cntt" && rawHex.length >= 4 -> CasmSemanticStatus.PRESERVED
        tag == "Sdec" -> CasmSemanticStatus.PRESERVED
        else -> CasmSemanticStatus.UNKNOWN
    }
}
data class CasmSemanticBinding(
    val section: String, val partIndex: Int, val policyIndex: Int, val descriptorIndex: Int,
    val cnttDescriptorIndex: Int, val effectivePolicy: CasmPolicyModel,
    val descriptor: CasmSemanticDescriptor, val cnttDescriptor: CasmSemanticDescriptor?
)
data class CasmSemanticSnapshot(
    val status: CasmSemanticStatus = CasmSemanticStatus.UNKNOWN,
    val descriptors: List<CasmSemanticDescriptor> = emptyList(),
    val bindings: List<CasmSemanticBinding> = emptyList(),
    val rawProtocol: String = ""
) {
    fun descriptorStatus(index: Int): CasmSemanticStatus {
        val descriptor = descriptors.getOrNull(index) ?: return CasmSemanticStatus.UNKNOWN
        if(descriptor.status != CasmSemanticStatus.PRESERVED)return descriptor.status
        return if(descriptor.tag == "Ctab" && bindings.none { it.descriptorIndex == index })
            CasmSemanticStatus.PARSED_BUT_UNATTACHED else CasmSemanticStatus.PRESERVED
    }
}
/* SFF_CASM_METADATA_END */