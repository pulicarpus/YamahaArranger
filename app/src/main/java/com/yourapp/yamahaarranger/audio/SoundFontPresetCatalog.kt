package com.yourapp.yamahaarranger.audio

/** Only the current identity-bearing native protocol is selectable. */
object SoundFontPresetCatalog {
    fun parse(raw: String): List<AudioEngineManager.SfPreset> = raw.split('\u001e').mapNotNull { record ->
        val fields = record.split('\u001f', limit = 6)
        if (fields.size != 6) return@mapNotNull null
        val id = fields[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val bank = fields[3].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
        val pc = fields[4].toIntOrNull()?.takeIf { it in 0..65535 } ?: return@mapNotNull null
        if (fields[0].isBlank() || fields[2].isBlank()) return@mapNotNull null
        AudioEngineManager.SfPreset(fields[0], bank, pc, fields[5], id, fields[2])
    }.distinctBy { it.identity }

    fun filter(presets: List<AudioEngineManager.SfPreset>, search: String, fontId: Long? = null):
        List<AudioEngineManager.SfPreset> = presets.filter {
        (fontId == null || it.soundFontId == fontId) && it.name.contains(search.trim(), ignoreCase = true)
    }
}
