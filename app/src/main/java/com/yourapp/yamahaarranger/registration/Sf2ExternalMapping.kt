package com.yourapp.yamahaarranger.registration

/**
 * The single SF2 identity -> external Bank/Program mapping. The normal voice selector and Registration recall both use it,
 * so a recalled voice reaches an external keyboard through exactly the values the selector sends. Pure; no MIDI I/O.
 * Program numbers are 0-based MIDI values (Program Change data byte), never the 1-based numbers of a Data List.
 */
object Sf2ExternalMapping {
    /** Marks an identity derived from the SF2 voice, as opposed to one picked explicitly from the EXT voice list. */
    const val DERIVED_PROFILE = "SF2_DERIVED"

    /** Bank values above 127 are 14-bit (MSB*128+LSB); a plain bank is sent as MSB only. Null when MIDI cannot express it. */
    fun voiceFor(bank: Int, program: Int, label: String): ExternalVoice? {
        if (program !in 0..127 || bank !in 0..16383) return null
        val msb = if (bank >= 128) bank / 128 else bank
        val lsb = if (bank >= 128) bank % 128 else 0
        return ExternalVoice(msb, lsb, program, label.trim().take(24).ifBlank { "$msb/$lsb/$program" }, DERIVED_PROFILE)
    }

    fun voiceFor(ref: Sf2VoiceRef): ExternalVoice? = voiceFor(ref.bank, ref.program, ref.presetName)

    fun isDerived(voice: ExternalVoice?): Boolean = voice?.deviceProfile == DERIVED_PROFILE
}
