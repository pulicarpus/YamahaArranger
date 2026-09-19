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

/** Runtime overrides for a style destination channel. These do not modify the source style file. */
data class StyleChannelOverride(
    val volume: Int = 127,
    val program: Int? = null,
    val bank: Int? = null,
    val transpose: Int = 0,
    val muted: Boolean = false,
    val pan: Int = 64,
    val expression: Int = 127,
    val reverbSend: Int = 40,
    val chorusSend: Int = 0
)

data class StylePartModel(
    val name: String,
    val events: List<StyleNoteEvent>,
    val casm: CasmPolicyModel? = null,
    val casmPolicies: List<CasmPolicyModel> = emptyList(),
    /** Actual MIDI setup from the style track. */
    val program: Int = -1,
    val bankMsb: Int = 0,
    val bankLsb: Int = 0
)

data class StyleSectionModel(
    val name: String,
    val lengthTicks: Int,
    val parts: List<StylePartModel>
)

data class ParsedStyle(
    val fileName: String,
    val ppq: Int,
    val sections: Map<String, StyleSectionModel>,
    val voiceMap: Map<Int, String> = emptyMap(),
    val defaultTempoBpm: Int = 120
)
