package com.yourapp.yamahaarranger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.yourapp.yamahaarranger.song.SongRouting
import com.yourapp.yamahaarranger.song.SongState
import com.yourapp.yamahaarranger.song.SongStatus
import com.yourapp.yamahaarranger.song.SongVoice

/** MIDI RECORDER state for the SONG page. [available] = false keeps RECORD / SAVE disabled (older callers, previews). */
data class RecorderUi(
    val available: Boolean = false, val recording: Boolean = false, val elapsedUs: Long = 0, val events: Int = 0,
    val hasTake: Boolean = false, val takeInfo: String = "", val notice: String? = null,
    /** V1C: FULL ARRANGER available; [full] = selected mode; [gm] = GM COMPATIBLE export selected (else YA NATIVE) */
    val fullAvailable: Boolean = false, val full: Boolean = false, val gm: Boolean = false,
)

object SongTags {
    const val PANEL = "song.panel"; const val CLOSE = "song.close"; const val OPEN = "song.open"; const val PLAY = "song.play"; const val STOP = "song.stop"
    const val PREV = "song.prev"; const val NEXT = "song.next"; const val RECORD = "song.record"; const val SAVE = "song.save"; const val SEEK = "song.seek"
    const val POSITION = "song.position"; const val TIME = "song.time"; const val TEMPO = "song.tempo"; const val TEMPO_DOWN = "song.tempo.down"
    const val TEMPO_UP = "song.tempo.up"; const val TEMPO_RESET = "song.tempo.reset"; const val TIMESIG = "song.timesig"; const val NOTICE = "song.notice"
    const val TITLE = "song.title"; const val MODE_KEYBOARD = "song.mode.keyboard"; const val MODE_FULL = "song.mode.full"
    const val REC_TIME = "song.rec.time"; const val REC_LOAD = "song.rec.load"; const val REC_INFO = "song.rec.info"
    const val EXPORT_NATIVE = "song.export.native"; const val EXPORT_GM = "song.export.gm"
    fun routing(r: SongRouting) = "song.routing.${r.name}"
    fun channel(ch: Int) = "song.ch.$ch"
}

private val SBg = Color(0xFF0B0E11); private val SPanel = Color(0xFF161C22); private val SBlue = Color(0xFF3FA2FF); private val SGreen = Color(0xFF1E8E3E)
private val SRed = Color(0xFF8E2B2B); private val SAmber = Color(0xFFFFC857); private val SDim = Color(0xFF8D9AA6); private val SKey = Color(0xFF26303A)

@Composable
private fun SongKey(text: String, tag: String, modifier: Modifier = Modifier, color: Color = SKey, enabled: Boolean = true, minWidth: Int = 96, sub: String? = null, onClick: () -> Unit) {
    Box(
        modifier.testTag(tag).heightIn(min = 56.dp).widthIn(min = minWidth.dp)
            .background(if (enabled) color else Color(0xFF1A1F25), RoundedCornerShape(6.dp))
            .border(1.dp, if (enabled) Color(0xFF3A4652) else Color(0xFF262C33), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (enabled) Color.White else Color(0xFF5D6873), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            if (sub != null) Text(sub, color = if (enabled) SDim else Color(0xFF4A545E), fontSize = 9.sp, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String, tag: String, modifier: Modifier = Modifier, big: Boolean = false, content: @Composable () -> Unit = {}) {
    Column(modifier.background(SPanel, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(label, color = SDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = if (big) 26.sp else 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            maxLines = 1, softWrap = false, modifier = Modifier.testTag(tag))
        content()
    }
}

private fun mmss(us: Long): String { val s = us / 1_000_000; return "%d:%02d".format(s / 60, s % 60) }

/**
 * SONG page (MIDI Song Player V1A). Portable Compose (no view model): the Android dialog polls [status] and wires the callbacks.
 * Recording controls are shown but disabled until V1B / V1C exist (they never pretend to work).
 */
@Composable
fun SongPanel(
    status: SongStatus, routing: SongRouting, notice: String?, nowNs: Long,
    onOpen: () -> Unit, onPlayPause: () -> Unit, onStop: () -> Unit, onPrevBar: () -> Unit, onNextBar: () -> Unit,
    onSeek: (Float) -> Unit, onTempoPercent: (Int) -> Unit, onRouting: (SongRouting) -> Unit, onMute: (Int) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
    voices: List<SongVoice> = emptyList(), mixer: SongMixerActions = SongMixerActions(), liveReport: String = "",
    onPickerOpen: () -> Unit = {},
    /** initial view (previews / evidence renders): MIXER tab, selected channel, Voice Selector open */
    startInMixer: Boolean = false, startChannel: Int = 0, startPicker: Boolean = false,
    recorder: RecorderUi = RecorderUi(), onRecord: () -> Unit = {}, onSaveTake: () -> Unit = {}, onLoadTake: () -> Unit = {},
    onRecordMode: (Boolean) -> Unit = {}, onExportGm: (Boolean) -> Unit = {},
) {
    val loaded = status.state != SongState.EMPTY
    var mixerTab by remember { mutableStateOf(startInMixer) }
    var selected by remember { mutableStateOf(startChannel.coerceIn(0, 15)) }
    var picker by remember { mutableStateOf(startPicker) }
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(SBg).testTag(SongTags.PANEL)) {
        Row(Modifier.fillMaxWidth().background(SPanel).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("SONG", color = SBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text(if (loaded) status.title else "No MIDI song loaded — OPEN MIDI (.mid / .midi, SMF Type 0/1)", color = if (loaded) Color.White else SDim,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag(SongTags.TITLE))
            SongKey("CLOSE", SongTags.CLOSE, color = SRed, minWidth = 100, onClick = onClose)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // transport
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SongKey("OPEN MIDI", SongTags.OPEN, color = Color(0xFF1F5E8C), minWidth = 120, onClick = onOpen)
                SongKey("◀◀ BAR", SongTags.PREV, enabled = loaded, onClick = onPrevBar)
                SongKey(if (status.state == SongState.PLAYING) "❚❚ PAUSE" else "▶ PLAY", SongTags.PLAY, color = SGreen, enabled = loaded, minWidth = 140, onClick = onPlayPause)
                SongKey("■ STOP", SongTags.STOP, enabled = loaded, onClick = onStop)
                SongKey("BAR ▶▶", SongTags.NEXT, enabled = loaded, onClick = onNextBar)
                Spacer(Modifier.width(8.dp))
                if (recorder.available) {
                    SongKey(if (recorder.recording) "■ STOP REC" else "● RECORD", SongTags.RECORD, color = if (recorder.recording) SRed else Color(0xFF5A2330),
                        minWidth = 120, sub = if (recorder.recording) "${mmss(recorder.elapsedUs)} · ${recorder.events} ev" else if (recorder.full) "FULL ARRANGER" else "KEYBOARD ONLY", onClick = onRecord)
                    SongKey("SAVE MIDI", SongTags.SAVE, enabled = recorder.hasTake && !recorder.recording, minWidth = 110, sub = "SMF Type 1", onClick = onSaveTake)
                } else {
                    SongKey("● RECORD", SongTags.RECORD, enabled = false, minWidth = 110, sub = "next step (V1B)") {}
                    SongKey("SAVE MIDI", SongTags.SAVE, enabled = false, minWidth = 110, sub = "next step (V1B)") {}
                }
            }
            // position / time / tempo / signature / output
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoCard("BAR : BEAT", status.barBeat?.toString() ?: "---:--", SongTags.POSITION, big = true)
                InfoCard("TIME", "${mmss(status.positionUs)} / ${mmss(status.durationUs)}", SongTags.TIME)
                InfoCard("TEMPO", if (loaded) "%.1f BPM".format(status.effectiveBpm) else "—", SongTags.TEMPO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallSongKey("−5%", SongTags.TEMPO_DOWN, loaded) { onTempoPercent(status.tempoPercent - 5) }
                        SmallSongKey("${status.tempoPercent}%", SongTags.TEMPO_RESET, loaded) { onTempoPercent(100) }
                        SmallSongKey("+5%", SongTags.TEMPO_UP, loaded) { onTempoPercent(status.tempoPercent + 5) }
                    }
                }
                InfoCard("TIME SIG", status.timeSig, SongTags.TIMESIG)
                Column(Modifier.background(SPanel, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("OUTPUT", color = SDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SongRouting.values().forEach { r -> SmallSongKey(r.label, SongTags.routing(r), true, selected = r == routing) { onRouting(r) } }
                    }
                }
            }
            // seek
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: status.progress, onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let(onSeek); dragging = null }, enabled = loaded,
                colors = SliderDefaults.colors(thumbColor = SBlue, activeTrackColor = SBlue),
                modifier = Modifier.fillMaxWidth().height(40.dp).testTag(SongTags.SEEK)
            )
            // CHANNELS (tap = mute) or MIXER (V1A.2: voice / volume / pan / sustain / solo per channel)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallSongKey("CHANNELS", SongMixerTags.TAB_CHANNELS, true, selected = !mixerTab) { mixerTab = false }
                SmallSongKey("MIXER", SongMixerTags.TAB_MIXER, true, selected = mixerTab) { mixerTab = true }
                Text(if (mixerTab) "tap a channel to edit it" else "tap = MUTE", color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (mixerTab) {
                SongMixerSection(status, nowNs, selected, { selected = it }, { picker = true; onPickerOpen() }, mixer, liveReport)
            } else {
                for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    for (ch in row * 8 until row * 8 + 8) ChannelCell(status, ch, nowNs, Modifier.weight(1f)) { onMute(ch) }
                }
            }
            // recording mode: V1B KEYBOARD ONLY; FULL ARRANGER is V1C (after V1B is verified on the device)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RECORD MODE", color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (recorder.available) {
                    SmallSongKey("KEYBOARD ONLY", SongTags.MODE_KEYBOARD, !recorder.recording, selected = !recorder.full) { onRecordMode(false) }
                    if (recorder.fullAvailable) SmallSongKey("FULL ARRANGER", SongTags.MODE_FULL, !recorder.recording, selected = recorder.full) { onRecordMode(true) }
                    else SongKey("FULL ARRANGER", SongTags.MODE_FULL, enabled = false, minWidth = 150, sub = "V1C, after V1B is verified") {}
                    if (recorder.fullAvailable) {
                        Text("EXPORT", color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        SmallSongKey("YA NATIVE", SongTags.EXPORT_NATIVE, true, selected = !recorder.gm) { onExportGm(false) }
                        SmallSongKey("GM", SongTags.EXPORT_GM, true, selected = recorder.gm) { onExportGm(true) }
                    }
                    SongKey("LOAD TAKE", SongTags.REC_LOAD, enabled = recorder.hasTake && !recorder.recording, minWidth = 120, sub = "into the player", onClick = onLoadTake)
                    Text(if (recorder.recording) "● REC ${mmss(recorder.elapsedUs)}" else "", color = Color(0xFFFF6B6B), fontSize = 16.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(SongTags.REC_TIME))
                } else {
                    SongKey("KEYBOARD ONLY", SongTags.MODE_KEYBOARD, enabled = false, minWidth = 150, sub = "not available yet (V1B)") {}
                    SongKey("FULL ARRANGER", SongTags.MODE_FULL, enabled = false, minWidth = 150, sub = "not available yet (V1C)") {}
                }
            }
            if (recorder.available && (recorder.notice != null || recorder.takeInfo.isNotEmpty()))
                Text(recorder.notice ?: recorder.takeInfo, color = Color(0xFFFF9E9E), fontSize = 12.sp, modifier = Modifier.testTag(SongTags.REC_INFO))
            Text(notice ?: "", color = SAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(SongTags.NOTICE))
            Spacer(Modifier.height(8.dp))
        }
    }
    if (picker) SongVoicePicker(selected, selected in status.drumChannels, voices, { v -> picker = false; mixer.onVoice(selected, v) }, { picker = false })
    }
}

@Composable
private fun SmallSongKey(text: String, tag: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.testTag(tag).heightIn(min = 40.dp).widthIn(min = 52.dp)
        .background(if (selected) Color(0xFF1F5E8C) else if (enabled) SKey else Color(0xFF1A1F25), RoundedCornerShape(5.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) SBlue else Color(0xFF3A4652), RoundedCornerShape(5.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) Color.White else Color(0xFF5D6873), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ChannelCell(status: SongStatus, ch: Int, nowNs: Long, modifier: Modifier, onMute: () -> Unit) {
    val used = ch in status.channelsUsed
    val muted = status.muted.getOrElse(ch) { false }
    val activeNote = used && !muted && nowNs - status.lastNoteOnNs.getOrElse(ch) { 0L } in 0..150_000_000L
    Column(modifier.testTag(SongTags.channel(ch)).heightIn(min = 72.dp)
        .background(if (muted) Color(0xFF3A1B1B) else if (used) SPanel else Color(0xFF101418), RoundedCornerShape(5.dp))
        .border(1.dp, if (muted) Color(0xFFB54747) else Color(0xFF2E3740), RoundedCornerShape(5.dp))
        .clickable(enabled = used, onClick = onMute).padding(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CH ${ch + 1}", color = if (used) Color.White else Color(0xFF4A545E), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box(Modifier.size(10.dp).background(if (activeNote) Color(0xFF4CD37B) else Color(0xFF26303A), CircleShape))
        }
        Text(if (used) status.overrides.getOrNull(ch)?.voice?.name ?: GmNames.name(ch, status.programs.getOrElse(ch) { -1 }, status.bankMsb.getOrElse(ch) { -1 }) else "—",
            color = if (used) SDim else Color(0xFF3A444E), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (muted) Text("MUTE", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
