package com.yourapp.yamahaarranger.style

/**
 * Yamaha CASM playback policy decoded from Ctab/Ctb2.
 * Values follow Yamaha SFF/SFF2 conventions:
 * NTR: 0 Root Trans, 1 Root Fixed, 2 Guitar, 3 Bypass
 * NTT: 0 Bypass, 1 Melody, 2 Chord, 3 Bass, 4 Melodic Minor, 5 Harmonic Minor
 * RTR: 0 Stop, 1 Pitch Shift, 2 Pitch Shift to Root, 3 Retrigger,
 *      4 Retrigger to Root, 5 Note Generator
 */
data class YamahaCasmPolicy(
    val sourceRoot: Int = 0,
    val sourceChord: Int = 2,
    val ntr: Int = 0,
    val ntt: Int = 0,
    val highKey: Int = 127,
    val noteLow: Int = 0,
    val noteHigh: Int = 127,
    val rtr: Int = 0,
    val bassOn: Boolean = false
)

data class StyleNoteEvent(
    val tick: Int,
    val isNoteOn: Boolean,
    val note: Int,
    val velocity: Int,
    val channel: Int = 0
)

data class StylePartModel(
    val name: String,
    val events: List<StyleNoteEvent>,
    val channel: Int = events.firstOrNull()?.channel ?: 0,
    val casmPolicy: YamahaCasmPolicy? = null
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
