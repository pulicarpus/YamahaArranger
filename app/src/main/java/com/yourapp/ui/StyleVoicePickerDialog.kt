package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.SoundFontRolePlanner

private val PickerAmber = Color(0xFFFFB347)
private val PickerDim = Color(0xFF9CA3AF)

/** Presets a style channel may be pinned to: Colombo/Tyros only, kits only on Rhythm 8/9. */
fun styleVoiceCandidates(channel: Int, presets: List<AudioEngineManager.SfPreset>): List<AudioEngineManager.SfPreset> {
    val drum = channel == 8 || channel == 9
    return presets.filter { p ->
        val kind = SoundFontRolePlanner.kind(p.soundFontName)
        when {
            kind != SoundFontRolePlanner.Kind.COLOMBO && kind != SoundFontRolePlanner.Kind.TYROS -> false
            // Mirrors the native R2-3 kit whitelist: Colombo 127/128, Tyros 128.
            drum -> p.bank == 128 || (p.bank == 127 && kind == SoundFontRolePlanner.Kind.COLOMBO)
            else -> p.bank != 127 && p.bank != 128
        }
    }.sortedWith(compareBy({ SoundFontRolePlanner.kind(it.soundFontName).ordinal }, { it.bank }, { it.program }))
}

/**
 * CPV manual instrument picker for one style channel. The choice changes only the
 * internal sound and is saved for this style; MIDI OUT keeps the style's own request.
 */
@Composable
fun StyleVoicePickerDialog(
    slot: VoiceSlot,
    presets: List<AudioEngineManager.SfPreset>,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (AudioEngineManager.SfPreset) -> Unit,
    onReset: () -> Unit
) {
    LaunchedEffect(presets.isEmpty()) { if (presets.isEmpty()) onRefresh() }
    var search by remember { mutableStateOf("") }
    var font by remember { mutableStateOf(SoundFontRolePlanner.Kind.COLOMBO) }
    val candidates = remember(presets, slot.channel) { styleVoiceCandidates(slot.channel, presets) }
    val shown = candidates.filter {
        SoundFontRolePlanner.kind(it.soundFontName) == font &&
            (search.isBlank() || it.name.contains(search, true) || "${it.bank}:${it.program}".contains(search.trim()))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CH ${slot.channel} • ${slot.label} • SELECT VOICE") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                Text("Style: ${slot.displayName()}", color = PickerDim, fontSize = 10.sp)
                slot.overridePin?.let { pin ->
                    Text("Override: ${pin.soundFontName} ${pin.bank}:${pin.program} '${pin.presetName}' • ${slot.overrideStatus}",
                        color = if (slot.overrideStatus == "ACTIVE") Color(0xFF58C77A) else PickerAmber, fontSize = 10.sp)
                }
                Text("Internal sound only; saved for this style. E343 receives no Bank/PC from this choice.",
                    color = PickerDim, fontSize = 9.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (kind in listOf(SoundFontRolePlanner.Kind.COLOMBO, SoundFontRolePlanner.Kind.TYROS)) {
                        OutlinedButton(onClick = { font = kind }) {
                            Text((if (font == kind) "✓ " else "") + if (kind == SoundFontRolePlanner.Kind.COLOMBO) "COLOMBO" else "TYROS 4", fontSize = 11.sp)
                        }
                    }
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search name or bank:pc…") })
                Spacer(Modifier.height(4.dp))
                when {
                    presets.isEmpty() -> Text("Loading presets…", color = PickerDim, fontSize = 11.sp)
                    shown.isEmpty() -> Text("No ${if (slot.channel == 8 || slot.channel == 9) "kit" else "voice"} in this SF2 matches.",
                        color = PickerDim, fontSize = 11.sp)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(shown, key = { it.identity }) { p ->
                            val current = slot.overridePin?.let { it.soundFontName == p.soundFontName && it.bank == p.bank && it.program == p.program } == true
                            Row(Modifier.fillMaxWidth().clickable { onSelect(p) }.padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${p.bank}:${p.program}", color = PickerDim, fontSize = 10.sp, modifier = Modifier.width(64.dp))
                                Text(p.name.ifBlank { "(unnamed)" }, fontSize = 12.sp,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (current) PickerAmber else Color.Unspecified)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
        dismissButton = { TextButton(enabled = slot.hasOverride, onClick = onReset) { Text("RESET KE STYLE") } }
    )
}
