package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.VirtualKeyRouter
import com.yourapp.yamahaarranger.song.MidiRecorder
import com.yourapp.yamahaarranger.song.RecVoice
import com.yourapp.yamahaarranger.song.RecordMode
import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SongOutput
import com.yourapp.yamahaarranger.song.SongPlayer
import com.yourapp.yamahaarranger.song.SongTimeline
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * V1C INVESTIGATION (device report: FULL ARRANGER take "not musical, chord changes not heard"). REAL Style (ROSITA, --rosita), production
 * chain, chords C -> F -> G from the ON-SCREEN keyboard. Compares, per chord segment and per Style channel, the ordered NOTE ON pitches the
 * ENGINE received live with the ones in the FILE and the ones the SONG PLAYER sends on LOAD TAKE; and the VOICE the engine was asked for
 * (native calls) with the bank / program the file carries. Prints a parity table (RECPAR lines); assertions only on note parity.
 */
class RecorderParityRtTest {
    private fun tap(r: MidiRecorder) = object : AudioEngineManager.NoteTap {   // identical to MainViewModel.recorderTap
        override fun noteOn(channel: Int, note: Int, velocity01: Float) = r.noteOn(channel, note, velocity01)
        override fun noteOff(channel: Int, note: Int) = r.noteOff(channel, note)
        override fun partStopped(channel: Int) = r.channelStopped(channel)
        override fun allStopped() = r.allStopped()
        override fun sustain(on: Boolean) = r.sustain(on)
        override fun keyboardVoice(channel: Int, bank: Int, program: Int, name: String, sf2: String) = r.voice(channel, RecVoice(bank, program, name, sf2))
        override fun styleVoice(channel: Int, bank: Int, program: Int, name: String, sf2: String, bank14: Boolean) = r.voice(channel, RecVoice(bank, program, name, sf2, bank14))
        override fun controllers(channel: Int, pairs: IntArray) = r.controllers(channel, pairs)
    }
    private val names = mapOf(8 to "Rhythm1", 9 to "Rhythm2", 10 to "Bass", 11 to "Chord1", 12 to "Chord2", 13 to "Pad", 14 to "Phrase1", 15 to "Phrase2")
    private fun pcs(l: List<Int>) = l.map { listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[it % 12] }.toSortedSet().joinToString("")

    @Test fun chordChangesVoicesAndPlaybackParity() {
        val st = StyleTsv.load(); assumeTrue("ROSITA TSV (--rosita) not set", st != null)
        DebugLog.clear()
        val rig = RtRig(true, 120, st)
        try {
            rig.brain.setAcmpEnabled(true); rig.brain.setLeftVoiceEnabled(false); rig.settle(30)
            val vk = VirtualKeyRouter({ rig.brain.splitNoteValue() }, { rig.brain.state.value.acmpEnabled }, { rig.brain.state.value.leftVoiceEnabled },
                { p, v -> rig.brain.onKeyboardNoteOn(p, v) }, { p -> rig.brain.onKeyboardNoteOff(p) }, rig.brain.virtualKeyboardSink)
            val chords = listOf("C" to listOf(48, 52, 43), "F" to listOf(41, 45, 48), "G" to listOf(43, 47, 50))
            val rec = MidiRecorder(); val t0 = System.nanoTime()
            rec.start(rig.brain.state.value.tempoBpm.toDouble(), emptyMap(), false, "parity", (0..15).toSet(), RecordMode.FULL)
            rig.engine.noteTap = tap(rec)
            val mark = rig.engineEvents.size
            val seg = ArrayList<Triple<String, Long, Long>>(); var held = emptyList<Int>()
            val labels = ArrayList<String>()
            for ((i, c) in chords.withIndex()) {
                held.forEach { vk.noteOff(it) }; c.second.forEach { vk.noteOn(it, 90) }; held = c.second
                val from = System.nanoTime(); rig.settle(80); labels += "${c.first}->'${rig.brain.state.value.currentChordLabel}'"
                if (i == 0) { rig.brain.startStop(); }
                rig.settle(if (i == 0) 4200 else 4000)
                seg += Triple(c.first, from, System.nanoTime())
            }
            if (rig.brain.state.value.isPlaying) { rig.brain.startStop(); rig.settle(300) }
            val take = rec.stop()!!; rig.engine.noteTap = null; held.forEach { vk.noteOff(it) }
            java.io.File("build/recorder-evidence").mkdirs(); java.io.File("build/recorder-evidence/v1c_parity_rosita.mid").writeBytes(take.bytes)
            println("RECPAR live chord state after each on-screen chord: $labels")

            val eng = rig.engineEvents.toList().drop(mark).filter { it.kind == 'O' }
            val t = SongTimeline.from(Smf.parse(take.bytes))
            val sent = ArrayList<Pair<Long, ByteArray>>(); var now = 0L
            val out = object : SongOutput { override val id = "i"; override val acceptsSysEx = true; override fun send(bytes: ByteArray) { sent += now to bytes } }
            val p = SongPlayer { now }; p.load(t, "parity"); p.setOutputs(listOf(out)); p.play()
            var g = 0; while (g++ < 2_000_000) { val n = p.step(now); if (n < 0) break; now = maxOf(n, now) }
            val playOns = sent.filter { (it.second[0].toInt() and 0xF0) == 0x90 && it.second.size > 2 && it.second[2] > 0 }
            var mismatches = 0
            for ((name, from, to) in seg) for (ch in 8..15) {
                val a = (from + 200_000_000L - t0) / 1000; val b = (to - t0) / 1000
                val live = eng.filter { it.ch == ch && (it.t - t0) / 1000 in a until b }.map { it.note }
                val file = t.events.filter { it.isNoteOn && it.channel == ch && it.us in a until b }.map { it.data1 }
                val play = playOns.filter { (it.second[0].toInt() and 0x0F) == ch && it.first / 1000 in a until b }.map { it.second[1].toInt() and 0x7F }
                if (live.isEmpty() && file.isEmpty()) continue
                val ok = live == file && file == play
                if (!ok) mismatches++
                println("RECPAR chord $name ${names[ch]} (ch${ch + 1}) live=${pcs(live)}[${live.size}] file=${pcs(file)}[${file.size}] playback=${pcs(play)}[${play.size}] ${if (ok) "SAME" else "DIFF"}")
            }
            // voices: what the engine was asked for (native calls) vs what the file carries
            val inv = Mockito.mockingDetails(rig.bridge).invocations.map { it.method.name to it.arguments.toList() }
            for (ch in 8..15) {
                val req = inv.lastOrNull { (n, a) -> (n == "nativeSetChannelPresetWithName" || n == "nativeSetChannelPreset") && a[0] == ch }
                val drumBind = inv.lastOrNull { (n, a) -> (n == "nativeBindDrumAB" || n == "nativeBindDrumManual" || n == "nativePinChannelPreset") && a[0] == ch }
                val trk = t.events.filter { !it.isSysEx && it.channel == ch && (it.command == 0xC0 || (it.command == 0xB0 && it.data1 in listOf(0, 32))) }.take(3)
                    .joinToString(",") { if (it.command == 0xC0) "PC${it.data1}" else "CC${it.data1}=${it.data2}" }
                if (req == null && drumBind == null && trk.isEmpty()) continue
                println("RECPAR voice ${names[ch]} (ch${ch + 1}) engine=${req?.let { "${it.first}(bank14=${it.second[1]} -> MSB ${(it.second[1] as Int) / 128} LSB ${(it.second[1] as Int) % 128}, pc=${it.second[2]}${if (it.second.size > 3) ", '" + it.second[3] + "'" else ""})" } ?: "-"}" +
                    "${drumBind?.let { " drumBind=${it.first}${it.second}" } ?: ""} file=[${trk.ifEmpty { "NO BANK/PROGRAM" }}]")
            }
            println("RECPAR ${take.info}; drum channels in file=${t.drumChannels}; note-sequence mismatches=$mismatches")
            assertEquals("live engine = file = playback, ordered NOTE ON pitches per chord segment and Style channel", 0, mismatches)
        } finally { rig.close() }
    }
}
