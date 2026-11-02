package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.song.MidiRecorder
import com.yourapp.yamahaarranger.song.RecVoice
import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SongTimeline
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Test

/**
 * MIDI RECORDER V1B on the production keyboard chain (HOST, no device): USB bytes and the on-screen keyboard -> ArrangerBrain (split,
 * transpose, RIGHT octave, LEFT, sustain, MIDI OUT ON) -> AudioEngineManager -> the recorder tap (same adapter as MainViewModel).
 * The file must contain exactly the notes the engine sounded on channels 1-4: transposed / octaved pitches, nothing from MIDI OUT twice.
 */
class RecorderRtTest {
    private fun tap(r: MidiRecorder) = object : AudioEngineManager.NoteTap {   // identical to MainViewModel.recorderTap
        override fun noteOn(channel: Int, note: Int, velocity01: Float) = r.noteOn(channel, note, velocity01)
        override fun noteOff(channel: Int, note: Int) = r.noteOff(channel, note)
        override fun partStopped(channel: Int) = r.channelStopped(channel)
        override fun allStopped() = r.allStopped()
        override fun sustain(on: Boolean) = r.sustain(on)
        override fun keyboardVoice(channel: Int, bank: Int, program: Int, name: String, sf2: String) = r.voice(channel, RecVoice(bank, program, name, sf2))
    }
    private fun notes(f: com.yourapp.yamahaarranger.song.SmfFile, ch: Int) =
        f.tracks.flatMap { it.events }.filter { it.isChannel && it.channel == ch && it.isNoteOn }.map { it.data1 }

    @Test fun recordsTheSoundingKeyboardPartsOnceWithTransposeOctaveLeftAndSustain() {
        DebugLog.clear()
        val rig = RtRig(true, 120)                      // MIDI OUT ON: every keyboard note also goes to the external module
        try {
            rig.brain.setAcmpEnabled(false); rig.brain.setLeftVoiceEnabled(true)
            rig.brain.setRightVoiceEnabled(1, true); rig.brain.setRightOctave(1, 1); rig.brain.setKeyboardTranspose(2); rig.settle(30)
            val rec = MidiRecorder()
            rec.start(120.0, mapOf(0 to RecVoice(0, 0, "Piano", "test.sf2")), false, "rt take")
            rig.engine.noteTap = tap(rec)
            val before = rig.engineEvents.size
            rig.noteOn(72, 100); rig.noteOn(48, 80); rig.settle(40)          // RIGHT area + LEFT area (split 54)
            rig.cc(64, 127); rig.settle(20)                                    // pedal down (USB)
            rig.noteOff(72); rig.settle(40)                                    // held by sustain: no engine NOTE OFF yet
            rig.cc(64, 0); rig.settle(30); rig.noteOff(48); rig.settle(30)
            rig.brain.virtualKeyboardSink.noteOn(76, 110); rig.settle(30); rig.brain.virtualKeyboardSink.noteOff(76); rig.settle(30)
            rig.noteOn(60, 90); rig.settle(30)                                 // still held when STOP is pressed
            val take = rec.stop()!!; rig.engine.noteTap = null
            val f = Smf.parse(take.bytes)
            val engineOns = rig.engineEvents.toList().drop(before).filter { it.kind == 'O' && it.ch in 0..3 }
            println("RECRT engine NOTE ONs ch1-4=${engineOns.map { it.ch to it.note }} file R1=${notes(f, 0)} R2=${notes(f, 1)} LEFT=${notes(f, 3)} ${take.info}")
            // USB keys follow Master Transpose (+2); the on-screen keyboard is its own path (own OCTAVE, no Master Transpose: VirtualKeyboard.kt),
            // so it SOUNDS 76 and the file must say 76 (the recorder writes what is heard)
            assertEquals("RIGHT1: USB 72 -> 74, virtual 76 -> 76, USB 60 -> 62", listOf(74, 76, 62), notes(f, 0))
            assertEquals("RIGHT2: USB +2 and one octave up (86, 74); the on-screen key sounds as is on every enabled layer (76)", listOf(86, 76, 74), notes(f, 1))
            assertEquals("LEFT: +2 on channel 4", listOf(50), notes(f, 3))
            assertEquals("exactly the engine's NOTE ONs, nothing doubled by MIDI OUT", engineOns.size, f.tracks.flatMap { it.events }.count { it.isChannel && it.isNoteOn })
            val r1 = f.tracks.first { it.name == "RIGHT1" }.events.filter { it.isChannel }
            val pedalUp = r1.first { it.command == 0xB0 && it.data1 == 64 && it.data2 == 0 }.tick
            val off74 = r1.first { it.isNoteOff && it.data1 == 74 }.tick
            assertTrue("the sustained note ends with the pedal, not with the key ($off74 >= $pedalUp)", off74 >= pedalUp)
            assertTrue("pedal down recorded", r1.any { it.command == 0xB0 && it.data1 == 64 && it.data2 == 127 })
            val t = SongTimeline.from(f)
            assertEquals("every note closed (the one held at STOP too)", t.events.count { it.isNoteOn }, t.events.count { it.isNoteOff })
            assertTrue(f.tracks.all { it.endOfTrack })
            rig.noteOff(60); rig.settle(20)
            assertEquals("after STOP nothing more is recorded", take.notes, rec.lastTake()!!.notes)
        } finally { rig.close() }
    }
}
