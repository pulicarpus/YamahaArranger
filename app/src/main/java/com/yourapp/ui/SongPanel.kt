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

object SongTags {
    const val PANEL = "song.panel"; const val CLOSE = "song.close"; const val OPEN = "song.open"; const val PLAY = "song.play"; const val STOP = "song.stop"
    const val PREV = "song.prev"; const val NEXT = "song.next"; const val RECORD = "song.record"; const val SAVE = "song.save"; const val SEEK = "song.seek"
    const val POSITION = "song.position"; const val TIME = "song.time"; const val TEMPO = "song.tempo"; const val TEMPO_DOWN = "song.tempo.down"
    const val TEMPO_UP = "song.tempo.up"; const val TEMPO_RESET = "song.tempo.reset"; const val TIMESIG = "song.timesig"; const val NOTICE = "song.notice"
    const val TITLE = "song.title"; const val MODE_KEYBOARD = "song.mode.keyboard"; const val MODE_FULL = "song.mode.full"
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
) {
    val loaded = status.state != SongState.EMPTY
    Column(modifier.fillMaxSize().background(SBg).testTag(SongTags.PANEL)) {
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
                SongKey("● RECORD", SongTags.RECORD, enabled = false, minWidth = 110, sub = "next step (V1B)") {}
                SongKey("SAVE MIDI", SongTags.SAVE, enabled = false, minWidth = 110, sub = "next step (V1B)") {}
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
            // recording mode (not available in V1A)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RECORD MODE", color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                SongKey("KEYBOARD ONLY", SongTags.MODE_KEYBOARD, enabled = false, minWidth = 150, sub = "not available yet (V1B)") {}
                SongKey("FULL ARRANGER", SongTags.MODE_FULL, enabled = false, minWidth = 150, sub = "not available yet (V1C)") {}
            }
            // 16-channel mixer: name, program, activity, mute (tap)
            Text("CHANNELS  (tap = MUTE)", color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            for (row in 0 until 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                for (ch in row * 8 until row * 8 + 8) ChannelCell(status, ch, nowNs, Modifier.weight(1f)) { onMute(ch) }
            }
            Text(notice ?: "", color = SAmber, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(SongTags.NOTICE))
            Spacer(Modifier.height(8.dp))
        }
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
        Text(if (used) GmNames.name(ch, status.programs.getOrElse(ch) { -1 }, status.bankMsb.getOrElse(ch) { -1 }) else "—",
            color = if (used) SDim else Color(0xFF3A444E), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (muted) Text("MUTE", color = Color(0xFFFF6B6B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
