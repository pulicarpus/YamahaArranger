package com.yourapp.yamahaarranger.soundfont

data class Sf2File(
    val info: Map<String, String>,
    val presets: List<Sf2Preset>,
    val instruments: List<Sf2Instrument>,
    val samples: List<Sf2Sample>
)

data class Sf2Preset(
    val name: String,
    val program: Int,
    val bank: Int,
    val zones: List<Sf2PresetZone>
)

data class Sf2PresetZone(
    val keyRange: IntRange? = null,
    val velocityRange: IntRange? = null,
    val instrumentIndex: Int? = null,
    val generators: Map<Int, Int> = emptyMap()
)

data class Sf2Instrument(
    val name: String,
    val zones: List<Sf2InstrumentZone>
)

data class Sf2InstrumentZone(
    val keyRange: IntRange? = null,
    val velocityRange: IntRange? = null,
    val sampleIndex: Int? = null,
    val rootKey: Int? = null,
    val coarseTune: Int? = null,
    val fineTune: Int? = null,
    val pan: Int? = null,
    val attenuation: Int? = null,
    val sampleModes: Int? = null,
    val exclusiveClass: Int? = null,
    val generators: Map<Int, Int> = emptyMap()
)

data class Sf2Sample(
    val name: String,
    val start: Long,
    val end: Long,
    val loopStart: Long,
    val loopEnd: Long,
    val sampleRate: Long,
    val originalPitch: Int,
    val pitchCorrection: Int,
    val sampleType: Int,
    val sampleLink: Int
)

object Sf2Generator {
    const val INITIAL_FILTER_FC = 8
    const val INITIAL_FILTER_Q = 9
    const val PAN = 17
    const val DELAY_VOL_ENV = 33
    const val ATTACK_VOL_ENV = 34
    const val HOLD_VOL_ENV = 35
    const val DECAY_VOL_ENV = 36
    const val SUSTAIN_VOL_ENV = 37
    const val RELEASE_VOL_ENV = 38
    const val INSTRUMENT = 41
    const val KEY_RANGE = 43
    const val VEL_RANGE = 44
    const val ATTENUATION = 48
    const val COARSE_TUNE = 51
    const val FINE_TUNE = 52
    const val SAMPLE_ID = 53
    const val SAMPLE_MODES = 54
    const val SCALE_TUNING = 56
    const val EXCLUSIVE_CLASS = 57
    const val OVERRIDING_ROOT_KEY = 58
}
