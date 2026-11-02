package com.yourapp.yamahaarranger.song

/** Where the Song plays. Default INTERNAL (the external module may share channels with RIGHT/LEFT and the Style). */
enum class SongRouting(val label: String) { INTERNAL("INTERNAL"), EXTERNAL("MIDI OUT"), BOTH("BOTH") }

/** Internal BASSMIDI Song stream (a separate stream from the keyboard/Style one). */
class InternalSongOutput(private val sink: (ByteArray) -> Boolean) : SongOutput {
    override val id = "INTERNAL"
    override fun send(bytes: ByteArray) { sink(bytes) }
}

/** External module over USB MIDI. The sink refuses SysEx/system bytes; the Song never produces them anyway. */
class ExternalSongOutput(private val sink: (ByteArray) -> Boolean) : SongOutput {
    override val id = "MIDI OUT"
    override fun send(bytes: ByteArray) { sink(bytes) }
}

/** The outputs for a routing choice. Each event goes once to each of them (see SongPlayer). */
fun songOutputsFor(routing: SongRouting, internal: SongOutput, external: SongOutput): List<SongOutput> = when (routing) {
    SongRouting.INTERNAL -> listOf(internal)
    SongRouting.EXTERNAL -> listOf(external)
    SongRouting.BOTH -> listOf(internal, external)
}

/** General MIDI program names (display only). */
object GmNames {
    private val names = listOf(
        "Acoustic Grand Piano", "Bright Acoustic Piano", "Electric Grand Piano", "Honky-tonk Piano", "Electric Piano 1", "Electric Piano 2", "Harpsichord", "Clavi",
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone", "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
        "Drawbar Organ", "Percussive Organ", "Rock Organ", "Church Organ", "Reed Organ", "Accordion", "Harmonica", "Tango Accordion",
        "Nylon Guitar", "Steel Guitar", "Jazz Guitar", "Clean Guitar", "Muted Guitar", "Overdriven Guitar", "Distortion Guitar", "Guitar Harmonics",
        "Acoustic Bass", "Finger Bass", "Pick Bass", "Fretless Bass", "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        "Violin", "Viola", "Cello", "Contrabass", "Tremolo Strings", "Pizzicato Strings", "Orchestral Harp", "Timpani",
        "String Ensemble 1", "String Ensemble 2", "Synth Strings 1", "Synth Strings 2", "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet", "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax", "Oboe", "English Horn", "Bassoon", "Clarinet",
        "Piccolo", "Flute", "Recorder", "Pan Flute", "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
        "Square Lead", "Saw Lead", "Calliope Lead", "Chiff Lead", "Charang Lead", "Voice Lead", "Fifths Lead", "Bass + Lead",
        "New Age Pad", "Warm Pad", "Polysynth Pad", "Choir Pad", "Bowed Pad", "Metallic Pad", "Halo Pad", "Sweep Pad",
        "Rain", "Soundtrack", "Crystal", "Atmosphere", "Brightness", "Goblins", "Echoes", "Sci-Fi",
        "Sitar", "Banjo", "Shamisen", "Koto", "Kalimba", "Bagpipe", "Fiddle", "Shanai",
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock", "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cymbal",
        "Guitar Fret Noise", "Breath Noise", "Seashore", "Bird Tweet", "Telephone Ring", "Helicopter", "Applause", "Gunshot")
    /** Display name for [program] (0-based) on [channel] (0-based); channel 10 and bank MSB 127/126 are drum kits. */
    fun name(channel: Int, program: Int, bankMsb: Int = -1): String = when {
        channel == 9 || bankMsb == 127 || bankMsb == 126 -> "Drums" + if (program >= 0) " (kit ${program + 1})" else ""
        program in 0..127 -> names[program]
        else -> "—"
    }
}
