package com.yourapp.yamahaarranger.e343out

/** MIDI OUT voice/balance mode for an external PSR-E343. ORIGINAL is the default and sends exactly what the Style asks for. */
enum class E343OutMode(val label: String) {
    ORIGINAL("ORIGINAL"), AUTO("AUTO E343"), AUTO_BALANCE("AUTO E343 + BALANCE");
    val mapsVoices: Boolean get() = this != ORIGINAL
    val balances: Boolean get() = this == AUTO_BALANCE
}

/**
 * MIDI OUT only adapter between the Style engine and the MIDI OUT router (MidiMixerOutput). It never touches the internal synthesizer, the Style data,
 * CASM/NTT, note events or velocities. Style destination channels 8..15 only; keyboard parts (0..3), the Song player and every other path pass unchanged.
 *
 * Voice priority: manual EXTERNAL MIDI OUT voice saved in the Style profile > AUTO E343 mapping > the Style's own request when it is already in the E343 list.
 * Volume priority (AUTO E343 + BALANCE): manual MIDI OUT trim saved per Style > Mixer slider moved by the user (then no automatic trim on that part) > auto trim.
 * Only CC7 is scaled; CC10/11/91/93 and every note pass through. In ORIGINAL mode every call returns its input unchanged (same array instance).
 *
 * [sendProgram] and [sendControllers] re-send to the router when the mode or plan changes; the router itself skips values the module already holds.
 */
class E343OutAdapter(
    private val sendProgram: (channel: Int, program: Int, msb: Int, lsb: Int) -> Unit,
    private val sendControllers: (channel: Int, pairs: IntArray) -> Unit,
) {
    @Volatile var mode: E343OutMode = E343OutMode.ORIGINAL; private set
    private var mapper: E343VoiceMapper? = null
    private var plan: E343StylePlan = E343StylePlan.EMPTY
    private val manualVoices = HashMap<Int, Set<Long>>()
    private val manualTrim = IntArray(16) { -1 }
    /** Live manual EXTERNAL MIDI OUT voices of the working Style profile (channel -> Bank MSB/LSB/Program triples); supplied by the app. */
    @Volatile var manualVoiceLookup: ((Int) -> Set<Triple<Int, Int, Int>>)? = null
    /** Mixer slider value per channel (127 = untouched); supplied by the app. */
    @Volatile var mixerSlider: (Int) -> Int = { 127 }
    private val rawRequest = arrayOfNulls<IntArray>(16)      // program, msb, lsb as the Style asked
    private val lastDecision = arrayOfNulls<E343VoiceDecision>(16)
    private val rawCc7 = IntArray(16) { -1 }
    private val sentCc7 = IntArray(16) { -1 }

    @Synchronized fun setCatalog(catalog: E343Catalog?) { mapper = catalog?.let(::E343VoiceMapper) }
    @Synchronized fun currentMapper(): E343VoiceMapper? = mapper
    @Synchronized fun currentPlan(): E343StylePlan = plan

    /** New Style: plan, manual voices (channel -> Bank/Program triples of saved EXTERNAL overrides) and saved manual MIDI OUT trims (-1 = none). */
    @Synchronized fun setStyle(next: E343StylePlan, manual: Map<Int, Set<Triple<Int, Int, Int>>>, trims: Map<Int, Int>) {
        plan = next
        manualVoices.clear(); manual.forEach { (ch, s) -> manualVoices[ch] = s.map { key(it.first, it.second, it.third) }.toSet() }
        manualTrim.fill(-1); trims.forEach { (ch, v) -> if (ch in 8..15 && v in 0..127) manualTrim[ch] = v }
        lastDecision.fill(null); rawRequest.fill(null); rawCc7.fill(-1); sentCc7.fill(-1)
    }
    @Synchronized fun setManualVoices(manual: Map<Int, Set<Triple<Int, Int, Int>>>) {
        manualVoices.clear(); manual.forEach { (ch, s) -> manualVoices[ch] = s.map { key(it.first, it.second, it.third) }.toSet() }
    }

    @Synchronized fun setMode(next: E343OutMode) {
        if (next == mode) return
        mode = next
        resendAll()
    }
    /** Manual MIDI OUT trim of one part (0..127, null = automatic). Applied at once when BALANCE is on. */
    @Synchronized fun setManualTrim(channel: Int, value: Int?) {
        if (channel !in 8..15) return
        manualTrim[channel] = value?.coerceIn(0, 127) ?: -1
        resendCc7(channel)
    }
    @Synchronized fun manualTrims(): Map<Int, Int> = (8..15).filter { manualTrim[it] >= 0 }.associateWith { manualTrim[it] }

    /** Style Bank/Program request on its way to MIDI OUT; returns (program, msb, lsb) to write. */
    @Synchronized fun voice(channel: Int, program: Int, msb: Int, lsb: Int): IntArray {
        val raw = intArrayOf(program, msb, lsb)
        if (channel !in 8..15) return raw
        rawRequest[channel] = raw
        val d = decide(channel, program, msb, lsb)
        lastDecision[channel] = d
        return if (d == null) raw else intArrayOf(d.outProgram, d.outMsb, d.outLsb)
    }

    private fun decide(channel: Int, program: Int, msb: Int, lsb: Int): E343VoiceDecision? {
        if (!mode.mapsVoices) return null
        if (manualVoices[channel]?.contains(key(msb, lsb, program)) == true || manualVoiceLookup?.invoke(channel)?.contains(Triple(msb, lsb, program)) == true)
            return E343VoiceDecision(channel, msb, lsb, program, plan.names[channel].orEmpty(), msb >= 126, E343MapStatus.MANUAL, null, null,
                "manual EXTERNAL MIDI OUT voice saved in the Style profile")
        plan.decision(channel, msb, lsb, program)?.let { return it }
        val m = mapper ?: return null
        return m.map(channel, msb, lsb, program, plan.names[channel].orEmpty(), msb >= 126, plan.usage[channel])
    }

    /** Effective trim of one part now, with its source. */
    @Synchronized fun trimOf(channel: Int): Pair<Int, String> = trimLocked(channel)
    private fun trimLocked(channel: Int): Pair<Int, String> {
        if (channel !in 8..15 || !mode.balances) return 127 to "off"
        if (manualTrim[channel] >= 0) return manualTrim[channel] to "MANUAL (Style profile)"
        if (mixerSlider(channel) != 127) return 127 to "MIXER slider ${mixerSlider(channel)} (manual, no auto trim)"
        val row = plan.balance[channel] ?: return 127 to "no data"
        return row.autoTrim to "AUTO"
    }

    /** Mixer controllers on their way to MIDI OUT. Only CC7 of channels 8..15 is scaled, and only in AUTO E343 + BALANCE. */
    fun controllers(channel: Int, pairs: IntArray): IntArray = synchronized(this) {
        if (channel !in 8..15) return pairs
        var i = 0; var at = -1
        while (i + 1 < pairs.size) { if (pairs[i] == 7) at = i; i += 2 }
        if (at < 0) return pairs
        rawCc7[channel] = pairs[at + 1]
        val trim = trimLocked(channel).first
        if (trim == 127) { sentCc7[channel] = pairs[at + 1]; return pairs }
        val copy = pairs.copyOf()
        copy[at + 1] = scale(pairs[at + 1], trim)
        sentCc7[channel] = copy[at + 1]
        copy
    }

    private fun scale(v: Int, trim: Int) = (v.coerceIn(0, 127) * trim.coerceIn(0, 127) + 63) / 127

    private fun resendCc7(channel: Int) {
        val raw = rawCc7[channel]
        if (raw < 0) return
        val v = scale(raw, trimLocked(channel).first)
        sentCc7[channel] = v
        sendControllers(channel, intArrayOf(7, v))
    }
    private fun resendAll() {
        for (ch in 8..15) {
            rawRequest[ch]?.let { r ->
                val d = decide(ch, r[0], r[1], r[2]); lastDecision[ch] = d
                if (d == null) sendProgram(ch, r[0], r[1], r[2]) else sendProgram(ch, d.outProgram, d.outMsb, d.outLsb)
            }
            resendCc7(ch)
        }
    }

    /** One line per part for the UI and the report: what the Style asked, what is sent, why, and the CC7 written. */
    data class PartState(val channel: Int, val request: IntArray?, val decision: E343VoiceDecision?, val trim: Int, val trimSource: String,
                         val autoTrim: Int, val manualTrim: Int?, val rawCc7: Int, val sentCc7: Int)
    @Synchronized fun state(): List<PartState> = (8..15).map { ch ->
        val t = trimLocked(ch)
        PartState(ch, rawRequest[ch]?.copyOf(), lastDecision[ch], t.first, t.second, plan.balance[ch]?.autoTrim ?: 127,
            manualTrim[ch].takeIf { it >= 0 }, rawCc7[ch], sentCc7[ch])
    }

    private fun key(msb: Int, lsb: Int, program: Int) = (msb.toLong() shl 16) or (lsb.toLong() shl 8) or program.toLong()
}
