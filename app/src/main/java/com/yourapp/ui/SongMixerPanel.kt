package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourapp.yamahaarranger.song.GmNames
import com.yourapp.yamahaarranger.song.SongStatus
import com.yourapp.yamahaarranger.song.SongVoice
import com.yourapp.yamahaarranger.song.SustainMode
import kotlin.math.roundToInt

object SongMixerTags {
    const val TAB_CHANNELS = "song.tab.channels"; const val TAB_MIXER = "song.tab.mixer"; const val DETAIL = "song.mixer.detail"
    const val VOICE = "song.mixer.voice"; const val VOICE_MIDI = "song.mixer.voice.midi"; const val VOICE_TEXT = "song.mixer.voice.text"
    const val MUTE = "song.mixer.mute"; const val SOLO = "song.mixer.solo"; const val RESET = "song.mixer.reset"
    const val VOL = "song.mixer.vol"; const val VOL_MIDI = "song.mixer.vol.midi"; const val VOL_TEXT = "song.mixer.vol.text"
    const val PAN = "song.mixer.pan"; const val PAN_MIDI = "song.mixer.pan.midi"; const val PAN_TEXT = "song.mixer.pan.text"
    const val LIVE = "song.mixer.live"; const val PICKER = "song.picker"; const val PICKER_CANCEL = "song.picker.cancel"
    fun sustain(m: SustainMode) = "song.mixer.sustain.${m.name}"
    fun strip(ch: Int) = "song.mixer.ch.$ch"
    fun pickerItem(i: Int) = "song.picker.item.$i"
    fun pickerSf2(name: String) = "song.picker.sf2.$name"
}

/** Mixer callbacks (portable; the Android dialog wires them to the view model). Defaults do nothing. */
class SongMixerActions(
    val onVoice: (Int, SongVoice?) -> Unit = { _, _ -> }, val onVolume: (Int, Int?) -> Unit = { _, _ -> }, val onPan: (Int, Int?) -> Unit = { _, _ -> },
    val onSustain: (Int, SustainMode) -> Unit = { _, _ -> }, val onMute: (Int) -> Unit = {}, val onSolo: (Int) -> Unit = {}, val onReset: (Int) -> Unit = {},
)

private val MBg = Color(0xFF161C22); private val MKey = Color(0xFF26303A); private val MBlue = Color(0xFF3FA2FF); private val MDim = Color(0xFF8D9AA6)
private val MAmber = Color(0xFFFFC857); private val MRed = Color(0xFFB54747); private val MGreen = Color(0xFF1E8E3E)

@Composable
private fun MixKey(text: String, tag: String, modifier: Modifier = Modifier, on: Boolean = false, onColor: Color = Color(0xFF1F5E8C), enabled: Boolean = true,
                   minWidth: Int = 92, onClick: () -> Unit) {
    Box(modifier.testTag(tag).heightIn(min = 52.dp).widthIn(min = minWidth.dp)
        .background(if (!enabled) Color(0xFF1A1F25) else if (on) onColor else MKey, RoundedCornerShape(6.dp))
        .border(if (on) 2.dp else 1.dp, if (on) Color.White else Color(0xFF3A4652), RoundedCornerShape(6.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else Color(0xFF5D6873), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

private fun songPanText(v: Int) = when { v == 64 -> "C"; v < 64 -> "L${64 - v}"; else -> "R${v - 64}" }

/** The file's voice of [ch] as a short label. */
fun songFileVoiceLabel(s: SongStatus, ch: Int): String {
    val msb = s.bankMsb.getOrElse(ch) { -1 }; val lsb = s.bankLsb.getOrElse(ch) { -1 }; val pc = s.programs.getOrElse(ch) { -1 }
    val name = if (ch in s.drumChannels) "Drums" + if (pc >= 0) " (kit ${pc + 1})" else "" else GmNames.name(ch, pc, msb)
    return "$name  [${if (msb < 0) "-" else msb}/${if (lsb < 0) "-" else lsb}/${if (pc < 0) "-" else pc}]"
}

/**
 * SONG MIXER (V1A.2): 16 channel strips (tap = select) and the selected channel's controls.
 * Everything shown comes from [status] (the real player): the file's values and the user's overrides are shown separately.
 */
@Composable
fun SongMixerSection(status: SongStatus, nowNs: Long, selected: Int, onSelect: (Int) -> Unit, onOpenPicker: () -> Unit, actions: SongMixerActions,
                     liveReport: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (ch in row * 8 until row * 8 + 8) SongMixerStrip(status, ch, nowNs, ch == selected, Modifier.weight(1f)) { onSelect(ch) }
        }
        ChannelDetail(status, selected, onOpenPicker, actions, liveReport)
    }
}

@Composable
private fun SongMixerStrip(s: SongStatus, ch: Int, nowNs: Long, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val used = ch in s.channelsUsed; val o = s.overrides.getOrNull(ch); val silent = s.silent.getOrElse(ch) { false }
    val active = used && !silent && nowNs - s.lastNoteOnNs.getOrElse(ch) { 0L } in 0..150_000_000L
    Column(modifier.testTag(SongMixerTags.strip(ch)).heightIn(min = 66.dp)
        .background(if (sel) Color(0xFF1B3A57) else if (silent) Color(0xFF3A1B1B) else if (used) MBg else Color(0xFF101418), RoundedCornerShape(5.dp))
        .border(if (sel) 2.dp else 1.dp, if (sel) MBlue else Color(0xFF2E3740), RoundedCornerShape(5.dp))
        .clickable(onClick = onClick).padding(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CH${ch + 1}", color = if (used) Color.White else Color(0xFF4A545E), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(9.dp).background(if (active) Color(0xFF4CD37B) else Color(0xFF26303A), CircleShape))
        }
        Text(o?.voice?.name ?: if (used) songFileVoiceLabel(s, ch).substringBefore("  [") else "—", color = if (o?.voice != null) MAmber else MDim,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val flags = listOfNotNull(
            if (s.muted.getOrElse(ch) { false }) "M" else null, if (s.solo.getOrElse(ch) { false }) "S" else null,
            if (o != null && o.sustain != SustainMode.FOLLOW) "PED OFF" else null, if (o?.volume != null || o?.pan != null) "MIX" else null)
        Text(flags.joinToString(" "), color = Color(0xFFFF8A65), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ChannelDetail(s: SongStatus, ch: Int, onOpenPicker: () -> Unit, a: SongMixerActions, liveReport: String) {
    val o = s.overrides.getOrNull(ch); val drum = ch in s.drumChannels
    Column(Modifier.fillMaxWidth().testTag(SongMixerTags.DETAIL).background(MBg, RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CH ${ch + 1}" + if (drum) "  DRUM" else "", color = MBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.weight(1f)) {
                Text("MIDI: " + songFileVoiceLabel(s, ch), color = MDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("VOICE: " + (o?.voice?.label ?: "MIDI voice (from the file)") + (s.voiceBind.getOrNull(ch)?.let { " — ${it.label}" } ?: ""),
                    color = if (o?.voice != null) MAmber else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(SongMixerTags.VOICE_TEXT))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MixKey(if (drum) "CHANGE KIT" else "CHANGE VOICE", SongMixerTags.VOICE, minWidth = 150, onClick = onOpenPicker)
            MixKey("MIDI VOICE", SongMixerTags.VOICE_MIDI, enabled = o?.voice != null, minWidth = 120) { a.onVoice(ch, null) }
            MixKey("MUTE", SongMixerTags.MUTE, on = s.muted.getOrElse(ch) { false }, onColor = MRed) { a.onMute(ch) }
            MixKey("SOLO", SongMixerTags.SOLO, on = s.solo.getOrElse(ch) { false }, onColor = Color(0xFF8C6D1F)) { a.onSolo(ch) }
            MixKey("RESET CHANNEL", SongMixerTags.RESET, minWidth = 150) { a.onReset(ch) }
        }
        MixSlider("VOLUME", o?.volume, s.fileVolume.getOrElse(ch) { -1 }, 100, { it.toString() }, SongMixerTags.VOL, SongMixerTags.VOL_TEXT, SongMixerTags.VOL_MIDI,
            { a.onVolume(ch, it) })
        MixSlider("PAN", o?.pan, s.filePan.getOrElse(ch) { -1 }, 64, ::songPanText, SongMixerTags.PAN, SongMixerTags.PAN_TEXT, SongMixerTags.PAN_MIDI, { a.onPan(ch, it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SUSTAIN", color = MDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
            SustainMode.values().forEach { m -> MixKey(m.label, SongMixerTags.sustain(m), on = (o?.sustain ?: SustainMode.FOLLOW) == m, minWidth = 140) { a.onSustain(ch, m) } }
            val ped = s.filePedal.getOrElse(ch) { -1 }
            Text("file pedal: " + when { ped < 0 -> "not used"; ped >= 64 -> "ON"; else -> "OFF" }, color = MDim, fontSize = 11.sp)
        }
        val live = liveReport.lineSequence().firstOrNull { it.startsWith("SONGCH ch=${ch + 1} ") }
        Text(live?.removePrefix("SONGCH ")?.let { "SYNTH: $it" } ?: "", color = Color(0xFF6FA8DC), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(SongMixerTags.LIVE))
    }
}

@Composable
private fun MixSlider(label: String, user: Int?, file: Int, def: Int, fmt: (Int) -> String, tag: String, textTag: String, midiTag: String, onValue: (Int?) -> Unit) {
    var drag by remember(label) { mutableStateOf<Float?>(null) }
    val shown = user ?: file.takeIf { it >= 0 } ?: def
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(70.dp))
        Slider(value = drag ?: shown.toFloat(), onValueChange = { drag = it; onValue(it.roundToInt()) }, onValueChangeFinished = { drag = null },
            valueRange = 0f..127f, colors = SliderDefaults.colors(thumbColor = if (user != null) MAmber else MBlue, activeTrackColor = if (user != null) MAmber else MBlue),
            modifier = Modifier.weight(1f).height(44.dp).testTag(tag))
        Text("${fmt((drag?.roundToInt()) ?: shown)} ${if (user != null || drag != null) "USER" else "MIDI"}", color = if (user != null) MAmber else Color.White,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(96.dp).testTag(textTag))
        MixKey("MIDI", midiTag, enabled = user != null, minWidth = 72) { onValue(null) }
    }
}

/** Full-panel Voice Selector: the loaded SF2 presets (drum kits on a drum channel, melodic voices otherwise), filtered by SF2. */
@Composable
fun SongVoicePicker(channel: Int, drum: Boolean, voices: List<SongVoice>, onPick: (SongVoice) -> Unit, onCancel: () -> Unit) {
    val pool = voices.filter { it.isDrum == drum }
    val sf2s = pool.map { it.sf2 }.distinct()
    var sf2 by remember { mutableStateOf<String?>(null) }
    val shown = pool.filter { sf2 == null || it.sf2 == sf2 }
    Column(Modifier.fillMaxSize().background(Color(0xF00B0E11)).testTag(SongMixerTags.PICKER).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SELECT ${if (drum) "DRUM KIT" else "VOICE"} — CH ${channel + 1}", color = MBlue, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${shown.size} presets", color = MDim, fontSize = 12.sp)
            MixKey("CANCEL", SongMixerTags.PICKER_CANCEL, minWidth = 110, onClick = onCancel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            MixKey("ALL SF2", SongMixerTags.pickerSf2("ALL"), on = sf2 == null, minWidth = 100) { sf2 = null }
            sf2s.forEach { f -> MixKey(f, SongMixerTags.pickerSf2(f), on = sf2 == f, minWidth = 100) { sf2 = f } }
        }
        if (shown.isEmpty()) Text(if (drum) "No drum kit in the loaded SF2s" else "No melodic preset in the loaded SF2s", color = MAmber, fontSize = 14.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(shown) { i, v ->
                Row(Modifier.fillMaxWidth().testTag(SongMixerTags.pickerItem(i)).heightIn(min = 50.dp).background(MKey, RoundedCornerShape(5.dp))
                    .clickable { onPick(v) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%3d:%3d".format(v.bank, v.program), color = MDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp))
                    Text(v.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(v.sf2, color = MGreen, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
