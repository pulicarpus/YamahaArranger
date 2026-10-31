package com.yourapp.yamahaarranger.style

/**
 * Leaving the Style Editor (the ← BACK button, the Android Back gesture/button and the old CLOSE button all use the same rule).
 * Nothing here touches MIDI OUT, octave corrections, BASSMIDI, CASM or playback: it only decides whether to ask first and when the page may close.
 */
object StyleEditorNavigation {
    enum class Leave { CLOSE_NOW, ASK }

    /** Unsaved profile changes → ask Save / Discard / Cancel; otherwise leave at once. */
    fun onBack(dirty: Boolean): Leave = if (dirty) Leave.ASK else Leave.CLOSE_NOW

    /** After SAVE PROFILE from the confirmation: close only when the profile was really written (or removed because it became empty); stay and show the reason otherwise. */
    fun closeAfterSave(message: String): Boolean = !(message.startsWith("Not saved") || message.startsWith("No Style"))
}
