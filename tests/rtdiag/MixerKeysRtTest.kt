package com.yourapp.rtdiag

import com.yourapp.midi.ChannelActivity
import com.yourapp.midi.ChannelActivity.Source
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Production chain (USB bytes -> parser -> Brain/engine/router) for the live key mirror, activity indicators and the Mixer's mute / solo / pan paths. HOST only; no PSR-E343. */
class MixerKeysRtTest {
    @Before fun fresh() { ChannelActivity.reset() }
    private fun rig(out: Boolean = true): RtRig { DebugLog.clear(); return RtRig(out, 120) }

    @Test fun threeKeysLightImmediatelyAndReleaseOneByOneFromRealUsbBytes() {
        val r = rig()
        try {
            val keys = r.midi.liveKeys
            r.noteOn(60, 100); r.noteOn(64, 90); r.noteOn(67, 80)
            assertEquals("C-E-G lit at once", listOf(60, 64, 67), keys.downNotes())
            assertEquals(90, keys.velocityOf(64))
            r.noteOff(64); assertEquals(listOf(60, 67), keys.downNotes())
            r.feed(0x90, 60, 0)                                 // NOTE_ON velocity 0 = NOTE_OFF
            assertEquals(listOf(67), keys.downNotes())
            r.noteOff(67); assertEquals(0, keys.downCount())
        } finally { r.close() }
    }

    @Test fun mirrorShowsTheOriginalNoteNotTheTransposedOrRoutedOne() {
        val r = rig()
        try {
            r.brain.setKeyboardTranspose(5)
            r.noteOn(72, 100)                                    // RIGHT area: the engine receives 77, the mirror must show 72
            assertEquals(listOf(72), r.midi.liveKeys.downNotes())
            assertTrue("the engine really played the transposed note", r.engineEvents.any { it.kind == 'O' && it.note == 77 })
            r.noteOff(72); assertEquals(0, r.midi.liveKeys.downCount())
            r.noteOn(40, 100)                                    // LEFT/ACMP area: shown too, as played
            assertEquals(listOf(40), r.midi.liveKeys.downNotes()); r.noteOff(40)
        } finally { r.close() }
    }

    @Test fun rapidPressesAndReleasesLeaveNothingLitAndDisconnectClearsEverything() {
        val r = rig()
        try {
            repeat(400) { i -> val n = 60 + i % 12; r.noteOn(n, 1 + i % 127); if (i % 2 == 0) r.noteOff(n) }
            for (n in 60..71) r.noteOff(n)
            assertEquals(0, r.midi.liveKeys.downCount())
            r.noteOn(60, 80); r.noteOn(64, 80); assertEquals(2, r.midi.liveKeys.downCount())
            r.midi.close()                                       // device removed / disconnected
            assertEquals("disconnect clears the visual state", 0, r.midi.liveKeys.downCount())
        } finally { r.close() }
    }

    @Test fun mirrorDoesNotTouchRoutingOrMidiOut() {
        val on = rig(); val before: Int
        try { on.noteOn(72, 100); on.settle(10); on.noteOff(72); on.settle(10); before = on.usbWrites.count { (it.status and 0xF0) == 0x90 } } finally { on.close() }
        assertEquals("one NOTE_ON per played note reaches MIDI OUT, no echo and no extra message from the visualizer", 1, before)
    }

    @Test fun otherMidiChannelsDoNotLightTheMirrorBecauseTheyAreNotPlayed() {
        val r = rig()
        try {
            r.noteOn(60, 100, ch = 5)
            assertEquals("same channel filter as the playing path", 0, r.midi.liveKeys.downCount())
            r.noteOn(60, 100, ch = 0); assertEquals(1, r.midi.liveKeys.downCount())
        } finally { r.close() }
    }

    @Test fun midiOutActivityOnlyCountsMessagesReallyWrittenAndRespectsMute() {
        val r = rig(out = false)
        try {
            r.midi.sendNoteOn(11, 60, 100)
            assertTrue("internal note activity is recorded even with MIDI OUT off", ChannelActivity.level(Source.NOTES, 11) > 0f)
            assertEquals("no MIDI OUT message, no MIDI OUT activity", 0f, ChannelActivity.level(Source.MIDI_OUT, 11), 0f)
            r.midi.midiOutEnabled = true
            r.midi.sendNoteOn(11, 62, 100)
            assertTrue(ChannelActivity.level(Source.MIDI_OUT, 11) > 0f)
            ChannelActivity.reset()
            r.midi.setMixerMutedChannels(setOf(12))
            r.midi.sendNoteOn(12, 60, 100)
            assertEquals("a muted part writes nothing to MIDI OUT, so its MIDI OUT indicator stays dark", 0f, ChannelActivity.level(Source.MIDI_OUT, 12), 0f)
            assertTrue("while the internal engine still reports the note", ChannelActivity.level(Source.NOTES, 12) > 0f)
        } finally { r.close() }
    }

    @Test fun muteReleasesHeldNotesWithoutStickingAndLeavesOtherPartsAlone() {
        val r = rig()
        try {
            r.midi.sendNoteOn(10, 48, 100); r.midi.sendNoteOn(11, 60, 100)
            val before = r.usbWrites.size
            r.midi.setMixerMutedChannels(setOf(10))
            val offs = r.usbWrites.toList().drop(before).filter { (it.status and 0xF0) == 0x80 }
            assertEquals("only the muted part is released", listOf(10 to 48), offs.map { (it.status and 15) to it.d1 })
            r.midi.sendNoteOff(11, 60)
            assertTrue("the other part still releases normally", r.usbWrites.any { (it.status and 0xF0) == 0x80 && (it.status and 15) == 11 && it.d1 == 60 })
            r.midi.sendNoteOff(10, 48)
            r.midi.setMixerMutedChannels(emptySet())
            val stray = r.usbWrites.toList().filter { (it.status and 0xF0) == 0x90 && (it.status and 15) == 10 }
            assertEquals("unmuting does not replay the old note", 1, stray.size)
        } finally { r.close() }
    }

    @Test fun panOfOnePartSendsCc10ForThatChannelOnly() {
        val r = rig()
        try {
            r.wireProductionMixerOutput()
            r.usbWrites.clear()
            r.engine.setChannelMixer(11, 100, 20, 127, 40, 0)
            val pans = r.usbWrites.toList().filter { (it.status and 0xF0) == 0xB0 && it.d1 == 10 }
            assertTrue("pan reaches MIDI OUT only on channel 11", pans.isNotEmpty() && pans.all { (it.status and 15) == 11 && it.d2 == 20 })
            assertTrue("no other channel received any controller", r.usbWrites.toList().all { (it.status and 15) == 11 || (it.status and 0xF0) !in setOf(0xB0) })
        } finally { r.close() }
    }
}
