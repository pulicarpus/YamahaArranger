package com.yourapp.yamahaarranger.audio

enum class KeyboardPart(val channel: Int, val label: String) {
    RIGHT1(0, "RIGHT 1"), RIGHT2(1, "RIGHT 2"), RIGHT3(2, "RIGHT 3"), LEFT(3, "LEFT");

    companion object {
        fun fromChannel(channel: Int): KeyboardPart = entries.first { it.channel == channel }
    }
}

/** Category changes are filters; they must preserve the selected keyboard part. */
data class KeyboardVoiceBrowserState(
    val part: KeyboardPart = KeyboardPart.RIGHT1,
    val category: String? = null
) {
    fun selectPart(part: KeyboardPart) = copy(part = part, category = null)
    fun selectCategory(category: String?) = copy(category = category)
}
