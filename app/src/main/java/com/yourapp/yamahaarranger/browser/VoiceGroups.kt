package com.yourapp.yamahaarranger.browser

/**
 * Instrument groups for the Voice Browser. Display only: they never feed the automatic voice resolver.
 *
 * A preset is classified from what its own metadata proves:
 *  1. drum banks (127/128) are Percussion;
 *  2. otherwise its own preset NAME decides when it names exactly one group;
 *  3. otherwise, in GM bank 0 the GM program family decides (also used to settle a name that hints at several groups);
 *     the same holds for variation banks 1-63 of a SoundFont whose bank 0 is a complete GM set (GS/XG variation tones keep the
 *     program's family); SoundFonts without a complete bank 0 (e.g. SX Prime) get no such inference;
 *  4. anything else (expansion/variation banks without a telling name, or conflicting hints) is Other / Unclassified,
 *     never guessed and never hidden.
 */
enum class VoiceGroup(val id: String, val label: String) {
    PIANO("PIANO", "Piano"), ELECTRIC_PIANO("EPIANO", "Electric Piano"), ORGAN("ORGAN", "Organ"), GUITAR("GUITAR", "Guitar"),
    BASS("BASS", "Bass"), STRINGS("STRINGS", "Strings"), BRASS("BRASS", "Brass"), SAX_WOODWIND("SAXWW", "Sax / Woodwind"),
    FLUTE_ETHNIC("FLUTE", "Flute / Ethnic Wind"), SYNTH_LEAD("LEAD", "Synth Lead"), SYNTH_PAD("PAD", "Synth Pad"), CHOIR("CHOIR", "Choir"),
    PERCUSSION("PERC", "Percussion / Other"), UNCLASSIFIED("OTHER", "Other / Unclassified");

    companion object { fun fromId(id: String?): VoiceGroup? = entries.firstOrNull { it.id == id } }
}

enum class GroupBasis { DRUM_BANK, NAME, PROGRAM, PROGRAM_VARIATION, UNCLASSIFIED }
data class VoiceClass(val group: VoiceGroup, val basis: GroupBasis)

object VoiceGroups {
    private class Rule(val group: VoiceGroup, val words: List<String>, val tokens: List<String> = emptyList())

    // Substring words are matched inside the lower-cased name; token words must equal a whole alphanumeric token.
    private val rules = listOf(
        Rule(VoiceGroup.ELECTRIC_PIANO, listOf("electric piano", "e.piano", "e piano", "epiano", "eleckpiano", "elec piano", "rhodes", "wurli", "dx7"), listOf("ep")),
        Rule(VoiceGroup.PIANO, listOf("piano", "grand", "honky")),
        Rule(VoiceGroup.ORGAN, listOf("organ", "accordion", "accordian", "b3 org"), listOf("org")),
        Rule(VoiceGroup.GUITAR, listOf("guitar", "gtr")),
        Rule(VoiceGroup.BASS, listOf("bass"), emptyList()),
        Rule(VoiceGroup.STRINGS, listOf("string", "strg", "violin", "viola", "cello", "biola", "pizz")),
        Rule(VoiceGroup.BRASS, listOf("brass", "trumpet", "trombone", "tuba", "flugel", "french horn"), listOf("horn")),
        Rule(VoiceGroup.SAX_WOODWIND, listOf("sax", "oboe", "clarinet", "bassoon", "woodwind", "english horn")),
        Rule(VoiceGroup.FLUTE_ETHNIC, listOf("flute", "suling", "shakuhachi", "piccolo", "recorder", "ocarina", "whistle", "sarune", "bagpipe", "shanai", "shehnai", "bamboo")),
        Rule(VoiceGroup.SYNTH_LEAD, listOf("calliope", "charang"), listOf("lead")),
        Rule(VoiceGroup.SYNTH_PAD, emptyList(), listOf("pad")),
        Rule(VoiceGroup.CHOIR, listOf("choir", "aahs", "oohs", "synth voice")),
        Rule(VoiceGroup.PERCUSSION, listOf("drum", "kendang", "gendang", "tabla", "timpani", "taiko", "conga", "bongo", "perc", "kempul", "vibraphone", "marimba", "xylophone", "glockenspiel"), listOf("kit", "tom", "toms"))
    )

    private fun tokens(name: String) = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map { it.trimEnd { c -> c.isDigit() }.ifEmpty { it } }.toSet()

    private fun nameGroups(name: String): Set<VoiceGroup> {
        val lower = name.lowercase(); val toks = tokens(name)
        val hits = LinkedHashSet<VoiceGroup>()
        for (r in rules) {
            var hit = r.words.any { w ->
                if (w == "bass") Regex("bass(?!oon|\\s*drum)").containsMatchIn(lower) else lower.contains(w)
            } || r.tokens.any { it in toks }
            if (hit) hits += r.group
        }
        // "piano" inside "electric piano" is the more specific Electric Piano, not both.
        if (VoiceGroup.ELECTRIC_PIANO in hits) hits -= VoiceGroup.PIANO
        // a plain "bass drum"/"tom" kit name is percussion, not bass/etc. handled by the bass lookahead above.
        return hits
    }

    /** General MIDI melodic family of a bank-0 program (0-based); null where GM names no family for these groups. */
    fun gmProgramGroup(program: Int): VoiceGroup? = when (program) {
        in 0..3 -> VoiceGroup.PIANO
        4, 5 -> VoiceGroup.ELECTRIC_PIANO
        in 8..15 -> VoiceGroup.PERCUSSION
        in 16..23 -> VoiceGroup.ORGAN
        in 24..31 -> VoiceGroup.GUITAR
        in 32..39 -> VoiceGroup.BASS
        in 40..51 -> VoiceGroup.STRINGS
        in 52..54 -> VoiceGroup.CHOIR
        in 56..63 -> VoiceGroup.BRASS
        in 64..71 -> VoiceGroup.SAX_WOODWIND
        in 72..79 -> VoiceGroup.FLUTE_ETHNIC
        in 80..87 -> VoiceGroup.SYNTH_LEAD
        in 88..95 -> VoiceGroup.SYNTH_PAD
        in 112..119 -> VoiceGroup.PERCUSSION
        else -> null
    }

    /** A SoundFont is "GM-complete" when its bank 0 holds (nearly) all 128 programs. */
    fun isGmComplete(bank0Programs: Int) = bank0Programs >= 120

    fun classify(bank: Int, program: Int, name: String, gmCompleteSoundFont: Boolean = false): VoiceClass {
        if (bank == 127 || bank == 128) return VoiceClass(VoiceGroup.PERCUSSION, GroupBasis.DRUM_BANK)
        val byName = nameGroups(name)
        val variation = gmCompleteSoundFont && bank in 1..63
        val byProgram = if (bank == 0 || variation) gmProgramGroup(program) else null
        val basis = if (variation) GroupBasis.PROGRAM_VARIATION else GroupBasis.PROGRAM
        return when {
            byName.size == 1 -> VoiceClass(byName.first(), GroupBasis.NAME)
            byName.size > 1 && byProgram != null && byProgram in byName -> VoiceClass(byProgram, basis)
            byName.isEmpty() && byProgram != null -> VoiceClass(byProgram, basis)
            else -> VoiceClass(VoiceGroup.UNCLASSIFIED, GroupBasis.UNCLASSIFIED)
        }
    }

    /** Short source tag for a cell: enough to tell Colombo / Tyros / SX Prime apart at a glance. */
    fun sourceTag(soundFontFile: String): String {
        val n = soundFontFile.lowercase()
        return when {
            n.contains("colombo") -> "COLOMBO"
            n.contains("tyros") -> "TYROS 4"
            (n.contains("melodi") || n.contains("melody")) && (n.contains("prime") || n.contains("psr") || n.contains("yamaha")) -> "SX PRIME"
            (n.contains("drum")) && (n.contains("prime") || n.contains("psr") || n.contains("yamaha")) -> "SX DRUM"
            else -> soundFontFile.substringBeforeLast('.').take(10).uppercase()
        }
    }
}
