package com.yourapp.yamahaarranger.soundfont

data class Sf2ZoneMatch(
    val preset: Sf2Preset,
    val presetZoneIndex: Int,
    val instrument: Sf2Instrument,
    val instrumentZoneIndex: Int,
    val sample: Sf2Sample
)

object Sf2Resolver {

    /**
     * Resolve the SF2 path for a MIDI bank/program/note/velocity.
     *
     * Global zones are intentionally not merged in V1; the raw generator maps
     * remain available for later V2 resolution of inherited generators.
     */
    fun findMatches(
        sf2: Sf2File,
        bank: Int,
        program: Int,
        note: Int,
        velocity: Int
    ): List<Sf2ZoneMatch> {
        val presets = sf2.presets.filter { it.bank == bank && it.program == program }
        val result = mutableListOf<Sf2ZoneMatch>()

        for (preset in presets) {
            preset.zones.forEachIndexed { pzIndex, pz ->
                if (!contains(pz.keyRange, note) || !contains(pz.velocityRange, velocity)) return@forEachIndexed
                val instrumentIndex = pz.instrumentIndex ?: return@forEachIndexed
                val instrument = sf2.instruments.getOrNull(instrumentIndex) ?: return@forEachIndexed

                instrument.zones.forEachIndexed { izIndex, iz ->
                    if (!contains(iz.keyRange, note) || !contains(iz.velocityRange, velocity)) return@forEachIndexed
                    val sampleIndex = iz.sampleIndex ?: return@forEachIndexed
                    val sample = sf2.samples.getOrNull(sampleIndex) ?: return@forEachIndexed
                    result += Sf2ZoneMatch(preset, pzIndex, instrument, izIndex, sample)
                }
            }
        }
        return result
    }

    private fun contains(range: IntRange?, value: Int): Boolean =
        range == null || value in range
}
