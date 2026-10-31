package com.yourapp.yamahaarranger.ui

/** Mixer helpers without any UI dependency (host-tested). */

/** 64 is the centre; values within 3 of it snap to it so the centre is easy to hit. */
fun snapPan(value: Int): Int = if (value in 61..67) 64 else value.coerceIn(0, 127)

fun panText(pan: Int): String = when { pan == 64 -> "PAN C"; pan < 64 -> "PAN L${64 - pan}"; else -> "PAN R${pan - 64}" }
