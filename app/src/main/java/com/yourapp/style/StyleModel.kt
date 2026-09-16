package com.yourapp.yamahaarranger.style

data class StyleNoteEvent(
    val tick: Int,
    val isNoteOn: Boolean,
    val note: Int,
    val velocity: Int,
    val channel: Int = 0
)

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
    val bassOn: Boolean
)

data class StylePartModel(
    val name: String,
    val events: List<StyleNoteEvent>,
    val casm: CasmPolicyModel? = null
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
