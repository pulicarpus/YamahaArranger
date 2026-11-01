package com.yourapp.yamahaarranger.style

/**
 * PSR-E343 compatibility CLASSIFICATION of a Style part's MIDI OUT voice request. Read-only and evidence-based: the app ships NO built-in E343 voice list.
 * It compares the request (Bank MSB / LSB / Program, program 0-based as sent) with the Voice/Drum list the USER supplied from the Yamaha PSR-E343 Data List
 * (E343_VOICES.tsv, program entered 1-based there and stored 0-based here). Nothing is invented, nothing is ever applied automatically: a candidate is only a suggestion
 * for the manual override.
 *  EXACT              the supplied list has exactly this MSB/LSB/Program.
 *  COMPATIBLE         other numbers, same voice name (case-insensitive).
 *  FALLBACK_CANDIDATE same MSB and Program with another LSB (a variation of the same slot); a human must confirm it.
 *  UNSUPPORTED        a list was supplied and nothing in it matches this request.
 *  UNKNOWN            no list supplied, or the Style states no complete request: no claim is made.
 */
object E343Compat {
    enum class Status { EXACT, COMPATIBLE, FALLBACK_CANDIDATE, UNSUPPORTED, UNKNOWN }
    class Result(val status: Status, val reason: String, val candidate: ExternalVoice? = null)

    fun classify(msb: Int?, lsb: Int?, program0: Int?, name: String, catalog: List<ExternalVoice>): Result {
        if (msb == null || lsb == null || program0 == null) return Result(Status.UNKNOWN, "the Style does not state a complete Bank MSB / LSB / Program for this part")
        if (catalog.isEmpty()) return Result(Status.UNKNOWN, "no PSR-E343 Data List supplied (put E343_VOICES.tsv in YamahaArranger/); nothing is assumed")
        catalog.firstOrNull { it.msb == msb && it.lsb == lsb && it.program == program0 }?.let { return Result(Status.EXACT, "listed as '${it.label}'", it) }
        if (name.isNotBlank()) catalog.firstOrNull { it.label.equals(name.trim(), ignoreCase = true) }?.let {
            return Result(Status.COMPATIBLE, "same name '${it.label}' but listed as ${it.msb}:${it.lsb}:PC${it.program + 1}", it)
        }
        catalog.firstOrNull { it.msb == msb && it.program == program0 }?.let {
            return Result(Status.FALLBACK_CANDIDATE, "same Program in the list with LSB ${it.lsb} ('${it.label}'); confirm by ear before using", it)
        }
        return Result(Status.UNSUPPORTED, "not in the supplied PSR-E343 list (${catalog.size} entries)")
    }

    fun describe(r: Result) = "${r.status.name}: ${r.reason}"
}
