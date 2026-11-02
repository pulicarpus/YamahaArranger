package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.VirtualKeyRouter
import com.yourapp.yamahaarranger.song.ExportFlavor
import com.yourapp.yamahaarranger.song.MidiRecorder
import com.yourapp.yamahaarranger.song.RecVoice
import com.yourapp.yamahaarranger.song.RecordMode
import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SongOutput
import com.yourapp.yamahaarranger.song.SongPlayer
import com.yourapp.yamahaarranger.song.SongTimeline
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * MIDI RECORDER V1C (FULL ARRANGER) and the on-screen keyboard lower area, on the PRODUCTION chain (HOST, mock synth, no device):
 * ArrangerBrain + the real StyleSequencer (CASM / chord retarget / sections) -> AudioEngineManager -> the recorder tap (same adapter as
 * MainViewModel). Proves: every sounding NOTE ON of keyboard AND Style is in the file exactly once (MIDI OUT ON), notes are paired,
 * keyboard and Style share one timeline (each recorded event within 2 ms of the engine call), chord changes retarget the bass,
 * Intro -> Main -> Fill -> Ending and a tempo change keep their timing, and the take plays back through the SONG player.
 * NOT audio quality (see tests/song_xg_real_bass_test.cpp for real BASSMIDI playback of a recorded file).
 */
class FullArrangerRecorderRtTest {
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
    private fun router(rig: RtRig) = VirtualKeyRouter(   // identical to MainViewModel.virtualKeyboard's router
        split = { rig.brain.splitNoteValue() }, acmp = { rig.brain.state.value.acmpEnabled }, left = { rig.brain.state.value.leftVoiceEnabled },
        lowerOn = { p, v -> rig.brain.onKeyboardNoteOn(p, v) }, lowerOff = { p -> rig.brain.onKeyboardNoteOff(p) }, right = rig.brain.virtualKeyboardSink)
    private val C = listOf(48, 52, 43); private val F = listOf(41, 45, 48)      // both under the default split 54

    @Test fun fullArrangerTakeHasEverySoundingNoteOnceOnOneTimelineAndPlaysBack() {
        DebugLog.clear()
        val rig = RtRig(true, 120)
        try {
            rig.brain.setAcmpEnabled(true); rig.brain.setLeftVoiceEnabled(false); rig.settle(30)
            val rec = MidiRecorder()
            val t0 = System.nanoTime()
            rec.start(120.0, mapOf(0 to RecVoice(0, 0, "Piano", "test.sf2")), false, "full take", (0..15).toSet(), RecordMode.FULL)
            rig.engine.noteTap = tap(rec)
            val mark = rig.engineEvents.size
            val vk = router(rig)
            C.forEach { vk.noteOn(it, 90) }; rig.settle(80)                         // chord from the ON-SCREEN keyboard (V1C lower area)
            assertTrue("on-screen keyboard chord recognised: '${rig.brain.state.value.currentChordLabel}'", rig.brain.state.value.currentChordLabel.isNotEmpty())
            rig.brain.selectSection(ArrangerSection.IntroA); rig.brain.startStop(); rig.settle(2300)    // Intro
            rig.noteOn(72, 100); rig.settle(300); rig.noteOff(72)                    // melody from USB while the Style runs
            val changeNs = System.nanoTime()
            C.forEach { vk.noteOff(it) }; F.forEach { vk.noteOn(it, 90) }; rig.settle(2200)              // chord change F (Main)
            rig.brain.selectSection(ArrangerSection.FillAA); rig.settle(2200)                           // Fill
            rig.brain.setTempo(100); rec.tempo(100.0); rig.settle(400)                                  // tempo change (VM collector does rec.tempo)
            rig.brain.selectSection(ArrangerSection.EndingA); rig.settle(2600)                          // Ending
            vk.noteOn(76, 100); rig.settle(200)                                      // still held at STOP
            if (rig.brain.state.value.isPlaying) { rig.brain.startStop(); rig.settle(200) }
            val take = rec.stop()!!; rig.engine.noteTap = null
            vk.releaseAll(); F.forEach { vk.noteOff(it) }; rig.settle(50)
            File("build/recorder-evidence").mkdirs()
            File("build/recorder-evidence/v1c_rt_full_native.mid").writeBytes(take.bytes)
            File("build/recorder-evidence/v1c_rt_full_gm.mid").writeBytes(take.bytesFor(ExportFlavor.GM))

            val f = Smf.parse(take.bytes); val t = SongTimeline.from(f)
            val engineOns = rig.engineEvents.toList().drop(mark).filter { it.kind == 'O' }
            val fileOns = t.events.filter { it.isNoteOn }
            val perCh = (0..15).associateWith { ch -> engineOns.count { it.ch == ch } to fileOns.count { it.channel == ch } }.filter { it.value.first + it.value.second > 0 }
            println("RECV1C per channel (engine, file) $perCh tracks=${f.tracks.map { it.name }} ${take.info}")
            for ((ch, v) in perCh) assertEquals("ch${ch + 1} ${MidiRecorder.partName(ch)}: file = engine, nothing doubled by MIDI OUT", v.first, v.second)
            assertTrue("Style parts recorded", listOf(8, 9, 10, 11, 12, 13, 14).all { (perCh[it]?.second ?: 0) > 0 })
            assertEquals("keyboard RIGHT melody + held on-screen note", 2, perCh[0]?.second)
            assertEquals("every note closed (Style STOP / the held key at STOP)", fileOns.size, t.events.count { it.isNoteOff })
            assertTrue(f.tracks.all { it.endOfTrack })
            assertTrue("NATIVE: both Rhythm channels are drums in the file", 8 in t.drumChannels && 9 in t.drumChannels)
            // one timeline: each recorded NOTE ON within 2 ms of its engine call (keyboard and Style alike, before and after the tempo change)
            val byCh = engineOns.groupBy { it.ch }; var worst = 0L
            for ((ch, list) in fileOns.groupBy { it.channel }) list.zip(byCh[ch] ?: emptyList()).forEach { (fe, ee) ->
                worst = maxOf(worst, kotlin.math.abs(fe.us - (ee.t - t0) / 1000)) }
            println("RECV1C timeline: worst |file - engine| = ${worst / 1000.0} ms over ${fileOns.size} NOTE ONs, tempos=${t.tempos.map { it.tick to it.usPerQuarter }}")
            assertTrue("one timeline, worst ${worst} us", worst < 2000)
            assertEquals("tempo map 120 -> 100", listOf(500_000, 600_000), t.tempos.map { it.usPerQuarter })
            // chord retarget: the bass plays C-chord pitch classes before the change and F after it
            val changeUs = (changeNs - t0) / 1000
            val bassBefore = fileOns.filter { it.channel == 10 && it.us < changeUs }.map { it.data1 % 12 }.toSet()
            val bassAfter = fileOns.filter { it.channel == 10 && it.us > changeUs + 150_000 && it.us < changeUs + 2_000_000 }.map { it.data1 % 12 }.toSet()
            println("RECV1C bass pitch classes before=$bassBefore after=$bassAfter")
            assertTrue("bass follows the chord change ($bassBefore -> $bassAfter)", 0 in bassBefore && 5 in bassAfter && bassBefore != bassAfter)
            // GM flavour: Rhythm1 + Rhythm2 both on channel 10, no XG
            val g = SongTimeline.from(Smf.parse(take.bytesFor(ExportFlavor.GM)))
            assertTrue("GM: no Rhythm on channel 9", g.events.none { !it.isSysEx && it.channel == 8 })
            assertEquals("GM: same notes", fileOns.size, g.events.count { it.isNoteOn })
            // the take plays back in the SONG player: every NOTE ON once, balanced
            val sent = ArrayList<ByteArray>()
            val out = object : SongOutput { override val id = "rec"; override val acceptsSysEx = true; override fun send(bytes: ByteArray) { sent += bytes } }
            var now = 0L; val p = SongPlayer { now }; p.load(t, "take"); p.setOutputs(listOf(out)); p.play()
            var guard = 0; while (guard++ < 1_000_000) { val n = p.step(now); if (n < 0) break; now = maxOf(n, now) }
            assertEquals("played back: every NOTE ON", fileOns.size, sent.count { (it[0].toInt() and 0xF0) == 0x90 && it[2] > 0 })
            assertEquals(0, p.activeNotes())
        } finally { rig.close() }
    }

    @Test fun onScreenKeyboardLowerAreaFollowsTheUsbAcmpLeftPolicy() {
        DebugLog.clear()
        val rig = RtRig(false, 120)
        try {
            val vk = router(rig)
            fun ons(m: Int, ch: Int) = rig.engineEvents.toList().drop(m).filter { it.kind == 'O' && it.ch == ch }.map { it.note }
            // ACMP ON, LEFT OFF: chord only
            rig.brain.setAcmpEnabled(true); rig.brain.setLeftVoiceEnabled(false); rig.settle(20)
            var m = rig.engineEvents.size
            C.forEach { vk.noteOn(it, 90) }; rig.settle(60)
            assertTrue("ACMP ON: chord recognised", rig.brain.state.value.currentChordLabel.isNotEmpty())
            assertTrue("ACMP ON, LEFT OFF: no RIGHT / LEFT voice for chord keys", ons(m, 0).isEmpty() && ons(m, 3).isEmpty())
            C.forEach { vk.noteOff(it) }; rig.settle(30)
            // ACMP OFF, LEFT ON: LEFT voice on channel 4
            rig.brain.setAcmpEnabled(false); rig.brain.setLeftVoiceEnabled(true); rig.settle(20)
            m = rig.engineEvents.size; vk.noteOn(48, 90); rig.settle(30)
            assertEquals("LEFT ON: lower key plays LEFT", listOf(48), ons(m, 3)); assertTrue(48 !in ons(m, 0))
            vk.noteOff(48); rig.settle(30); assertEquals(0, rig.oracle.stuckOnChannels(3..3))
            // ACMP OFF, LEFT OFF: whole keyboard RIGHT (unchanged on-screen RIGHT path)
            rig.brain.setLeftVoiceEnabled(false); rig.settle(20)
            m = rig.engineEvents.size; vk.noteOn(48, 90); rig.settle(30)
            assertTrue("ACMP/LEFT OFF: lower key plays RIGHT", 48 in ons(m, 0) && ons(m, 3).isEmpty()); vk.noteOff(48); rig.settle(30)
            // upper area is always the RIGHT path
            rig.brain.setLeftVoiceEnabled(true); m = rig.engineEvents.size; vk.noteOn(72, 90); rig.settle(30)
            assertTrue("upper key = RIGHT", 72 in ons(m, 0) && ons(m, 3).isEmpty()); vk.noteOff(72)
            // a held lower key keeps its route when the split moves
            vk.noteOn(50, 90); rig.settle(20); rig.brain.setSplitNote(40); vk.noteOff(50); rig.settle(30)
            assertEquals("no stuck note after the split moved under a held key", 0, rig.oracle.stuckOnChannels(0..3))
            assertTrue(vk.heldLower().isEmpty())
            println("RECV1C on-screen keyboard: ACMP chord, LEFT voice, RIGHT fallback, split move safe")
        } finally { rig.close() }
    }
}
