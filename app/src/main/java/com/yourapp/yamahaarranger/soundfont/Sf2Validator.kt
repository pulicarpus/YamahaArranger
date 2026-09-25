package com.yourapp.yamahaarranger.soundfont

data class Sf2ValidationResult(
    val errors: List<String>,
    val warnings: List<String>
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object Sf2Validator {

    fun validate(sf2: Sf2File): Sf2ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        sf2.presets.forEachIndexed { presetIndex, preset ->
            preset.zones.forEachIndexed { zoneIndex, zone ->
                val instrument = zone.instrumentIndex
                if (instrument != null && instrument !in sf2.instruments.indices) {
                    errors += "Preset $presetIndex zone $zoneIndex references invalid instrument $instrument"
                }
                checkRange(zone.keyRange, "preset $presetIndex zone $zoneIndex key", warnings)
                checkRange(zone.velocityRange, "preset $presetIndex zone $zoneIndex velocity", warnings)
            }
        }

        sf2.instruments.forEachIndexed { instrumentIndex, instrument ->
            instrument.zones.forEachIndexed { zoneIndex, zone ->
                val sample = zone.sampleIndex
                if (sample != null && sample !in sf2.samples.indices) {
                    errors += "Instrument $instrumentIndex zone $zoneIndex references invalid sample $sample"
                }
                checkRange(zone.keyRange, "instrument $instrumentIndex zone $zoneIndex key", warnings)
                checkRange(zone.velocityRange, "instrument $instrumentIndex zone $zoneIndex velocity", warnings)

                zone.sampleIndex?.let { sampleIndex ->
                    sf2.samples.getOrNull(sampleIndex)?.let { header ->
                        if (header.end < header.start) {
                            errors += "Sample $sampleIndex has end before start"
                        }
                        if (header.loopEnd < header.loopStart) {
                            warnings += "Sample $sampleIndex has loop end before loop start"
                        }
                        if (header.sampleRate <= 0) {
                            warnings += "Sample $sampleIndex has invalid sample rate"
                        }
                    }
                }
            }
        }

        if (sf2.presets.isEmpty()) warnings += "SF2 contains no presets"
        if (sf2.instruments.isEmpty()) warnings += "SF2 contains no instruments"
        if (sf2.samples.isEmpty()) warnings += "SF2 contains no sample headers"

        return Sf2ValidationResult(errors, warnings)
    }

    private fun checkRange(range: IntRange?, label: String, warnings: MutableList<String>) {
        if (range != null && (range.first !in 0..127 || range.last !in 0..127 || range.first > range.last)) {
            warnings += "Invalid $label range: $range"
        }
    }
}
