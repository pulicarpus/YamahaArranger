package com.yourapp.yamahaarranger.e343out

/**
 * PSR-E343 voice and drum-kit list as printed in the official Yamaha Owner's Manual (docs/yamaha/E343_VOICES.tsv and E343_DRUMKITS.tsv,
 * bundled unchanged as assets/e343/). MIDI OUT only: nothing here touches the internal synthesizer.
 *
 * Numbering (audited): the TSV `program_1_128` column is the 1-based number printed in the manual; [E343Voice.program] is 0-based, exactly the
 * Program Change byte written to the cable (display = program + 1). The 27 DUAL panel voices have no Bank/Program and are commented out in the TSV.
 * Drum kits are rows of kind DRUMKIT (Bank MSB 127: 10 kits, MSB 126: 8 kits). A kit's note table lists every MIDI note that sounds in that kit;
 * a note that is not listed has no sound in that kit (manual footnote). Kits whose cells are only "same as Standard Kit 1" by footnote are marked unverified.
 */
data class E343Voice(val name: String, val msb: Int, val lsb: Int, val program: Int, val voiceNo: Int, val list: String, val category: String, val kit: Boolean) {
    val panel: Boolean get() = list == "PANEL"
    fun matches(msb: Int, lsb: Int, program: Int) = this.msb == msb && this.lsb == lsb && this.program == program
    /** "Finger Bass (0/112/34)" with the 1-based program as shown on the instrument. */
    val text: String get() = "$name ($msb/$lsb/${program + 1})"
}

class E343Catalog(val voices: List<E343Voice>, private val kitNotes: Map<Long, Map<Int, String>>, private val unverifiedKits: Set<Long>) {
    private val byTriple = voices.associateBy { key(it.msb, it.lsb, it.program) }
    val kits: List<E343Voice> get() = voices.filter { it.kit }

    fun find(msb: Int, lsb: Int, program: Int): E343Voice? = byTriple[key(msb, lsb, program)]
    fun contains(msb: Int, lsb: Int, program: Int) = find(msb, lsb, program) != null
    /** PANEL voice of that name first (higher-quality panel voices share names with XGlite ones), then XGlite. */
    fun byName(name: String): E343Voice? = voices.filter { it.name.equals(name, ignoreCase = true) }.minWithOrNull(compareBy({ !it.panel }, { it.voiceNo }))
    /** MIDI note -> sound name of a kit; empty when the triple is not an E343 kit. */
    fun kitNotes(kit: E343Voice): Map<Int, String> = kitNotes[key(kit.msb, kit.lsb, kit.program)].orEmpty()
    fun kitUnverified(kit: E343Voice) = key(kit.msb, kit.lsb, kit.program) in unverifiedKits

    companion object {
        private fun key(msb: Int, lsb: Int, program: Int) = (msb.toLong() shl 16) or (lsb.toLong() shl 8) or program.toLong()

        fun parse(voicesTsv: String, kitsTsv: String): E343Catalog {
            val voices = ArrayList<E343Voice>()
            for (line in voicesTsv.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 8) continue
                val msb = c[1].trim().toIntOrNull() ?: continue
                val lsb = c[2].trim().toIntOrNull() ?: continue
                val p1 = c[3].trim().toIntOrNull() ?: continue
                if (msb !in 0..127 || lsb !in 0..127 || p1 !in 1..128) continue
                voices += E343Voice(c[0].trim(), msb, lsb, p1 - 1, c[4].trim().toIntOrNull() ?: 0, c[5].trim(), c[6].trim(), c[7].trim() == "DRUMKIT")
            }
            val notes = HashMap<Long, MutableMap<Int, String>>()
            val unverified = HashSet<Long>()
            for (line in kitsTsv.lineSequence()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val c = line.split('\t')
                if (c.size < 11) continue
                val msb = c[2].trim().toIntOrNull() ?: continue
                val lsb = c[3].trim().toIntOrNull() ?: continue
                val p1 = c[4].trim().toIntOrNull() ?: continue
                val note = c[5].trim().toIntOrNull() ?: continue
                if (p1 !in 1..128 || note !in 0..127) continue
                val k = key(msb, lsb, p1 - 1)
                notes.getOrPut(k) { java.util.TreeMap() }[note] = c[9].trim()
                if (c[10].trim() == "footnote_only") unverified += k
            }
            return E343Catalog(voices, notes, unverified)
        }
    }
}
