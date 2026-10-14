package com.yourapp.yamahaarranger.registration

/** Monotonic deadlines; arming never takes a snapshot or touches playback. */
data class RegistrationSaveState(val armedUntil: Long = 0, val overwriteSlot: Int? = null,
    val overwriteUntil: Long = 0) {
    fun arm(now: Long, heldMs: Long): RegistrationSaveState =
        if (heldMs >= 600) RegistrationSaveState(armedUntil = now + 5000) else this
    fun armed(now: Long) = armedUntil > now
    fun tap(now: Long, index: Int, occupied: Boolean): Pair<RegistrationSaveState, Boolean> {
        require(index in 0..7)
        if (!armed(now)) return RegistrationSaveState() to false
        if (occupied && !(overwriteSlot == index && now <= overwriteUntil))
            return copy(overwriteSlot = index, overwriteUntil = now + 2000) to false
        return RegistrationSaveState() to true
    }
}

/** MIDI-native input only: Data List Program 1–128 conversion is explained by the UI. */
fun externalVoiceInput(msb: String, lsb: String, program: String, label: String): ExternalVoice? {
    val m=msb.toIntOrNull() ?: return null
    val l=lsb.toIntOrNull() ?: return null
    val p=program.toIntOrNull() ?: return null
    if(m !in 0..127 || l !in 0..127 || p !in 0..127 || label.isBlank()) return null
    return ExternalVoice(m,l,p,label.trim().take(24))
}
