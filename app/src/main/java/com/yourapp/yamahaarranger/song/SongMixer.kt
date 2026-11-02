package com.yourapp.yamahaarranger.song

/** What the SONG does with the file's sustain pedal (CC64) on one channel. */
enum class SustainMode(val label: String) {
    /** CC64 from the file is played as written. */
    FOLLOW("FOLLOW MIDI"),
    /** CC64 from the file is dropped; the pedal is released once when the mode is chosen. */
    IGNORE("IGNORE CC64"),
    /** Every CC64 from the file is replaced by CC64 = 0 (the pedal is held OFF even if the file presses it again). */
    FORCE_OFF("FORCE OFF"),
}

/**
 * One SoundFont preset chosen by the user for a SONG channel (from the SF2s currently loaded by the app).
 * [bank] is the SF2 bank as the app lists it (127 / 128 = drum kit).
 */
data class SongVoice(val fontId: Long, val sf2: String, val bank: Int, val program: Int, val name: String) {
    val isDrum get() = bank == 127 || bank == 128
    val label get() = "$name  ($sf2 $bank:$program)"
}

/** The user's overrides for one SONG channel. null = follow the MIDI file. */
data class ChannelOverride(
    val voice: SongVoice? = null, val volume: Int? = null, val pan: Int? = null, val sustain: SustainMode = SustainMode.FOLLOW,
) {
    val isDefault get() = voice == null && volume == null && pan == null && sustain == SustainMode.FOLLOW
}

/** Result of binding a voice in the internal Song synth (see [SongVoiceBinder]). */
enum class VoiceBind(val label: String) {
    VERIFIED("voice active (read back from the synth)"),
    BOUND("voice mapped; the synth confirms it at the next note / drum-mode change"),
    NOT_FOUND("preset not found in the loaded SF2s: the MIDI voice is kept"),
    CLEARED("MIDI voice"),
}

/**
 * Installs a user voice on one channel of the internal Song synth ONLY (never the keyboard/Style stream, never MIDI OUT).
 * The synth plays the chosen preset when it receives Bank 0 / LSB 0 / Program 0 (melodic) or Program 0 (drum kit) on that channel;
 * the player sends exactly those in place of the file's Program Change.
 */
fun interface SongVoiceBinder { fun bind(channel: Int, voice: SongVoice?): VoiceBind }

/** Pure helpers that turn the user's overrides into the MIDI the outputs receive. */
object SongMixerRules {
    /** Messages that select the user's voice on the internal synth (see [SongVoiceBinder]). */
    fun voiceSelect(ch: Int, v: SongVoice): List<ByteArray> =
        if (v.isDrum) listOf(pc(ch, 0)) else listOf(cc(ch, 0, 0), cc(ch, 32, 0), pc(ch, 0))

    /**
     * What one file channel message becomes on one output. [internal] = the internal Song synth (voice overrides apply there only;
     * MIDI OUT keeps the file's Bank / Program). Volume, pan and sustain overrides apply to every output.
     * Returns the messages to send instead (empty = dropped, the message itself = unchanged).
     */
    fun transform(m: ByteArray, o: ChannelOverride, internal: Boolean): List<ByteArray> {
        val st = m[0].toInt() and 0xFF
        if (st >= 0xF0 || o.isDefault) return listOf(m)
        val ch = st and 0x0F
        val voice = if (internal) o.voice else null
        return when (st and 0xF0) {
            0xC0 -> if (voice != null) voiceSelect(ch, voice) else listOf(m)
            0xB0 -> when (m[1].toInt()) {
                0, 32 -> if (voice != null && !voice.isDrum) emptyList()          // melodic override: file bank never reaches the synth
                         else listOf(m)                                          // drum override: keep the file's bank (XG drum mode)
                7 -> o.volume?.let { listOf(cc(ch, 7, it)) } ?: listOf(m)
                10 -> o.pan?.let { listOf(cc(ch, 10, it)) } ?: listOf(m)
                64 -> when (o.sustain) {
                    SustainMode.FOLLOW -> listOf(m)
                    SustainMode.IGNORE -> emptyList()
                    SustainMode.FORCE_OFF -> listOf(cc(ch, 64, 0))
                }
                else -> listOf(m)
            }
            else -> listOf(m)
        }
    }

    /** The full override state of one channel (sent after a chase or a synth reset, and when an override is set). */
    fun state(ch: Int, o: ChannelOverride, internal: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (internal) o.voice?.let { out += voiceSelect(ch, it) }
        o.volume?.let { out += cc(ch, 7, it) }
        o.pan?.let { out += cc(ch, 10, it) }
        if (o.sustain != SustainMode.FOLLOW) out += cc(ch, 64, 0)
        return out
    }

    fun cc(ch: Int, n: Int, v: Int) = byteArrayOf((0xB0 or ch).toByte(), n.toByte(), v.coerceIn(0, 127).toByte())
    fun pc(ch: Int, p: Int) = byteArrayOf((0xC0 or ch).toByte(), p.coerceIn(0, 127).toByte())
}
