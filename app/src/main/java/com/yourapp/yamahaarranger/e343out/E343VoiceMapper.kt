package com.yourapp.yamahaarranger.e343out

/** Musical family of a Style part's voice, used only to choose a PSR-E343 voice for MIDI OUT. */
enum class E343Family(val label: String, val gmPrograms: IntRange, val extraPrograms: Set<Int> = emptySet()) {
    PIANO("Piano", 0..3, setOf(6)), EPIANO("E.Piano", 4..5, setOf(7)), CHROMATIC("Chromatic perc.", 8..15), ORGAN("Organ", 16..20),
    ACCORDION("Accordion", 21..23), GUITAR("Guitar", 24..31), BASS("Bass", 32..39), STRINGS("Strings", 40..51, setOf(55)), CHOIR("Choir", 52..54),
    TRUMPET("Trumpet / Horn", 56..60), BRASS("Brass", 61..63), SAX("Saxophone", 64..67), REED("Reed", 68..71), FLUTE("Flute", 72..79),
    SYNTH_LEAD("Synth lead", 80..87), PAD("Pad", 88..95), SYNTH_FX("Synth FX", 96..103), WORLD("World", 104..111), PERCUSSIVE("Percussive", 112..119),
    SFX("Sound FX", 120..127), DRUM("Drum kit", IntRange.EMPTY);
    fun hasProgram(p: Int) = p in gmPrograms || p in extraPrograms
    companion object { fun ofProgram(p: Int) = entries.firstOrNull { it != DRUM && it.hasProgram(p) } }
}

enum class E343MapStatus(val label: String) {
    /** The Style's own Bank/Program is in the E343 list: sent unchanged. */
    ORIGINAL_COMPATIBLE("ORIGINAL (E343 list)"),
    /** Not in the list: an E343 voice of the same family and character is sent instead. */
    AUTO_MAPPED("AUTO E343"),
    /** Family unknown from name and program; the Style part role (Bass) decided the voice. */
    ROLE_FALLBACK("ROLE FALLBACK"),
    /** No safe E343 candidate: the Style's own request is sent unchanged and the part is reported (the E343 behaviour for it is undocumented). */
    NO_SAFE_CANDIDATE("NO SAFE CANDIDATE"),
    /** A manual EXTERNAL MIDI OUT voice saved in the Style profile is sent (highest priority). */
    MANUAL("MANUAL"),
}

/** What a Style part plays, for the checks that need more than Bank/Program: velocity histogram (Mega Voice zones) and drum notes (kit coverage). */
class E343PartUsage {
    val velocity = IntArray(128)
    val drumNotes = java.util.TreeMap<Int, Int>()
    val noteOns: Int get() = velocity.sum()
    fun median(): Int { val n = noteOns; if (n == 0) return -1; var acc = 0; for (v in 0..127) { acc += velocity[v]; if (acc * 2 >= n) return v }; return 127 }
}

data class E343VoiceDecision(
    val channel: Int, val msb: Int, val lsb: Int, val program: Int, val requestName: String, val drum: Boolean,
    val status: E343MapStatus, val target: E343Voice?, val family: E343Family?, val reason: String, val warnings: List<String> = emptyList()
) {
    val outMsb: Int get() = if (status == E343MapStatus.AUTO_MAPPED || status == E343MapStatus.ROLE_FALLBACK) target!!.msb else msb
    val outLsb: Int get() = if (status == E343MapStatus.AUTO_MAPPED || status == E343MapStatus.ROLE_FALLBACK) target!!.lsb else lsb
    val outProgram: Int get() = if (status == E343MapStatus.AUTO_MAPPED || status == E343MapStatus.ROLE_FALLBACK) target!!.program else program
    val requestText: String get() = "$msb/$lsb/${program + 1}" + (if (requestName.isNotBlank()) " '$requestName'" else "")
    val outText: String get() = target?.takeIf { it.matches(outMsb, outLsb, outProgram) }?.text ?: "$outMsb/$outLsb/${outProgram + 1} (not in E343 list)"
}

/**
 * Chooses the PSR-E343 voice for one Style part request. Pure and deterministic.
 * Order: request already in the E343 list -> kept. Otherwise the family is taken from the CASM voice name, else from the GM program number (not for
 * Bank MSB 8 = Yamaha Mega Voice, whose program numbers are not GM families), else from the part role (Bass part only). Inside the family the voice is
 * chosen by character keywords of the name, then by the same GM program in the E343 list, then by shared name words, then by the family default.
 * Drums: the kit is chosen by name keywords / XG kit program, then its note table is audited against the notes the Style plays.
 */
class E343VoiceMapper(private val catalog: E343Catalog) {
    private data class Rule(val pattern: Regex, val target: String)
    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    // Family from the CASM voice name: ordered, first match wins (specific words before general ones).
    private val familyRules: List<Pair<Regex, E343Family>> = listOf(
        rx("steel ?drum|vib(e|ra)|marimba|xylo|celest|glock|music ?box|tubular|kalimba|bells?\\b(?!.*pad)") to E343Family.CHROMATIC,
        rx("e\\.? ?p(iano)?\\b|epiano|e-piano|el\\.? ?piano|elec.*piano|rhodes|tines?\\b|wurli|suitcase|dx ?ep|galaxy|stage ?ep|clav") to E343Family.EPIANO,
        rx("piano|pno|\\bpf\\b|grand|honky|harpsi|cp ?80") to E343Family.PIANO,
        rx("organ|\\borg\\b|drawbar|\\bb3\\b|rotor|harmonium") to E343Family.ORGAN,
        rx("accord|bandone|harmonica|musette") to E343Family.ACCORDION,
        rx("oboe|clarinet|bassoon|english ?horn") to E343Family.REED,
        rx("guit|gtr|\\bgt\\b|nylon|strum|12 ?str|\\ba\\.? ?g\\b|\\be\\.? ?g\\b|ac\\.? ?g|steel ?g|jazz ?g|mute ?g|dist|overdr|cutting|banjo|ukulele|mandolin") to E343Family.GUITAR,
        rx("string|\\bstrs?\\b|violin|viola|cello|fiddle|pizz|\\bharp\\b|orch|contrabass|\\btrem") to E343Family.STRINGS,
        rx("bass|\\bbs\\b|fretless|slap") to E343Family.BASS,
        rx("choir|\\bvox\\b|vocal|voices?\\b|\\baah|\\booh|\\bdoo|scat") to E343Family.CHOIR,
        rx("pad\\b|warm|atmos|heaven|sweep|halo") to E343Family.PAD,
        rx("trumpet|\\btp\\b|\\btpt|flugel|cornet|trombone|\\btb\\b|\\btrb|tuba|french ?horn") to E343Family.TRUMPET,
        rx("brass|horns?\\b|section") to E343Family.BRASS,
        rx("sax|\\balto\\b|tenor|baritone") to E343Family.SAX,
        rx("flute|piccolo|recorder|whistle|ocarina|shakuhachi") to E343Family.FLUTE,
        rx("sitar|shamisen|koto|kanoon|\\boud\\b|bouzouki|santur|er ?hu|bagpipe|shanai|tanpura|pi ?pa|gu ?zheng") to E343Family.WORLD,
        rx("lead|\\bsaw|square|synth|\\bsyn\\b|poly") to E343Family.SYNTH_LEAD,
    )

    // Character inside a family: name keywords -> E343 voice name (every target is checked against the list by E343AutoMapTest).
    private val characterRules: Map<E343Family, List<Rule>> = mapOf(
        E343Family.BASS to listOf(Rule(rx("fretless"), "Fretless Bass"), Rule(rx("slap"), "Slap Bass"), Rule(rx("pick"), "Pick Bass"),
            Rule(rx("acou|upright|wood|contra|jazz ?b|\\ba\\.? ?bass|ac\\.? ?bass"), "Acoustic Bass"), Rule(rx("funk"), "Funk Bass"),
            Rule(rx("synth|\\bsyn|hi-?q|dance|moog|analog"), "Synth Bass"), Rule(rx("finger|electric|\\be\\.? ?bass|el\\.? ?bass"), "Finger Bass")),
        E343Family.GUITAR to listOf(Rule(rx("nylon|classic|spanish|flamenco"), "Classical Guitar"), Rule(rx("12 ?str"), "12Strings Guitar"),
            Rule(rx("jazz"), "Jazz Guitar"), Rule(rx("mute"), "Muted Guitar"), Rule(rx("overdr|\\bod\\b|crunch"), "Overdriven Guitar"),
            Rule(rx("dist|heavy|power|rock"), "Distortion Guitar"), Rule(rx("60s|vintage"), "60s Clean Guitar"),
            Rule(rx("clean|cutting|funk|\\be\\.? ?g|el\\.? ?g|elec|strat|tele"), "Clean Guitar"),
            Rule(rx("steel|folk|acou|\\ba\\.? ?g|ac\\.? ?g|strum|western"), "Folk Guitar"), Rule(rx("banjo"), "Banjo")),
        E343Family.PIANO to listOf(Rule(rx("bright"), "Bright Piano"), Rule(rx("honky"), "Honky-tonk Piano"), Rule(rx("harpsi"), "Harpsichord"),
            Rule(rx("cp ?80"), "CP 80")),
        E343Family.EPIANO to listOf(Rule(rx("clav"), "Clavi"), Rule(rx("dx|\\bfm\\b|modern"), "DX Modern Electric Piano"),
            Rule(rx("galaxy|cool"), "Cool! Galaxy Electric Piano"), Rule(rx("tine|rhodes|suitcase|stage"), "Hyper Tines")),
        E343Family.ORGAN to listOf(Rule(rx("jazz|drawbar|\\bb3\\b|tonewheel"), "Jazz Organ 1"), Rule(rx("rock|purple|rotor|leslie"), "Rock Organ"),
            Rule(rx("click|perc"), "Click Organ"), Rule(rx("church|pipe|chapel"), "Pipe Organ"), Rule(rx("reed|harmonium"), "Reed Organ"),
            Rule(rx("theat"), "Theater Organ")),
        E343Family.STRINGS to listOf(Rule(rx("pizz"), "Pizzicato Strings"), Rule(rx("trem"), "Tremolo Strings"), Rule(rx("slow"), "Slow Strings"),
            Rule(rx("chamber|small|quartet"), "Chamber Strings"), Rule(rx("marcato|stacc|spicc|short"), "Marcato Strings"),
            Rule(rx("synth|\\bsyn|analog"), "Synth Strings"), Rule(rx("violin|fiddle"), "Violin"), Rule(rx("cello"), "Cello"),
            Rule(rx("contrabass"), "Contrabass"), Rule(rx("\\bharp\\b"), "Harp"), Rule(rx("hit"), "Orchestra Hit")),
        E343Family.PAD to listOf(Rule(rx("warm"), "Warm Pad"), Rule(rx("choir"), "Choir Pad"), Rule(rx("bowed"), "Bowed Pad"), Rule(rx("halo"), "Halo Pad"),
            Rule(rx("sweep"), "Sweep Pad"), Rule(rx("new ?age|fantas"), "New Age Pad"), Rule(rx("poly"), "Poly Synth Pad"), Rule(rx("metal"), "Metallic Pad"),
            Rule(rx("xenon"), "Xenon Pad")),
        E343Family.CHOIR to listOf(Rule(rx("vox|humana"), "Vox Humana"), Rule(rx("air"), "Air Choir"), Rule(rx("vocal|ensemble"), "Vocal Ensemble")),
        E343Family.TRUMPET to listOf(Rule(rx("mute"), "Muted Trumpet"), Rule(rx("trombone|\\btb\\b|\\btrb"), "Trombone"), Rule(rx("tuba"), "Tuba"),
            Rule(rx("french|horn"), "French Horn")),
        E343Family.BRASS to listOf(Rule(rx("synth|\\bsyn"), "Synth Brass"), Rule(rx("big ?band"), "Big Band Brass"), Rule(rx("funk"), "Funky Brass"),
            Rule(rx("mellow|horns"), "Mellow Horns")),
        E343Family.SAX to listOf(Rule(rx("alto"), "Alto Sax"), Rule(rx("soprano"), "Soprano Sax"), Rule(rx("bari"), "Baritone Sax"),
            Rule(rx("breath"), "Breathy Tenor Sax"), Rule(rx("ensemble|section"), "Sax Ensemble")),
        E343Family.REED to listOf(Rule(rx("oboe"), "Oboe"), Rule(rx("english"), "English Horn"), Rule(rx("bassoon"), "Bassoon")),
        E343Family.FLUTE to listOf(Rule(rx("piccolo"), "Piccolo"), Rule(rx("pan"), "Pan Flute"), Rule(rx("recorder"), "Recorder"), Rule(rx("ocarina"), "Ocarina")),
        E343Family.ACCORDION to listOf(Rule(rx("bandone"), "Bandoneon"), Rule(rx("harmonica"), "Harmonica"), Rule(rx("tradit"), "Traditional Accordion")),
        E343Family.CHROMATIC to listOf(Rule(rx("vib"), "Vibraphone"), Rule(rx("marimba"), "Marimba"), Rule(rx("xylo"), "Xylophone"),
            Rule(rx("celest"), "Celesta"), Rule(rx("music ?box"), "Music Box"), Rule(rx("tubular|bell"), "Tubular Bells"), Rule(rx("steel ?drum"), "Steel Drums")),
        E343Family.SYNTH_LEAD to listOf(Rule(rx("square"), "Square Lead"), Rule(rx("saw"), "Sawtooth Lead")),
    )

    /** Family default when neither keywords nor program choose: a plain, neutral voice of the family. */
    val familyDefault: Map<E343Family, String> = mapOf(
        E343Family.PIANO to "Grand Piano", E343Family.EPIANO to "Funky Electric Piano", E343Family.CHROMATIC to "Vibraphone", E343Family.ORGAN to "Jazz Organ 1",
        E343Family.ACCORDION to "Musette Accordion", E343Family.GUITAR to "Folk Guitar", E343Family.BASS to "Finger Bass", E343Family.STRINGS to "String Ensemble",
        E343Family.CHOIR to "Choir", E343Family.TRUMPET to "Trumpet", E343Family.BRASS to "Brass Section", E343Family.SAX to "Tenor Sax", E343Family.REED to "Clarinet",
        E343Family.FLUTE to "Flute", E343Family.SYNTH_LEAD to "Square Lead", E343Family.PAD to "Warm Pad", E343Family.WORLD to "Sitar",
    )
    val characterTargets: List<String> get() = characterRules.values.flatten().map { it.target } + familyDefault.values + kitRules.map { it.target }

    // Drum kit by name, then by the XG kit program number (MSB 127 programs are the XG kit slots), else Standard Kit 1.
    private val kitRules = listOf(Rule(rx("brush"), "Brush Kit"), Rule(rx("jazz"), "Jazz Kit"), Rule(rx("rock|power"), "Rock Kit"), Rule(rx("room"), "Room Kit"),
        Rule(rx("elec|techno|e\\.? ?kit"), "Electronic Kit"), Rule(rx("analog|808|909|hip ?hop|r&b"), "Analog Kit"), Rule(rx("dance|house|club"), "Dance Kit"),
        Rule(rx("symph|orch|classic"), "Symphony Kit"), Rule(rx("std ?2|standard ?2"), "Standard Kit 2"))

    fun familyOf(channel: Int, msb: Int, program: Int, name: String): Pair<E343Family, String>? {
        val n = name.trim()
        if (n.isNotEmpty()) familyRules.firstOrNull { it.first.containsMatchIn(n) }?.let { return it.second to "CASM voice name '$n'" }
        if (msb != 8) E343Family.ofProgram(program)?.let { return it to "GM program ${program + 1} (Bank MSB $msb)" }
        return null
    }

    fun map(channel: Int, msb: Int, lsb: Int, program: Int, name: String, drum: Boolean, usage: E343PartUsage? = null): E343VoiceDecision {
        if (drum) return mapKit(channel, msb, lsb, program, name, usage)
        catalog.find(msb, lsb, program)?.takeIf { !it.kit }?.let {
            return E343VoiceDecision(channel, msb, lsb, program, name, false, E343MapStatus.ORIGINAL_COMPATIBLE, it, E343Family.ofProgram(it.program), "in the E343 list as '${it.name}'")
        }
        val warnings = megaWarnings(msb, usage)
        val fam = familyOf(channel, msb, program, name)
        if (fam == null) {
            if (channel == 10) {
                val t = catalog.byName(familyDefault.getValue(E343Family.BASS))!!
                return E343VoiceDecision(channel, msb, lsb, program, name, false, E343MapStatus.ROLE_FALLBACK, t, E343Family.BASS,
                    "not in the E343 list; name '${name.ifBlank { "-" }}' and Bank MSB $msb program ${program + 1} give no family, the part is the Style Bass -> ${t.name}", warnings)
            }
            return E343VoiceDecision(channel, msb, lsb, program, name, false, E343MapStatus.NO_SAFE_CANDIDATE, null, null,
                "not in the E343 list and no family from name '${name.ifBlank { "-" }}' or program (Bank MSB $msb${if (msb == 8) " = Mega Voice, program is not a GM family" else ""}); the Style request is sent unchanged", warnings)
        }
        val (family, evidence) = fam
        val (target, how) = chooseInFamily(family, msb, program, name)
        return E343VoiceDecision(channel, msb, lsb, program, name, false, E343MapStatus.AUTO_MAPPED, target, family,
            "not in the E343 list; family ${family.label} from $evidence; $how", warnings)
    }

    private fun chooseInFamily(family: E343Family, msb: Int, program: Int, name: String): Pair<E343Voice, String> {
        characterRules[family]?.firstOrNull { it.pattern.containsMatchIn(name) }?.let { r ->
            catalog.byName(r.target)?.let { return it to "character from the name -> ${it.name}" }
        }
        if (msb != 8 && family.hasProgram(program)) {
            val same = catalog.voices.filter { !it.kit && it.msb == 0 && it.program == program }.minWithOrNull(compareBy({ !it.panel }, { it.lsb != 112 }, { it.lsb }))
            if (same != null) return same to "same GM program ${program + 1} in the E343 list -> ${same.name}"
        }
        val words = tokens(name)
        if (words.isNotEmpty()) {
            val best = catalog.voices.filter { !it.kit && family.hasProgram(it.program) }
                .map { it to tokens(it.name).count { w -> w in words } }.filter { it.second > 0 }
                .maxWithOrNull(compareBy<Pair<E343Voice, Int>>({ it.second }, { it.first.panel }, { -it.first.voiceNo }))
            if (best != null) return best.first to "shared name word with '${best.first.name}'"
        }
        val d = catalog.byName(familyDefault.getValue(family))!!
        return d to "family default -> ${d.name}"
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in GENERIC }.toSet()
    // Words that only name the family say nothing about the character (every bass is a "bass").
    private val GENERIC = setOf("voice", "the", "and", "kit", "sound", "bass", "guitar", "gtr", "piano", "strings", "string", "organ", "pad", "brass", "sax", "flute", "choir", "synth", "lead")

    /** Mega Voice (Bank MSB 8): Yamaha switches articulations by velocity zone; an E343 voice plays every zone as an ordinary note. */
    private fun megaWarnings(msb: Int, usage: E343PartUsage?): List<String> {
        if (msb != 8 || usage == null || usage.noteOns == 0) return emptyList()
        val out = ArrayList<String>()
        val high = (106..127).sumOf { usage.velocity[it] }
        if (high > 0) out += "Mega Voice: $high of ${usage.noteOns} notes at velocity 106-127 (Yamaha Mega articulation/noise zones); on the E343 voice they sound as ordinary loud notes — Bank/Program substitution alone does not reproduce them"
        val med = usage.median()
        if (med in 1..59) out += "Mega Voice: median velocity $med (Mega normal layer is played softly); the E343 voice follows velocity, so this part is quieter than on a Yamaha arranger. Velocity is not changed; Auto Balance only lowers the louder parts"
        return out
    }

    private fun mapKit(channel: Int, msb: Int, lsb: Int, program: Int, name: String, usage: E343PartUsage?): E343VoiceDecision {
        val exact = catalog.find(msb, lsb, program)?.takeIf { it.kit }
        val notes = usage?.drumNotes.orEmpty()
        if (exact != null) {
            val cov = coverage(exact, notes)
            return E343VoiceDecision(channel, msb, lsb, program, name, true, E343MapStatus.ORIGINAL_COMPATIBLE, exact, E343Family.DRUM,
                "E343 kit '${exact.name}'; ${cov.text}", cov.warnings)
        }
        val byName = kitRules.firstOrNull { it.pattern.containsMatchIn(name) }?.let { catalog.byName(it.target) }
        val byProgram = if (msb == 127) catalog.kits.firstOrNull { it.msb == 127 && it.program == program } else null
        val kit = byName ?: byProgram ?: catalog.byName("Standard Kit 1")!!
        val why = when (kit) { byName -> "kit name '$name'"; byProgram -> "same XG kit program ${program + 1}"; else -> "no kit hint in name '$name' or program ${program + 1}: Standard Kit 1" }
        val cov = coverage(kit, notes)
        if (notes.isNotEmpty() && cov.fraction < MIN_KIT_COVERAGE)
            return E343VoiceDecision(channel, msb, lsb, program, name, true, E343MapStatus.NO_SAFE_CANDIDATE, kit, E343Family.DRUM,
                "kit $msb/$lsb/${program + 1} is not an E343 kit; candidate '${kit.name}' ($why) covers only ${cov.text}; the Style request is sent unchanged", cov.warnings)
        return E343VoiceDecision(channel, msb, lsb, program, name, true, E343MapStatus.AUTO_MAPPED, kit, E343Family.DRUM,
            "kit $msb/$lsb/${program + 1} is not an E343 kit; $why -> ${kit.name}; note audit: ${cov.text}", cov.warnings)
    }

    class Coverage(val fraction: Double, val text: String, val warnings: List<String>)
    /** Note audit of a kit against the notes the Style plays (by NOTE ON count): which notes sound in the kit and which are silent there. */
    fun coverage(kit: E343Voice, notes: Map<Int, Int>): Coverage {
        if (notes.isEmpty()) return Coverage(1.0, "no drum notes measured", emptyList())
        val table = catalog.kitNotes(kit)
        val total = notes.values.sum()
        val missing = notes.filterKeys { it !in table }
        val covered = total - missing.values.sum()
        val f = covered.toDouble() / total
        val w = ArrayList<String>()
        if (missing.isNotEmpty()) w += "notes with no sound in '${kit.name}': " + missing.entries.joinToString(" ") { "${it.key}x${it.value}" }
        if (catalog.kitUnverified(kit)) w += "'${kit.name}' note table is only 'same as Standard Kit 1' by footnote: unverified until heard"
        return Coverage(f, "${notes.size} notes / $total NOTE ON, ${"%.0f".format(f * 100)} % sound in '${kit.name}'", w)
    }
    /** "16 Whip Slap" style list of what each Style drum note plays in a kit. */
    fun kitNoteNames(kit: E343Voice, notes: Collection<Int>): String { val t = catalog.kitNotes(kit); return notes.sorted().joinToString(", ") { "$it ${t[it] ?: "(silent)"}" } }

    companion object { const val MIN_KIT_COVERAGE = 0.9 }
}
