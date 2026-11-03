package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.song.MidiRecorder
import com.yourapp.yamahaarranger.song.RecVoice
import com.yourapp.yamahaarranger.song.RecordMode
import com.yourapp.yamahaarranger.song.ExportFlavor
import com.yourapp.yamahaarranger.song.SrcPreset
import com.yourapp.yamahaarranger.song.YaVoiceMap
import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SmfWriter
import com.yourapp.yamahaarranger.song.SongTimeline
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** MIDI RECORDER V1B: musical timeline, SMF Type 1 structure, note ledger, tempo map, snapshot and round trip through the SONG parser. */
class MidiRecorderTest {
    private class Clock { var ns = 5_000_000_000L; fun at(s: Double) { ns = 5_000_000_000L + (s * 1e9).toLong() } }
    private val piano = RecVoice(0, 0, "Grand Piano", "colombo.sf2")
    private val evidence = File("build/recorder-evidence").apply { mkdirs() }
    private fun save(name: String, b: ByteArray) = File(evidence, "$name.mid").writeBytes(b)
    private fun ch(e: com.yourapp.yamahaarranger.song.SmfEvent) = e.status and 0x0F

    @Test fun writerProducesAValidType1FileWithEndOfTrackOnEveryTrack() {
        val b = SmfWriter.write(1, 960, listOf(listOf(SmfWriter.Ev(0, SmfWriter.tempo(500_000))), listOf(SmfWriter.Ev(0, byteArrayOf(0x90.toByte(), 60, 100)),
            SmfWriter.Ev(960, byteArrayOf(0x80.toByte(), 60, 0)))), listOf(1920, 1920))
        val f = Smf.parse(b)
        assertEquals(1, f.format); assertEquals(960, f.ppq); assertEquals(2, f.tracks.size); assertTrue(f.tracks.all { it.endOfTrack })
        assertEquals("EOT at tick 1920", 1920L, f.tracks[1].events.last().tick)
        assertEquals(listOf<Byte>(0x81.toByte(), 0x00), SmfWriter.vlq(128).toList()); assertEquals(listOf<Byte>(0x7F), SmfWriter.vlq(127).toList())
        assertTrue(try { SmfWriter.write(1, 960, listOf(listOf(SmfWriter.Ev(10, byteArrayOf(0x90.toByte(), 1, 1)), SmfWriter.Ev(5, byteArrayOf(0x80.toByte(), 1, 0)))), listOf(0)); false } catch (e: IllegalArgumentException) { true })
    }

    @Test fun keyboardTakeHasMusicalTicksTracksVoicesAndSustain() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        c.at(0.0); r.start(120.0, mapOf(0 to piano, 3 to RecVoice(32, 33, "Finger Bass", "sx.sf2")), false, "Take A")
        c.at(0.5); r.noteOn(0, 64, 100 / 127f); r.noteOn(1, 76, 0.5f)            // RIGHT1 + RIGHT2 (octave up) layer
        c.at(0.75); r.sustain(true)
        c.at(1.0); r.noteOff(0, 64); r.noteOff(1, 76); r.noteOn(3, 40, 0.8f)
        c.at(1.5); r.sustain(false); r.noteOff(3, 40)
        r.noteOn(9, 36, 1f)                                                         // not a keyboard channel: ignored
        c.at(2.0); val take = r.stop()!!
        save("v1b_keyboard_take", take.bytes)
        val f = Smf.parse(take.bytes)
        assertEquals(1, f.format); assertEquals(960, f.ppq)
        assertEquals("conductor + RIGHT1 + RIGHT2 + LEFT", listOf("Take A", "RIGHT1", "RIGHT2", "LEFT"), f.tracks.map { it.name })
        assertTrue(f.tracks.all { it.endOfTrack }); assertTrue(f.tracks.all { it.events.last().tick == 3840L })   // 2.0 s at 120 BPM = 4 beats
        val cond = f.tracks[0].events
        assertTrue("tempo 120", cond.any { it.metaType == 0x51 && it.payload!!.toList() == listOf<Byte>(0x07, 0xA1.toByte(), 0x20) })
        assertTrue("4/4", cond.any { it.metaType == 0x58 && it.payload!![0].toInt() == 4 && it.payload[1].toInt() == 2 })
        val r1 = f.tracks[1].events.filter { it.isChannel }
        assertEquals("voice at tick 0: bank, LSB, program", listOf(Triple(0L, 0xB0, 0), Triple(0L, 0xB0, 32), Triple(0L, 0xC0, 0)),
            r1.take(3).map { Triple(it.tick, it.command, it.data1) })
        val on = r1.first { it.isNoteOn }; assertEquals(960L, on.tick); assertEquals(64, on.data1); assertEquals(100, on.data2)
        assertEquals("NOTE OFF at 1.0 s", 1920L, r1.first { it.isNoteOff }.tick)
        assertEquals("pedal down 0.75 s, up 1.5 s", listOf(1440L to 127, 2880L to 0), r1.filter { it.command == 0xB0 && it.data1 == 64 }.map { it.tick to it.data2 })
        val left = f.tracks[3].events.filter { it.isChannel }
        assertTrue("LEFT on channel 4 with its own voice", left.all { ch(it) == 3 } && left.any { it.command == 0xC0 && it.data1 == 33 } && left.any { it.command == 0xB0 && it.data1 == 0 && it.data2 == 32 })
        assertTrue("text names the SF2 preset", f.tracks[3].events.any { it.metaType == 0x01 && String(it.payload!!, Charsets.ISO_8859_1).contains("Finger Bass (sx.sf2 32:33)") })
        assertEquals(3, take.notes); assertEquals(setOf(0, 1, 3), take.channels); assertEquals(2_000_000L, take.durationUs)
        // played back by the SONG parser/timeline at the recorded times
        val t = SongTimeline.from(f)
        assertEquals(500_000L, t.events.first { it.isNoteOn && it.channel == 0 }.us)
        assertEquals(1_000_000L, t.events.first { it.isNoteOn && it.channel == 3 }.us)
        assertEquals("notes balanced", t.events.count { it.isNoteOn }, t.events.count { it.isNoteOff })
    }

    @Test fun stopInTheMiddleOfALongNoteClosesItAndThePedal() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        r.start(100.0, mapOf(), true, "Long")
        c.at(0.3); r.noteOn(0, 60, 0.7f); r.noteOn(0, 60, 0.7f)                    // the same pitch twice (retrigger): two instances
        c.at(1.2); val take = r.stop()!!
        save("v1b_stop_mid_note", take.bytes)
        val f = Smf.parse(take.bytes); val ev = f.tracks[1].events.filter { it.isChannel }
        val end = (1.2 * 100 / 60 * 960).toLong()
        assertEquals("two NOTE OFFs at the stop tick", listOf(end, end), ev.filter { it.isNoteOff }.map { it.tick })
        assertEquals("pedal pressed at start (SUSTAIN was ON), released at stop", listOf(0L to 127, end to 0), ev.filter { it.command == 0xB0 && it.data1 == 64 }.map { it.tick to it.data2 })
        assertTrue(f.tracks.all { it.endOfTrack })
    }

    @Test fun noOrphanNoteOffsAndPartStopClosesOnlyThatPart() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        r.start(120.0, mapOf(), false, "Orphans")
        c.at(0.1); r.noteOff(0, 50)                                                 // key held before RECORD: its NOTE OFF is not recorded
        c.at(0.2); r.noteOn(0, 60, 0.5f); r.noteOn(1, 60, 0.5f)
        c.at(0.4); r.channelStopped(1)                                               // RIGHT2 part OFF (no per-note NOTE OFF from the engine)
        c.at(0.6); r.noteOff(1, 60)                                                  // late engine NOTE OFF after the part stop: dropped
        c.at(0.8); r.noteOff(0, 60)
        c.at(1.0); val f = Smf.parse(r.stop()!!.bytes)
        val r1 = f.tracks[1].events.filter { it.isChannel && (it.isNoteOn || it.isNoteOff) }
        val r2 = f.tracks[2].events.filter { it.isChannel && (it.isNoteOn || it.isNoteOff) }
        assertEquals(listOf(384L to "on", 1536L to "off"), r1.map { it.tick to if (it.isNoteOn) "on" else "off" })
        assertEquals("RIGHT2 closed at the part stop", listOf(384L to "on", 768L to "off"), r2.map { it.tick to if (it.isNoteOn) "on" else "off" })
    }

    @Test fun tempoChangesBecomeATempoMapAndTicksFollowIt() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        r.start(120.0, mapOf(), false, "Tempo")
        c.at(1.0); r.tempo(60.0); r.tempo(60.0)                                      // a repeated value adds nothing
        c.at(2.0); r.noteOn(0, 60, 0.5f)
        c.at(3.0); r.noteOff(0, 60)
        c.at(3.5); val take = r.stop()!!
        save("v1b_tempo_change", take.bytes)
        val f = Smf.parse(take.bytes); val t = SongTimeline.from(f)
        assertEquals("tempos", listOf(0L to 500_000, 1920L to 1_000_000), t.tempos.map { it.tick to it.usPerQuarter })
        val on = t.events.first { it.isNoteOn }; assertEquals(1920L + 960, on.tick); assertEquals(2_000_000L, on.us)
        assertEquals(3_000_000L, t.events.first { it.isNoteOff }.us)
        assertEquals(1, take.tempoChanges); assertEquals(3_500_000L, take.durationUs); assertEquals(3_500_000L, t.durationUs)
    }

    @Test fun voiceChangeDuringTheTakeAndSnapshotIsValidWithoutChangingTheTake() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        r.start(120.0, mapOf(0 to piano), false, "Voices")
        c.at(0.5); r.noteOn(0, 60, 0.5f)
        c.at(0.7); val snap = r.snapshot()!!                                         // e.g. the periodic autosave while recording
        val sf = Smf.parse(snap.bytes); assertTrue(sf.tracks.all { it.endOfTrack })
        assertEquals("snapshot closes the open note at its own tick", 1344L, sf.tracks[1].events.last { it.isChannel && it.isNoteOff }.tick)
        c.at(1.0); r.noteOff(0, 60); r.voice(0, RecVoice(8, 48, "Strings", "tyros4.sf2"))
        c.at(1.5); r.noteOn(0, 67, 0.5f); c.at(2.0); r.noteOff(0, 67)
        val f = Smf.parse(r.stop()!!.bytes); val ev = f.tracks[1].events.filter { it.isChannel }
        assertEquals("the live NOTE OFF, not the snapshot's", listOf(1920L, 3840L), ev.filter { it.isNoteOff }.map { it.tick })
        assertEquals("voice change at 1.0 s: bank 8, program 48", listOf(Triple(1920L, 0xB0, 8), Triple(1920L, 0xC0, 48)),
            ev.filter { it.tick == 1920L && (it.command == 0xC0 || (it.command == 0xB0 && it.data1 == 0)) }.map { Triple(it.tick, it.command, if (it.command == 0xC0) it.data1 else it.data2) })
        assertFalse(r.isRecording); assertNull(r.snapshot())
    }

    @Test fun emptyTakeIsStillAValidFile() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        r.start(90.0, mapOf(0 to piano), false, "Empty"); c.at(1.0)
        val f = Smf.parse(r.stop()!!.bytes)
        assertEquals("conductor only", 1, f.tracks.size); assertTrue(f.tracks[0].endOfTrack)
        assertTrue(r.status().hasTake); assertFalse(r.status().recording)
    }

    // ---------------- V1C FULL ARRANGER ----------------
    private fun fullTake(): com.yourapp.yamahaarranger.song.RecordedTake {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        val voices = mapOf(0 to piano, 8 to RecVoice(127 * 128, 0, "Std Kit", bank14 = true), 9 to RecVoice(127 * 128, 8, "Room Kit", bank14 = true),
            10 to RecVoice(0 * 128 + 112, 33, "Finger Bass", bank14 = true), 11 to RecVoice(0 * 128 + 112, 27, "Clean Gtr", bank14 = true))
        r.start(120.0, voices, false, "Full", (0..15).toSet(), RecordMode.FULL, mapOf(10 to intArrayOf(7, 100, 10, 64, 11, 127, 91, 40, 93, 0)))
        c.at(0.0); r.noteOn(8, 36, 0.9f); r.noteOn(9, 42, 0.6f); r.noteOn(10, 36, 0.7f); r.noteOn(11, 60, 0.6f); r.noteOn(11, 64, 0.6f)
        c.at(0.1); r.noteOff(8, 36); r.noteOff(9, 42)
        c.at(0.25); r.sustain(true)                                                // keyboard pedal: keyboard parts only
        c.at(0.5); r.noteOn(0, 72, 0.8f); r.controllers(10, intArrayOf(7, 100, 11, 90)); r.controllers(10, intArrayOf(11, 90))   // only changes
        c.at(0.9); r.noteOff(10, 36); r.noteOff(11, 60)
        c.at(1.0); r.allStopped()                                                  // Style STOP: global All Notes Off
        c.at(1.1); r.noteOff(0, 72); r.noteOff(11, 64)                             // late offs after All Notes Off: dropped
        c.at(1.5); r.sustain(false)
        c.at(2.0); return r.stop()!!
    }

    @Test fun fullArrangerNativeTakeKeepsPartsYamahaBanksAndXgDrums() {
        val take = fullTake(); save("v1c_full_native", take.bytes); save("v1c_full_gm", take.bytesFor(ExportFlavor.GM))
        val f = Smf.parse(take.bytes); val t = SongTimeline.from(f)
        assertEquals(listOf("Full", "RIGHT1", "Rhythm1", "Rhythm2", "Bass", "Chord1"), f.tracks.map { it.name })
        assertTrue("XG System On forwarded to the synth", f.tracks[0].events.any { it.isSysEx && it.payload!!.toList().take(3) == listOf<Byte>(0x43, 0x10, 0x4C) })
        assertEquals("both Rhythm channels play as drums", setOf(8, 9), t.drumChannels.intersect(setOf(8, 9)))
        val bass = f.tracks[4].events.filter { it.isChannel }
        assertEquals("Yamaha bank 0/112 PC 33", listOf(0 to 0, 32 to 112), bass.filter { it.command == 0xB0 && it.data1 in listOf(0, 32) }.take(2).map { it.data1 to it.data2 })
        assertTrue(bass.any { it.command == 0xC0 && it.data1 == 33 })
        assertEquals("mixer at start + only real changes", listOf(0L to 127, 960L to 90), bass.filter { it.command == 0xB0 && it.data1 == 11 }.map { it.tick to it.data2 })
        assertEquals("CC7 not repeated", 1, bass.count { it.command == 0xB0 && it.data1 == 7 })
        val r1 = f.tracks[2].events.filter { it.isChannel }
        assertTrue("Rhythm1: bank MSB 127", r1.any { it.command == 0xB0 && it.data1 == 0 && it.data2 == 127 })
        assertTrue("pedal only on keyboard tracks", f.tracks.drop(2).none { tr -> tr.events.any { it.isChannel && it.command == 0xB0 && it.data1 == 64 } })
        assertEquals("All Notes Off closed RIGHT1, Bass, Chord1 at 1.0 s (1920)", listOf(1920L), f.tracks[1].events.filter { it.isNoteOff }.map { it.tick })
        assertEquals(listOf(1728L, 1920L), f.tracks[5].events.filter { it.isNoteOff }.map { it.tick }.sorted())
        assertEquals("balanced", t.events.count { it.isNoteOn }, t.events.count { it.isNoteOff })
    }

    @Test fun gmFlavourPutsBothRhythmPartsOnChannel10WithoutXg() {
        val take = fullTake(); val f = Smf.parse(take.bytesFor(ExportFlavor.GM)); val t = SongTimeline.from(f)
        assertTrue("GM System On, no XG", f.tracks[0].events.any { it.isSysEx && it.payload!![0] == 0x7E.toByte() } &&
            f.tracks[0].events.none { it.isSysEx && it.payload!![0] == 0x43.toByte() })
        assertTrue("no channel 9 events", t.events.none { !it.isSysEx && it.channel == 8 })
        assertEquals("both drum notes on channel 10", setOf(36, 42), t.events.filter { it.isNoteOn && it.channel == 9 }.map { it.data1 }.toSet())
        assertEquals("one kit on ch10 (Rhythm2's PC 8)", listOf(8), t.events.filter { !it.isSysEx && it.channel == 9 && it.command == 0xC0 }.map { it.data1 })
        assertTrue("melodic banks reset to 0, program kept", t.events.filter { !it.isSysEx && it.command == 0xB0 && it.data1 in listOf(0, 32) }.all { it.data2 == 0 } &&
            t.events.any { !it.isSysEx && it.channel == 10 && it.command == 0xC0 && it.data1 == 33 })
        assertEquals(listOf("Full", "RIGHT1", "Rhythm1 (GM ch10)", "Rhythm2 (GM ch10)", "Bass", "Chord1"), f.tracks.map { it.name })
        assertEquals("same notes as NATIVE", SongTimeline.from(Smf.parse(take.bytes)).events.count { it.isNoteOn }, t.events.count { it.isNoteOn })
    }

    // ---------------- V1C fix: YAMAP routes (the presets the live engine resolved) ----------------
    @Test fun nativeTakeCarriesYamapRoutesForEveryVoiceChangeAndGmDropsThem() {
        val c = Clock(); val r = MidiRecorder({ c.ns }, 960)
        val bassSrc = SrcPreset("Yamaha SX700 SX900 Melody.sf2", 8, 33)
        r.start(120.0, mapOf(10 to RecVoice(112, 33, "Finger Bass", bank14 = true, src = bassSrc), 9 to RecVoice(127 * 128, 8, "Room Kit", bank14 = true, src = SrcPreset("drums.sf2", 128, 8))),
            false, "Routes", (0..15).toSet(), RecordMode.FULL)
        c.at(0.5); r.noteOn(10, 36, 0.7f); r.noteOn(9, 38, 0.8f)
        c.at(1.0); r.noteOff(10, 36); r.noteOff(9, 38)
        r.voice(10, RecVoice(8 * 128 + 4, 35, "Fretless", bank14 = true, src = SrcPreset("tyros4.sf2", 0, 35)))   // section change: new bass voice
        c.at(1.5); r.noteOn(10, 41, 0.7f); c.at(2.0); r.noteOff(10, 41)
        val take = r.stop()!!; save("v1c_routes_native", take.bytes)
        val routes = YaVoiceMap.parse(Smf.parse(take.bytes))
        assertEquals("one route per voice: start bass, start kit, the section's new bass", setOf(
            YaVoiceMap.Route(10, 0, 112, 33, bassSrc), YaVoiceMap.Route(9, 127, 0, 8, SrcPreset("drums.sf2", 128, 8)),
            YaVoiceMap.Route(10, 8, 4, 35, SrcPreset("tyros4.sf2", 0, 35))), routes.toSet())
        assertTrue("GM export has no YA-only routes", YaVoiceMap.parse(Smf.parse(take.bytesFor(ExportFlavor.GM))).isEmpty())
        assertEquals("route text round trip", YaVoiceMap.Route(3, 1, 2, 3, SrcPreset("a b.sf2", 4, 5)), YaVoiceMap.parse(YaVoiceMap.text(3, 1, 2, 3, SrcPreset("a b.sf2", 4, 5))))
        assertNull(YaVoiceMap.parse("YAMAP ch=99 dst=0/0/0 src=0/0 sf2=x"))
    }
}
