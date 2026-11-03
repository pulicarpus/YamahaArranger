package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SmfException
import com.yourapp.yamahaarranger.song.SmfEvent
import com.yourapp.yamahaarranger.song.SongSysEx
import com.yourapp.yamahaarranger.song.SongVoice
import com.yourapp.yamahaarranger.song.SongVoiceBinder
import com.yourapp.yamahaarranger.song.SustainMode
import com.yourapp.yamahaarranger.song.VoiceBind
import com.yourapp.yamahaarranger.song.ChannelOverride
import com.yourapp.yamahaarranger.song.SongOutput
import com.yourapp.yamahaarranger.song.SongPlayer
import com.yourapp.yamahaarranger.song.SongState
import com.yourapp.yamahaarranger.song.SongTimeline
import com.yourapp.yamahaarranger.song.SongRouting
import com.yourapp.yamahaarranger.song.InternalSongOutput
import com.yourapp.yamahaarranger.song.ExternalSongOutput
import com.yourapp.yamahaarranger.song.GmNames
import com.yourapp.yamahaarranger.song.songOutputsFor
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * MIDI SONG V1A: parser + timeline + player, against fixtures written AND read back by an independent Python implementation
 * (tools/midi_fixture_gen.py -> tests/fixtures/midi/manifest.tsv). Player tests use an injected clock and recording outputs.
 */
class SongPlayerTest {
    private val dir = File("tests/fixtures/midi")
    private val manifest: Map<String, Map<String, String>> by lazy {
        File(dir, "manifest.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            .groupBy({ it[0] }, { it[1] to it[2] }).mapValues { it.value.toMap() }
    }
    private fun load(name: String) = Smf.parse(File(dir, "$name.mid").readBytes())
    private fun timeline(name: String) = SongTimeline.from(load(name))

    private class Rec(override val id: String, override val acceptsSysEx: Boolean = false) : SongOutput {
        val msgs = ArrayList<Pair<Long, ByteArray>>(); var now = 0L
        override fun send(bytes: ByteArray) { msgs += now to bytes.copyOf() }
        fun st(i: Int) = msgs[i].second[0].toInt() and 0xFF
        fun noteOns() = msgs.count { (it.second[0].toInt() and 0xF0) == 0x90 && it.second.size > 2 && it.second[2] > 0 }
        fun noteOffs() = msgs.count { val s = it.second[0].toInt() and 0xF0; s == 0x80 || (s == 0x90 && it.second[2].toInt() == 0) }
    }
    private class Clock { var ns = 1_000_000_000L }

    /** Play until [untilNs] (or the end), stepping exactly at the times the player asks for. */
    private fun run(p: SongPlayer, c: Clock, outs: List<Rec>, untilNs: Long = Long.MAX_VALUE, jitter: Random? = null): Long {
        var guard = 0
        while (guard++ < 1_000_000) {
            outs.forEach { it.now = c.ns }
            val nxt = p.step(c.ns)
            if (nxt < 0) break
            val target = maxOf(nxt, c.ns) + (jitter?.nextLong(0, 3_000_000) ?: 0)
            if (target > untilNs) { c.ns = untilNs; outs.forEach { it.now = c.ns }; p.step(c.ns); break }
            c.ns = target
        }
        return c.ns
    }

    // ---------------- parser vs the independent Python reader ----------------
    @Test fun goodFixturesMatchTheIndependentReader() {
        for (name in listOf("type0_basic", "type1_16ch")) {
            val m = manifest.getValue(name); val f = load(name); val t = SongTimeline.from(f)
            assertEquals(name, m["format"]!!.toInt(), f.format); assertEquals(name, m["ppq"]!!.toInt(), f.ppq)
            assertEquals(name, m["tracks"]!!.toInt(), f.tracks.size)
            assertEquals("$name channel events (incl. running status)", m["channelEvents"]!!.toInt(), t.channelEventCount)
            assertEquals(name, m["sysex"]!!.toInt(), t.sysExCount)
            assertEquals("$name: the GM System On is a forwardable setup SysEx", m["sysex"]!!.toInt(), t.sysExForwarded)
            val ons = IntArray(16); t.events.filter { it.isNoteOn }.forEach { ons[it.channel]++ }
            assertEquals(name, m["noteOns"], ons.joinToString(","))
            assertEquals(name, m["tempos"], t.tempos.joinToString(",") { "${it.tick}:${it.usPerQuarter}" })
            assertEquals(name, m["timeSigs"], t.timeSigs.joinToString(",") { "${it.tick}:${it.numerator}/${it.denominator}" })
            assertEquals(name, m["lastTick"]!!.toLong(), t.lastTick)
            assertEquals(name, m["durationUs"]!!.toLong(), t.durationUs)
            assertTrue("$name: every track has End of Track", f.tracks.all { it.endOfTrack })
        }
    }

    @Test fun damagedFilesFailWithAClearMessageAndNeverCrash() {
        val expect = mapOf("bad_truncated" to "truncated", "bad_type2" to "Type 2", "bad_vlq" to "longer than 4 bytes",
            "bad_no_status" to "without status", "bad_not_midi" to "MThd")
        for ((name, word) in expect) {
            assertTrue("python reader also rejects $name", manifest.getValue(name).containsKey("error"))
            val e = try { load(name); null } catch (x: SmfException) { x }
            assertNotNull("$name must be rejected", e); assertTrue("$name: '${e!!.message}' mentions '$word'", e.message!!.contains(word))
        }
        assertTrue(try { Smf.parse(ByteArray(0)); false } catch (e: SmfException) { true })
        assertTrue("SMPTE division rejected", try { Smf.parse(smfBytes(0, 0xE728, listOf(track(listOf(0L to byteArrayOf(0x90.toByte(), 60, 90)))))); false } catch (e: SmfException) { e.message!!.contains("SMPTE") })
    }

    // ---------------- timeline ----------------
    @Test fun tempoMapTimeSignatureAndBarBeat() {
        val t = timeline("type1_16ch")
        assertEquals("100 BPM: 7680 ticks at PPQ 960", 7680L * 600_000 / 960, t.usAt(7680))
        assertEquals(140.0, t.tempoAt(8000).bpm, 0.01)
        assertEquals("bar 5 = first 3/4 bar", 5, t.barBeat(15360).bar); assertEquals(1, t.barBeat(15360).beat)
        assertEquals("3/4: bar 6 starts 3 beats later", 6, t.barBeat(15360 + 3 * 960).bar)
        assertEquals(15360L + 3 * 960, t.tickOfBar(6)); assertEquals(3, t.barBeat(15360 + 2 * 960).beat)
        assertEquals(7680L, t.tickAt(t.usAt(7680)))
        assertEquals("ch 10 named from its track", "Drums", t.channelNames[9])
    }

    @Test fun chaseHasStateButNeverANote() {
        val t = timeline("type1_16ch")
        val chase = t.chaseBefore(2500)
        assertTrue(chase.none { (it[0].toInt() and 0xF0) == 0x90 || (it[0].toInt() and 0xF0) == 0x80 })
        assertTrue("ch1 sustain on (CC64 at tick 960)", chase.any { (it[0].toInt() and 0xFF) == 0xB0 && it[1].toInt() == 64 && it[2].toInt() == 127 })
        assertTrue("ch2 last pitch bend back to centre", chase.any { (it[0].toInt() and 0xFF) == 0xE1 && it[2].toInt() == 0x40 })
        val ch10 = chase.filter { (it[0].toInt() and 0x0F) == 9 }.map { (it[0].toInt() and 0xF0) to it[1].toInt() }
        assertEquals("bank MSB, bank LSB, program in that order", listOf(0xB0 to 0, 0xB0 to 32, 0xC0 to 63), ch10.take(3))
    }

    @Test fun noteOffSortsBeforeNoteOnAtTheSameTickAcrossTracks() {
        // track 0 starts C4 at 0 and a new C4 at 480; track 1 ends the first C4 at 480 -> the new note must survive
        val b = smfBytes(1, 480, listOf(track(listOf(0L to on(0, 60), 480L to on(0, 60), 960L to off(0, 60))), track(listOf(480L to off(0, 60)))))
        val t = SongTimeline.from(Smf.parse(b))
        assertEquals(listOf("on", "off", "on", "off"), t.events.map { if (it.isNoteOn) "on" else "off" })
    }

    // ---------------- player ----------------
    @Test fun fullPlayBothOutputsGetEveryEventExactlyOnceInOrderAndOnTime() {
        val t = timeline("type1_16ch"); val c = Clock(); val a = Rec("internal", acceptsSysEx = true); val b = Rec("external")
        val p = SongPlayer { c.ns }; p.load(t, "type1"); p.setOutputs(listOf(a, b))
        val start = c.ns; assertNull(p.play()); run(p, c, listOf(a, b))
        assertEquals(SongState.STOPPED, p.status(c.ns).state); assertEquals("End of song", p.status(c.ns).message)
        assertEquals("internal synth reset (GM System On) before the song", SongSysEx.GM_SYSTEM_ON.toList(), a.msgs[0].second.toList())
        val played = a.msgs.drop(1)
        assertEquals("internal: every event (channel + setup SysEx) once", t.events.size, played.size)
        assertEquals("external: every channel event once", t.channelEventCount, b.msgs.size)
        assertEquals("external = internal without SysEx", played.map { it.second }.filter { (it[0].toInt() and 0xFF) < 0xF0 }.map { it.toList() }, b.msgs.map { it.second.toList() })
        assertEquals("MIDI OUT never gets SysEx", 0, b.msgs.count { (it.second[0].toInt() and 0xFF) >= 0xF0 })
        assertEquals("the file's GM System On reached the internal synth", 1, played.count { (it.second[0].toInt() and 0xFF) == 0xF0 })
        assertEquals("notes balanced", a.noteOns(), a.noteOffs()); assertEquals(0, p.activeNotes())
        for ((i, e) in t.events.withIndex()) assertEquals("event $i on time", start + e.us * 1000, played[i].first)
        for (ch in 0 until 16) {
            val seq = a.msgs.map { it.second }.filter { (it[0].toInt() and 0x0F) == ch && ((it[0].toInt() and 0xF0) == 0xC0 || ((it[0].toInt() and 0xF0) == 0xB0 && it[1].toInt() in listOf(0, 32))) }
            assertEquals("ch${ch + 1} bank MSB -> LSB -> program", listOf(0xB0 to 0, 0xB0 to 32, 0xC0 to -1), seq.map { (it[0].toInt() and 0xF0) to (if (it.size > 2) it[1].toInt() else -1) })
        }
    }

    @Test fun pauseEndsOnlySongNotesAndResumeRestoresStateWithoutNotes() {
        val t = timeline("type1_16ch"); val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }
        p.load(t, "x"); p.setOutputs(listOf(a)); p.play()
        val t0 = c.ns
        run(p, c, listOf(a), untilNs = t0 + 300_000_000L)               // 0.3 s: the 16 notes of bar 1 are sounding
        val sounding = p.activeNotes(); assertEquals(16, sounding)
        var before = a.msgs.size; p.pause()
        var panic = a.msgs.drop(before).map { it.second }
        assertEquals("one NOTE OFF per sounding note", sounding, panic.count { (it[0].toInt() and 0xF0) == 0x80 })
        assertTrue("no All Notes Off / reset", panic.none { (it[0].toInt() and 0xF0) == 0xB0 && it[1].toInt() >= 120 })
        assertEquals(0, p.activeNotes())
        p.play(); run(p, c, listOf(a), untilNs = c.ns + 400_000_000L)    // song time 0.7 s: ch1 sustain is on (0.6..1.8 s)
        before = a.msgs.size; p.pause(); panic = a.msgs.drop(before).map { it.second }
        assertTrue("sustain released on ch1", panic.any { (it[0].toInt() and 0xFF) == 0xB0 && it[1].toInt() == 64 && it[2].toInt() == 0 })
        val pos = p.status(c.ns).positionUs; assertEquals(700_000L, pos)
        c.ns += 5_000_000_000L; assertEquals("position frozen while paused", pos, p.status(c.ns).positionUs)
        val b2 = a.msgs.size; p.play()
        val chase = a.msgs.drop(b2).map { it.second }
        assertTrue("resume sends state", chase.any { (it[0].toInt() and 0xF0) == 0xC0 }); assertTrue("but no NOTE ON", chase.none { (it[0].toInt() and 0xF0) == 0x90 })
        run(p, c, listOf(a)); assertEquals(SongState.STOPPED, p.status(c.ns).state); assertEquals(0, p.activeNotes())
        assertEquals("all NOTE ON were ended", a.noteOns(), a.noteOffs())
    }

    @Test fun seekJumpsWithStateAndOnlyLaterEvents() {
        val t = timeline("type1_16ch"); val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }
        p.load(t, "x"); p.setOutputs(listOf(a)); p.play(); run(p, c, listOf(a), untilNs = c.ns + 1_000_000_000L)
        val target = t.tickOfBar(5); val before = a.msgs.size
        p.seekTick(target)
        assertEquals(0, p.activeNotes()); assertEquals(5, p.status(c.ns).barBeat!!.bar)
        val after = run(p, c, listOf(a))
        val played = a.msgs.drop(before).map { it.second }.filter { (it[0].toInt() and 0xF0) == 0x90 && it[2] > 0 }
        assertEquals("only notes from bar 5 on (2 bars x 16 ch)", 2 * 16, played.size)
        assertTrue(after > 0); assertEquals(a.noteOns(), a.noteOffs())
    }

    @Test fun muteEndsThatChannelOnlyAndNeverSendsAnOrphanNoteOff() {
        val t = timeline("type0_basic"); val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }
        p.load(t, "x"); p.setOutputs(listOf(a)); p.play()
        run(p, c, listOf(a), untilNs = c.ns + 100_000_000L)                // first note (C4) sounding
        assertEquals(1, p.activeNotes()); val before = a.msgs.size
        p.setMute(0, true)
        assertEquals(listOf(0x80), a.msgs.drop(before).map { it.second[0].toInt() and 0xF0 }); assertEquals(0, p.activeNotes())
        run(p, c, listOf(a), untilNs = c.ns + 1_100_000_000L)              // its NOTE OFF and the next note pass while muted
        val muted = a.msgs.drop(before + 1)
        assertTrue("nothing note-related sent while muted", muted.none { val s = it.second[0].toInt() and 0xF0; s == 0x80 || s == 0x90 })
        p.setMute(0, false); run(p, c, listOf(a))
        assertEquals("balanced after unmute", a.noteOns(), a.noteOffs())
    }

    @Test fun stopEndsNotesSustainAndPitchBendOfTheSongOnly() {
        val t = timeline("type1_16ch"); val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }
        var stopped = 0; p.onStopped = { stopped++ }
        p.load(t, "x"); p.setOutputs(listOf(a)); p.play(); run(p, c, listOf(a), untilNs = c.ns + 1_000_000_000L)   // ch2 bent (0.625..1.25 s), ch1 sustained
        val sounding = p.activeNotes(); val before = a.msgs.size
        p.stop()
        val tail = a.msgs.drop(before).map { it.second }
        assertEquals(sounding, tail.count { (it[0].toInt() and 0xF0) == 0x80 })
        assertTrue("pitch bend back to centre", tail.any { (it[0].toInt() and 0xFF) == 0xE1 && it[1].toInt() == 0 && it[2].toInt() == 0x40 })
        assertTrue("sustain released", tail.any { (it[0].toInt() and 0xFF) == 0xB0 && it[1].toInt() == 64 && it[2].toInt() == 0 })
        assertTrue(tail.none { (it[0].toInt() and 0xF0) == 0xB0 && it[1].toInt() >= 120 })
        assertEquals(1, stopped); assertEquals(0L, p.status(c.ns).positionUs); assertEquals(SongState.STOPPED, p.status(c.ns).state)
    }

    @Test fun removingAnOutputWhilePlayingEndsItsNotesThereOnly() {
        val t = timeline("type0_basic"); val c = Clock(); val a = Rec("internal"); val b = Rec("external"); val p = SongPlayer { c.ns }
        p.load(t, "x"); p.setOutputs(listOf(a, b)); p.play(); run(p, c, listOf(a, b), untilNs = c.ns + 100_000_000L)
        val na = a.msgs.size; val nb = b.msgs.size
        p.setOutputs(listOf(a))
        assertEquals("internal untouched", na, a.msgs.size)
        assertEquals(listOf(0x80), b.msgs.drop(nb).map { it.second[0].toInt() and 0xF0 })
        run(p, c, listOf(a, b)); assertEquals(a.noteOns(), a.noteOffs())
    }

    @Test fun tempoOverrideIsContinuousAndScalesTime() {
        val t = timeline("type0_basic"); val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }
        p.load(t, "x"); p.setOutputs(listOf(a)); p.play(); run(p, c, listOf(a), untilNs = c.ns + 1_000_000_000L)
        val pos = p.status(c.ns).positionUs
        p.setTempoPercent(50); assertEquals("no jump", pos, p.status(c.ns).positionUs)
        c.ns += 1_000_000_000L; assertEquals("half speed", pos + 500_000, p.status(c.ns).positionUs)
        assertEquals(60.0, p.status(c.ns).effectiveBpm, 0.01)
    }

    @Test fun longSongHasNoDriftEvenWithLateWakeUps() {
        // 10 minutes, one note per beat at 120 BPM (PPQ 480), played with up to 3 ms random lateness per wake-up
        val ev = ArrayList<Pair<Long, ByteArray>>()
        for (i in 0 until 1200) { ev += (i * 480L) to on(0, 60 + i % 12); ev += (i * 480L + 240) to off(0, 60 + i % 12) }
        val t = SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev)))))
        assertEquals(599_750_000L, t.durationUs)
        val c = Clock(); val a = Rec("i"); val p = SongPlayer { c.ns }; p.load(t, "long"); p.setOutputs(listOf(a))
        val start = c.ns; p.play(); run(p, c, listOf(a), jitter = Random(7))
        var maxLate = 0L
        for ((i, e) in t.events.withIndex()) { val late = a.msgs[i].first - (start + e.us * 1000); assertTrue("never early", late >= 0); maxLate = maxOf(maxLate, late) }
        assertTrue("lateness bounded by one wake-up (no accumulation): ${maxLate / 1e6} ms", maxLate <= 3_000_000)
        println("SONGTEST long song: ${t.events.size} events over ${t.durationUs / 1e6} s, max lateness ${maxLate / 1e6} ms")
    }

    @Test fun routingSendsEachEventOncePerSelectedOutput() {
        val t = timeline("type0_basic")
        val internal = ArrayList<ByteArray>(); val external = ArrayList<ByteArray>()
        val i = InternalSongOutput({ internal += it; true }); val e = ExternalSongOutput { external += it; false }   // MIDI OUT refusing (off) must not matter
        for ((r, expI, expE) in listOf(Triple(SongRouting.INTERNAL, 1, 0), Triple(SongRouting.EXTERNAL, 0, 1), Triple(SongRouting.BOTH, 1, 1))) {
            internal.clear(); external.clear()
            val c = Clock(); val p = SongPlayer { c.ns }; p.load(t, "x"); p.setOutputs(songOutputsFor(r, i, e)); p.play()
            var g = 0; while (g++ < 100_000) { val n = p.step(c.ns); if (n < 0) break; c.ns = maxOf(n, c.ns) }
            assertEquals("$r internal", expI * t.channelEventCount, internal.size); assertEquals("$r external", expE * t.channelEventCount, external.size)
        }
        assertEquals("Drums (kit 1)", GmNames.name(9, 0)); assertEquals("Acoustic Grand Piano", GmNames.name(0, 0)); assertEquals("Drums (kit 26)", GmNames.name(3, 25, 127))
    }

    // ---------------- V1A.1: XG / GS / GM setup SysEx, internal synth only ----------------
    private fun sx(vararg b: Int) = byteArrayOf(0xF0.toByte(), (b.size).toByte()) + b.map { it.toByte() }.toByteArray()   // SMF: F0 <len> payload(.. F7)
    private fun cc(ch: Int, n: Int, v: Int) = byteArrayOf((0xB0 or ch).toByte(), n.toByte(), v.toByte())
    private fun pc(ch: Int, v: Int) = byteArrayOf((0xC0 or ch).toByte(), v.toByte())

    @Test fun setupSysExWhitelistMatchesTheSynthFamiliesOnly() {
        fun ok(vararg payload: Int) = SongSysEx.forInternal(SmfEvent(0, 0, 0, 0xF0, 0, 0, -1, payload.map { it.toByte() }.toByteArray())) != null
        assertTrue("GM System On", ok(0x7E, 0x7F, 0x09, 0x01, 0xF7))
        assertTrue("XG System On", ok(0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7))
        assertTrue("XG part mode", ok(0x43, 0x10, 0x4C, 0x08, 0x08, 0x07, 0x03, 0xF7))
        assertTrue("GS reset", ok(0x41, 0x10, 0x42, 0x12, 0x40, 0x00, 0x7F, 0x00, 0x41, 0xF7))
        assertTrue("master volume", ok(0x7F, 0x7F, 0x04, 0x01, 0x00, 0x7F, 0xF7))
        assertFalse("Yamaha style section control", ok(0x43, 0x7E, 0x00, 0x08, 0x7F, 0xF7))
        assertFalse("Yamaha instrument-private", ok(0x43, 0x73, 0x01, 0x51, 0x05, 0x00, 0x03, 0xF7))
        assertFalse("other manufacturer", ok(0x00, 0x20, 0x33, 0x01, 0x00, 0xF7))
        assertFalse("split packet (no F7)", ok(0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00))
        assertFalse("data byte > 0x7F", ok(0x43, 0x10, 0x4C, 0x08, 0x88, 0x07, 0x03, 0xF7))
        assertFalse("F7 escape event", SongSysEx.forInternal(SmfEvent(0, 0, 0, 0xF7, 0, 0, -1, byteArrayOf(0x43, 0x10, 0x4C, 0, 0, 0x7E, 0, 0xF7.toByte()))) != null)
    }

    @Test fun xgSetupGoesOnlyToTheInternalSynthInFileOrderAndIsReplayedOnSeek() {
        // a PSR-style XG file: XG On, ch9 = drum part, MW vibrato depth 0 on ch1, a style control + a private message (dropped), then voices and notes
        val xgOn = sx(0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7); val part = sx(0x43, 0x10, 0x4C, 0x08, 0x08, 0x07, 0x03, 0xF7)
        val vib = sx(0x43, 0x10, 0x4C, 0x08, 0x00, 0x20, 0x00, 0xF7)
        val ev = listOf(0L to sx(0x7E, 0x7F, 0x09, 0x01, 0xF7), 480L to xgOn, 480L to part, 480L to vib, 480L to sx(0x43, 0x7E, 0x00, 0x08, 0x7F, 0xF7),
            480L to sx(0x43, 0x73, 0x01, 0x51, 0x05, 0xF7), 960L to cc(8, 0, 127), 960L to pc(8, 58), 960L to cc(0, 1, 82), 960L to pc(0, 0)) +
            (0 until 8).flatMap { listOf(1920L + it * 480 to on(0, 60), 1920L + it * 480 + 400 to off(0, 60), 1920L + it * 480 to on(8, 36), 1920L + it * 480 + 100 to off(8, 36)) }
        val t = SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev)))))
        assertEquals(6, t.sysExCount); assertEquals("GM On + XG On + part mode + vibrato depth", 4, t.sysExForwarded)
        val c = Clock(); val a = Rec("internal", acceptsSysEx = true); val b = Rec("external"); val p = SongPlayer { c.ns }
        p.load(t, "xg"); p.setOutputs(listOf(a, b)); p.play(); run(p, c, listOf(a, b), untilNs = c.ns + 2_500_000_000L)
        val sys = a.msgs.map { it.second }.filter { (it[0].toInt() and 0xFF) == 0xF0 }.map { it.toList() }
        val wire = { m: ByteArray -> (byteArrayOf(0xF0.toByte()) + m.copyOfRange(2, m.size)).toList() }
        assertEquals("reset, then the file's setup in order", listOf(SongSysEx.GM_SYSTEM_ON.toList(), wire(ev[0].second), wire(xgOn), wire(part), wire(vib)), sys)
        val firstVoice = a.msgs.indexOfFirst { (it.second[0].toInt() and 0xF0) == 0xC0 }; val lastSys = a.msgs.indexOfLast { (it.second[0].toInt() and 0xFF) == 0xF0 }
        assertTrue("setup before the voices", lastSys < firstVoice)
        assertEquals("MIDI OUT never gets SysEx", 0, b.msgs.count { (it.second[0].toInt() and 0xFF) >= 0xF0 })
        // seek while playing: internal = notes ended, reset, setup again, then the channel chase; external = notes ended + chase only
        a.msgs.clear(); b.msgs.clear(); p.seekTick(1920L + 5 * 480)
        val after = a.msgs.map { it.second }
        val r = after.indexOfFirst { it.toList() == SongSysEx.GM_SYSTEM_ON.toList() }
        assertTrue("reset after the Song's NOTE OFFs", r >= 0 && after.take(r).all { (it[0].toInt() and 0xF0) == 0x80 || (it[0].toInt() and 0xF0) == 0xB0 })
        assertEquals("setup replayed", listOf(wire(ev[0].second), wire(xgOn), wire(part), wire(vib)), after.drop(r + 1).take(4).map { it.toList() })
        assertTrue("then ch9 bank 127 / program 58 chased", after.drop(r + 5).map { it.toList() }.containsAll(listOf(cc(8, 0, 127).toList(), pc(8, 58).toList())))
        assertEquals("MIDI OUT: still no SysEx after seek", 0, b.msgs.count { (it.second[0].toInt() and 0xFF) >= 0xF0 })
        assertTrue("MIDI OUT got the chase", b.msgs.any { it.second.toList() == pc(8, 58).toList() })
        p.stop(); assertEquals(0, p.activeNotes())
        // the next song starts from a clean internal synth
        a.msgs.clear(); p.load(timeline("type0_basic"), "next"); p.play()
        assertEquals("new song: GM System On first", SongSysEx.GM_SYSTEM_ON.toList(), a.msgs.first().second.toList())
    }

    // ---------------- V1A.2 SONG MIXER ----------------
    // PSR-like song: ch3 strings (bank 0/117 PC 49, CC7 71, CC10 40, CC64 pedal), a mid-song PC change, ch10 drums (bank 127 PC 58), ch1 piano
    private fun mixSong(): SongTimeline {
        val ev = ArrayList<Pair<Long, ByteArray>>()
        ev += 0L to cc(2, 0, 0); ev += 0L to cc(2, 32, 117); ev += 0L to pc(2, 49); ev += 0L to cc(2, 7, 71); ev += 0L to cc(2, 10, 40)
        ev += 0L to cc(9, 0, 127); ev += 0L to pc(9, 58); ev += 0L to pc(0, 0)
        for (b in 0 until 8) {
            val t = 480L + b * 960
            ev += t to cc(2, 64, 127); ev += t to on(2, 60); ev += (t + 400) to off(2, 60); ev += (t + 900) to cc(2, 64, 0)
            ev += t to on(9, 36); ev += (t + 100) to off(9, 36); ev += t to on(0, 72); ev += (t + 300) to off(0, 72)
        }
        ev += 4320L to pc(2, 50)                                                  // the file changes the strings voice mid-song
        return SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev)))))
    }
    private val strings = SongVoice(7, "tyros4.sf2", 0, 44, "Tremolo Strings")
    private val kit = SongVoice(9, "drums.sf2", 128, 25, "Analog Kit")
    private class Binder : SongVoiceBinder { val calls = ArrayList<Pair<Int, SongVoice?>>(); var answer = VoiceBind.VERIFIED
        override fun bind(channel: Int, voice: SongVoice?): VoiceBind { calls += channel to voice; return if (voice == null) VoiceBind.CLEARED else answer } }
    private fun ByteArray.k() = toList()
    private fun msgs(r: Rec, ch: Int) = r.msgs.map { it.second }.filter { (it[0].toInt() and 0xFF) < 0xF0 && (it[0].toInt() and 0x0F) == ch }
    private fun cmd(m: ByteArray) = m[0].toInt() and 0xF0
    private fun setup(): Triple<SongPlayer, Pair<Rec, Rec>, Pair<Clock, Binder>> {
        val c = Clock(); val i = Rec("internal", acceptsSysEx = true); val x = Rec("external"); val b = Binder()
        val p = SongPlayer { c.ns }; p.voiceBinder = b; p.load(mixSong(), "mix.mid"); p.setOutputs(listOf(i, x))
        return Triple(p, i to x, c to b)
    }

    @Test fun voiceOverrideReachesOnlyTheInternalSynthAndTheFileCannotUndoIt() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, b) = cb
        assertEquals(VoiceBind.VERIFIED, p.setVoice(2, strings)); assertEquals(listOf(2 to strings), b.calls)
        p.play(); run(p, c, listOf(i, x))
        val ip = msgs(i, 2).filter { cmd(it) == 0xC0 || (cmd(it) == 0xB0 && it[1].toInt() in listOf(0, 32)) }.map { it.k() }
        assertTrue("internal never receives the file's bank 0/117 or PC 49/50 on CH3", ip.none { it == pc(2, 49).k() || it == pc(2, 50).k() || it == cc(2, 32, 117).k() })
        assertTrue("internal selects the user preset (Bank 0 / LSB 0 / PC 0)", ip.containsAll(listOf(cc(2, 0, 0).k(), cc(2, 32, 0).k(), pc(2, 0).k())))
        val xp = msgs(x, 2).filter { cmd(it) == 0xC0 }.map { it.k() }
        assertEquals("MIDI OUT keeps the file's voices", listOf(pc(2, 49).k(), pc(2, 50).k()), xp)
        assertEquals("file voice still tracked for display", 50, p.status(c.ns).programs[2])
        assertEquals(strings, p.status(c.ns).overrides[2].voice)
    }

    @Test fun drumKitOverrideKeepsTheFilesDrumBankAndReplacesOnlyTheProgram() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
        p.setVoice(9, kit); p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 400_000_000L)
        val m = msgs(i, 9).filter { cmd(it) != 0x90 && cmd(it) != 0x80 }.map { it.k() }
        assertTrue("file bank 127 still reaches the synth (XG drum mode)", m.contains(cc(9, 0, 127).k()))
        assertTrue("kit = Program 0 of the pinned drum bank", m.contains(pc(9, 0).k()) && !m.contains(pc(9, 58).k()))
        assertTrue("MIDI OUT: file kit", msgs(x, 9).any { it.k() == pc(9, 58).k() })
        assertEquals(setOf(9), p.status(c.ns).drumChannels)
    }

    @Test fun overridesSurviveSeekPauseResumeRepeatAndASetupReset() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
        p.setVoice(2, strings); p.setVolume(2, 30); p.setSustainMode(2, SustainMode.IGNORE)
        p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 1_500_000_000L)
        fun checkState(what: String) {
            val m = msgs(i, 2).map { it.k() }
            assertTrue("$what: voice re-asserted", m.containsAll(listOf(cc(2, 0, 0).k(), cc(2, 32, 0).k(), pc(2, 0).k())))
            assertTrue("$what: volume re-asserted", m.contains(cc(2, 7, 30).k()) && !m.contains(cc(2, 7, 71).k()))
            assertTrue("$what: no file voice", !m.contains(pc(2, 49).k()) && !m.contains(pc(2, 50).k()))
            assertTrue("$what: no pedal ON", !m.contains(cc(2, 64, 127).k()))
        }
        i.msgs.clear(); p.seekTick(3000); checkState("seek forward")
        i.msgs.clear(); p.seekTick(500); checkState("seek back")
        p.pause(); i.msgs.clear(); p.play(); checkState("resume")
        run(p, c, listOf(i, x)); assertEquals(SongState.STOPPED, p.status(c.ns).state)
        i.msgs.clear(); p.play(); checkState("repeat from the start")
        val first = i.msgs.indexOfFirst { it.second.k() == SongSysEx.GM_SYSTEM_ON.k() }
        assertTrue("repeat: synth reset first, overrides right after it", first == 0 && i.msgs.drop(1).take(6).map { it.second.k() }.contains(cc(2, 7, 30).k()))
        // a setup SysEx in the file (XG System On) re-asserts the overrides right after it
        val ev = listOf(0L to pc(2, 49), 0L to cc(2, 7, 71), 960L to sx(0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7), 960L to on(2, 60), 1200L to off(2, 60))
        val c2 = Clock(); val i2 = Rec("internal", acceptsSysEx = true); val p2 = SongPlayer { c2.ns }
        p2.load(SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev))))), "xg.mid"); p2.setOutputs(listOf(i2)); p2.setVolume(2, 30); p2.setVoice(2, strings)
        p2.play(); run(p2, c2, listOf(i2))
        val all = i2.msgs.map { it.second.k() }; val xg = all.indexOfFirst { it.size > 4 && it[1] == 0x43.toByte() }
        assertTrue("after XG System On: volume and voice again", all.drop(xg + 1).take(5).containsAll(listOf(cc(2, 7, 30).k(), pc(2, 0).k())))
    }

    @Test fun volumeAndPanOverridesReplaceTheFileValuesOnEveryOutputAndMidiRestoresThem() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
        p.setVolume(2, 30); p.setPan(2, 100); p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 300_000_000L)
        for (r in listOf(i, x)) {
            val m = msgs(r, 2).map { it.k() }
            assertTrue("${r.id}: CC7 30 / CC10 100, never the file's 71 / 40", m.contains(cc(2, 7, 30).k()) && m.contains(cc(2, 10, 100).k()) &&
                !m.contains(cc(2, 7, 71).k()) && !m.contains(cc(2, 10, 40).k()))
        }
        assertEquals(71, p.status(c.ns).fileVolume[2]); assertEquals(40, p.status(c.ns).filePan[2])
        i.msgs.clear(); x.msgs.clear(); p.setVolume(2, null); p.setPan(2, null)
        assertEquals("MIDI: back to the file's values", listOf(cc(2, 7, 71).k(), cc(2, 10, 40).k()), msgs(x, 2).map { it.k() })
        p.setVolume(2, 200); assertEquals("clamped", 127, p.status(c.ns).overrides[2].volume)
        // stopped: a mixer change only reaches the internal synth (never the external module's channels)
        p.stop(); i.msgs.clear(); x.msgs.clear(); p.setVolume(5, 90)
        assertEquals(listOf(cc(5, 7, 90).k()), msgs(i, 5).map { it.k() }); assertTrue(x.msgs.isEmpty())
    }

    @Test fun soloSilencesOtherChannelsAndEndsOnlyTheirSongNotes() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
        p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 1_050_000_000L)       // inside bar 2: ch1 note sounding
        val sounding0 = msgs(i, 0).count { cmd(it) == 0x90 } - msgs(i, 0).count { cmd(it) == 0x80 }
        i.msgs.clear(); p.setSolo(2, true)
        assertEquals("ch1's sounding note ended once", sounding0, msgs(i, 0).count { cmd(it) == 0x80 })
        assertTrue(p.status(c.ns).silent[0] && p.status(c.ns).silent[9] && !p.status(c.ns).silent[2])
        i.msgs.clear(); run(p, c, listOf(i, x), untilNs = c.ns + 2_000_000_000L)
        assertEquals("no new notes on silenced channels", 0, msgs(i, 0).count { cmd(it) == 0x90 } + msgs(i, 9).count { cmd(it) == 0x90 })
        assertTrue("the soloed channel keeps playing", msgs(i, 2).any { cmd(it) == 0x90 })
        p.setSolo(2, false); i.msgs.clear(); run(p, c, listOf(i, x), untilNs = c.ns + 1_000_000_000L)
        assertTrue("un-solo: everyone again", msgs(i, 0).any { cmd(it) == 0x90 })
        p.stop(); assertEquals(0, p.activeNotes())
        assertEquals("balanced", i.noteOns(), i.noteOffs())
    }

    @Test fun sustainModesFilterOnlyCc64AndNoteOffsStillPlay() {
        for (mode in SustainMode.values()) {
            val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
            p.setSustainMode(2, mode); p.play(); run(p, c, listOf(i, x))
            for (r in listOf(i, x)) {
                val ped = msgs(r, 2).filter { cmd(it) == 0xB0 && it[1].toInt() == 64 }.map { it[2].toInt() }
                when (mode) {
                    SustainMode.FOLLOW -> assertTrue("$mode ${r.id}: file pedal played", ped.contains(127))
                    SustainMode.IGNORE -> assertTrue("$mode ${r.id}: no file pedal at all (only the mixer's own releases) $ped", ped.none { it > 0 } && ped.size < 4)
                    SustainMode.FORCE_OFF -> assertTrue("$mode ${r.id}: every file pedal turned into OFF", ped.isNotEmpty() && ped.all { it == 0 } && ped.size >= 16)
                }
                assertEquals("$mode ${r.id}: every NOTE OFF played", msgs(r, 2).count { cmd(it) == 0x90 }, msgs(r, 2).count { cmd(it) == 0x80 })
                assertTrue("$mode ${r.id}: never All Notes Off / All Sound Off", r.msgs.none { (it.second[0].toInt() and 0xF0) == 0xB0 && it.second[1].toInt() in 120..127 })
            }
            assertEquals(0, p.activeNotes())
        }
        // switching while the pedal is down releases it right away (only that channel)
        val (p, io, cb) = setup(); val (i, x) = io; val (c, _) = cb
        p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 600_000_000L)                // pedal pressed at tick 480 (0.5 s)
        i.msgs.clear(); p.setSustainMode(2, SustainMode.IGNORE)
        assertEquals(listOf(cc(2, 64, 0).k()), i.msgs.map { it.second.k() })
        assertEquals("file pedal still shown", 127, p.status(c.ns).filePedal[2])
    }

    @Test fun resetChannelRestoresTheFileAndANewSongStartsCleanWhileReopeningRestores() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, b) = cb
        p.setVoice(2, strings); p.setVolume(2, 30); p.setPan(2, 0); p.setSustainMode(2, SustainMode.FORCE_OFF); p.setMute(2, true)
        p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 600_000_000L)
        i.msgs.clear(); x.msgs.clear(); p.resetChannel(2)
        val m = msgs(i, 2).map { it.k() }
        assertTrue("file voice back (bank 0/117 PC 49)", m.containsAll(listOf(cc(2, 0, 0).k(), cc(2, 32, 117).k(), pc(2, 49).k())))
        assertTrue("file volume / pan / pedal back", m.containsAll(listOf(cc(2, 7, 71).k(), cc(2, 10, 40).k(), cc(2, 64, 127).k())))
        assertEquals(2 to null, b.calls.last()); assertEquals(ChannelOverride(), p.status(c.ns).overrides[2])
        assertFalse(p.status(c.ns).muted[2])
        // per song: another file starts clean, reopening the first one restores its overrides
        p.setVoice(2, strings); p.setVolume(2, 30); b.calls.clear()
        p.load(timeline("type0_basic"), "other.mid")
        assertEquals("new song: the user voice is unbound", listOf(2 to null), b.calls); assertTrue(p.overrides().all { it.isDefault })
        b.calls.clear(); p.load(mixSong(), "mix.mid")
        assertEquals("reopened: voice bound again", listOf(2 to strings), b.calls); assertEquals(30, p.overrides()[2].volume)
    }

    @Test fun aPresetThatIsNotLoadedKeepsTheFileVoice() {
        val (p, io, cb) = setup(); val (i, x) = io; val (c, b) = cb
        b.answer = VoiceBind.NOT_FOUND
        assertEquals(VoiceBind.NOT_FOUND, p.setVoice(2, strings)); assertNull(p.overrides()[2].voice)
        assertEquals(VoiceBind.NOT_FOUND, p.status(c.ns).voiceBind[2])
        p.play(); run(p, c, listOf(i, x), untilNs = c.ns + 100_000_000L)
        assertTrue("file voice plays", msgs(i, 2).any { it.k() == pc(2, 49).k() })
    }

    // ---------------- SONG TEMPO / SONG TRANSPOSE ----------------
    private fun tempoMeta(us: Int) = byteArrayOf(0xFF.toByte(), 0x51, 3, (us ushr 16).toByte(), (us ushr 8).toByte(), us.toByte())
    /** 120 BPM for 2 bars, then 60 BPM; ch1 melody, ch10 drums, ch9 XG drum (bank 127), ch4 GS rhythm part, ch3 melodic */
    private fun keySong(): SongTimeline {
        val ev = ArrayList<Pair<Long, ByteArray>>()
        ev += 0L to tempoMeta(500_000); ev += 3840L to tempoMeta(1_000_000)
        ev += 0L to sx(0x43, 0x10, 0x4C, 0x00, 0x00, 0x7E, 0x00, 0xF7); ev += 0L to cc(8, 0, 127); ev += 0L to pc(8, 0)
        ev += 0L to sx(0x41, 0x10, 0x42, 0x12, 0x40, 0x14, 0x15, 0x01, 0x16, 0xF7)          // GS: part 4 (ch4) = rhythm
        for (b in 0 until 8) { val t = b * 480L
            ev += t to on(0, 60 + b % 3); ev += (t + 400) to off(0, 60 + b % 3)
            ev += t to on(9, 36); ev += (t + 100) to off(9, 36); ev += t to on(8, 42); ev += (t + 100) to off(8, 42)
            ev += t to on(3, 38); ev += (t + 100) to off(3, 38); ev += t to on(2, 48); ev += (t + 400) to off(2, 48) }
        ev += 3840L to on(0, 72); ev += 4800L to off(0, 72)
        return SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev)))))
    }
    private fun pairedBySentPitch(r: Rec) {
        val open = HashMap<Pair<Int, Int>, Int>()
        for ((_, m) in r.msgs) { val st = m[0].toInt() and 0xF0; val k = (m[0].toInt() and 15) to (m.getOrElse(1) { 0 }.toInt() and 127)
            if (st == 0x90 && m[2] > 0) open.merge(k, 1, Int::plus)
            else if (st == 0x80 || (st == 0x90 && m[2].toInt() == 0)) { val n = open[k] ?: 0; assertTrue("${r.id}: NOTE OFF $k without a NOTE ON at that pitch", n > 0); open[k] = n - 1 } }
        assertTrue("${r.id}: every sent NOTE ON closed at its own pitch ${open.filterValues { it > 0 }}", open.values.all { it == 0 })
    }

    @Test fun drumChannelsComeFromTheFileNotOnlyChannel10() {
        assertEquals("ch10 + XG bank 127 (ch9) + GS rhythm part (ch4)", setOf(9, 8, 3), keySong().drumChannels)
    }

    @Test fun transposeMovesMelodicChannelsOnlyOnEveryOutputAndPairsNoteOffs() {
        val t = keySong(); val c = Clock(); val i = Rec("internal", acceptsSysEx = true); val x = Rec("external")
        val p = SongPlayer { c.ns }; p.load(t, "k"); p.setOutputs(listOf(i, x)); p.setTranspose(3); p.play(); run(p, c, listOf(i, x))
        for (r in listOf(i, x)) {
            val ons = r.msgs.map { it.second }.filter { (it[0].toInt() and 0xF0) == 0x90 && it[2] > 0 }
            assertEquals("${r.id}: melody +3", setOf(63, 64, 65, 75), ons.filter { (it[0].toInt() and 15) == 0 }.map { it[1].toInt() }.toSet())
            assertEquals("${r.id}: ch3 melodic +3", setOf(51), ons.filter { (it[0].toInt() and 15) == 2 }.map { it[1].toInt() }.toSet())
            assertEquals("${r.id}: drums unchanged (ch10, XG ch9, GS ch4)", setOf(36, 42, 38), ons.filter { (it[0].toInt() and 15) in setOf(9, 8, 3) }.map { it[1].toInt() }.toSet())
            pairedBySentPitch(r)
        }
        assertEquals(0, p.activeNotes()); assertEquals(3, p.status(c.ns).transpose)
        p.setTranspose(40); assertEquals("clamped", 12, p.status(c.ns).transpose)
    }

    @Test fun changingTransposeWhileANoteSoundsEndsItAtItsOwnPitch() {
        val t = keySong(); val c = Clock(); val i = Rec("internal", acceptsSysEx = true); val p = SongPlayer { c.ns }
        p.load(t, "k"); p.setOutputs(listOf(i)); p.play(); run(p, c, listOf(i), untilNs = c.ns + 100_000_000L)     // ch1 60 sounding
        p.setTranspose(5); run(p, c, listOf(i), untilNs = c.ns + 500_000_000L)
        val ch1 = i.msgs.map { it.second }.filter { (it[0].toInt() and 15) == 0 && (it[0].toInt() and 0xF0) in listOf(0x80, 0x90) }
        assertEquals("first note ends at 60 (sent before the change), the next one starts at 61+5", listOf("on60", "off60", "on66"),
            ch1.take(3).map { (if ((it[0].toInt() and 0xF0) == 0x90 && it[2] > 0) "on" else "off") + it[1] })
        // seek / pause / resume / stop with a transpose: no stuck or mismatched note
        p.seekTick(1500); run(p, c, listOf(i), untilNs = c.ns + 200_000_000L); p.pause(); p.setTranspose(-7); p.play()
        run(p, c, listOf(i), untilNs = c.ns + 300_000_000L); p.seekTick(200); p.setTranspose(0); run(p, c, listOf(i), untilNs = c.ns + 300_000_000L); p.stop()
        assertEquals(0, p.activeNotes()); pairedBySentPitch(i)
        p.load(t, "k2"); assertEquals("a new song starts in its own key", 0, p.status(c.ns).transpose)
    }

    @Test fun aTransposedPitchOutside0to127IsNotSentAndNeitherIsItsNoteOff() {
        val ev = listOf(0L to on(0, 125), 400L to off(0, 125), 0L to on(0, 60), 400L to off(0, 60))
        val c = Clock(); val i = Rec("i", acceptsSysEx = true); val p = SongPlayer { c.ns }
        p.load(SongTimeline.from(Smf.parse(smfBytes(0, 480, listOf(track(ev))))), "edge"); p.setOutputs(listOf(i)); p.setTranspose(5); p.play(); run(p, c, listOf(i))
        assertEquals(listOf(65, 65), i.msgs.map { it.second }.filter { (it[0].toInt() and 0xF0) in listOf(0x80, 0x90) }.map { it[1].toInt() })
        pairedBySentPitch(i)
    }

    @Test fun songTempoNudgesOneBpmScalesTheWholeTempoMapAndResets() {
        val t = keySong(); val c = Clock(); val i = Rec("i", acceptsSysEx = true); val p = SongPlayer { c.ns }
        p.load(t, "k"); p.setOutputs(listOf(i)); p.play(); run(p, c, listOf(i), untilNs = c.ns + 1_000_000_000L)
        val pos = p.status(c.ns).positionUs
        p.nudgeTempoBpm(1); p.nudgeTempoBpm(1)
        assertEquals("no jump", pos, p.status(c.ns).positionUs)
        assertEquals("120 -> 122 BPM", 122.0, p.status(c.ns).effectiveBpm, 1e-6); assertEquals(120.0, p.status(c.ns).fileBpm, 1e-6)
        val anchor = c.ns; val songAt = p.status(c.ns).positionUs
        run(p, c, listOf(i), untilNs = c.ns + 3_600_000_000L)                       // into the 60 BPM part (song time > 4 s)
        assertEquals("the later 60 BPM part keeps its ratio (60 x 122/120)", 61.0, p.status(c.ns).effectiveBpm, 1e-6)
        val expected = songAt + ((c.ns - anchor) / 1000.0 * 122.0 / 120.0).toLong()
        assertEquals("song time runs 122/120 faster", expected.toDouble(), p.status(c.ns).positionUs.toDouble(), 2.0)
        p.nudgeTempoBpm(-1); assertEquals("-1 on the tempo heard now: 61 -> 60", 60.0, p.status(c.ns).effectiveBpm, 1e-6)
        p.resetTempo(); assertEquals(1.0, p.status(c.ns).tempoFactor, 0.0); assertEquals(60.0, p.status(c.ns).effectiveBpm, 1e-6)
        p.nudgeTempoBpm(-1000); assertEquals("clamped at 25 %", 0.25, p.status(c.ns).tempoFactor, 1e-9)
        p.load(t, "k3"); assertEquals("a new song starts at its own tempo", 1.0, p.status(c.ns).tempoFactor, 0.0)
    }

    @Test fun anyTempoFactorReachesEveryEventWithoutSpinning() {
        // regression: wall times rounded down left an event 1 us "in the future" for ever with a factor != 1
        for (pct in listOf(95, 97, 101, 103, 133)) {
            val t = timeline("type1_16ch"); val c = Clock(); val i = Rec("i", acceptsSysEx = true); val p = SongPlayer { c.ns }
            p.load(t, "x"); p.setOutputs(listOf(i)); p.setTempoPercent(pct); p.play()
            var steps = 0; var last = -1L
            while (steps++ < 100_000) { val n = p.step(c.ns); if (n < 0) break; assertTrue("$pct%: the next wake-up is in the future (no spin)", n > last || n > c.ns); last = n; c.ns = maxOf(n, c.ns) }
            assertEquals("$pct%: song finished", SongState.STOPPED, p.status(c.ns).state); assertTrue("$pct%: few steps ($steps)", steps < 5_000)
            assertEquals("$pct%: every channel event sent", t.channelEventCount, i.msgs.count { (it.second[0].toInt() and 0xFF) < 0xF0 })
        }
    }

    // ---------------- tiny SMF builder for edge cases (writes bytes only; parsing is under test) ----------------
    private fun on(ch: Int, n: Int) = byteArrayOf((0x90 or ch).toByte(), n.toByte(), 90)
    private fun off(ch: Int, n: Int) = byteArrayOf((0x80 or ch).toByte(), n.toByte(), 0)
    private fun vlq(v: Long): ByteArray { val o = ArrayList<Byte>(); var x = v; o += (x and 0x7F).toByte(); x = x shr 7; while (x > 0) { o += ((x and 0x7F) or 0x80).toByte(); x = x shr 7 }; return o.reversed().toByteArray() }
    private fun track(events: List<Pair<Long, ByteArray>>): ByteArray {
        val d = ByteArrayOutputStream(); var last = 0L
        for ((tick, b) in events.sortedBy { it.first }) { d.write(vlq(tick - last)); d.write(b); last = tick }
        d.write(byteArrayOf(0, 0xFF.toByte(), 0x2F, 0))
        val body = d.toByteArray()
        return "MTrk".toByteArray() + byteArrayOf((body.size ushr 24).toByte(), (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }
    private fun smfBytes(format: Int, division: Int, tracks: List<ByteArray>): ByteArray =
        "MThd".toByteArray() + byteArrayOf(0, 0, 0, 6, 0, format.toByte(), 0, tracks.size.toByte(), (division ushr 8).toByte(), division.toByte()) + tracks.fold(ByteArray(0)) { a, b -> a + b }
}
