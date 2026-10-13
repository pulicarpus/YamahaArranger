package com.yourapp.yamahaarranger.ui

/** UI geometry only. Never used for MIDI channel or controller routing. */
internal fun compactMixerStripWidth(landscapeWidthDp: Float): Float =
    (landscapeWidthDp / 18f).coerceIn(48f, 56f)

internal fun compactMixerLabel(label: String): String = when (label.substringAfter("(", label).substringBefore(")").uppercase().replace(" ", "")) {
    "RIGHT1" -> "RIGHT1"
    "RIGHT2" -> "RIGHT2"
    "RIGHT3" -> "RIGHT3"
    "RHYTHM1" -> "RHY1"
    "RHYTHM2" -> "RHY2"
    "CHORD1" -> "CHD1"
    "CHORD2" -> "CHD2"
    "PHRASE1" -> "PHR1"
    "PHRASE2" -> "PHR2"
    "MASTER" -> "MASTER"
    "LEFT" -> "LEFT"
    "BASS" -> "BASS"
    "PAD" -> "PAD"
    "STYLE" -> if (label.startsWith("Ch")) label.substringBefore(" ").uppercase() else "STYLE"
    else -> label
}
