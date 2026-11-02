package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.song.Smf
import com.yourapp.yamahaarranger.song.SmfException
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

    private class Rec(override val id: String) : SongOutput {
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
            assertEquals("$name channel events (incl. running status)", m["channelEvents"]!!.toInt(), t.events.size)
            assertEquals(name, m["sysex"]!!.toInt(), t.sysExCount)
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
        val t = timeline("type1_16ch"); val c = Clock(); val a = Rec("internal"); val b = Rec("external")
        val p = SongPlayer { c.ns }; p.load(t, "type1"); p.setOutputs(listOf(a, b))
        val start = c.ns; assertNull(p.play()); run(p, c, listOf(a, b))
        assertEquals(SongState.STOPPED, p.status(c.ns).state); assertEquals("End of song", p.status(c.ns).message)
        assertEquals("internal: every channel event once", t.events.size, a.msgs.size)
        assertEquals("external identical to internal", a.msgs.map { it.second.toList() }, b.msgs.map { it.second.toList() })
        assertEquals("no SysEx ever sent", 0, a.msgs.count { (it.second[0].toInt() and 0xFF) >= 0xF0 })
        assertEquals("notes balanced", a.noteOns(), a.noteOffs()); assertEquals(0, p.activeNotes())
        for ((i, e) in t.events.withIndex()) assertEquals("event $i on time", start + e.us * 1000, a.msgs[i].first)
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
        val i = InternalSongOutput { internal += it; true }; val e = ExternalSongOutput { external += it; false }   // MIDI OUT refusing (off) must not matter
        for ((r, expI, expE) in listOf(Triple(SongRouting.INTERNAL, 1, 0), Triple(SongRouting.EXTERNAL, 0, 1), Triple(SongRouting.BOTH, 1, 1))) {
            internal.clear(); external.clear()
            val c = Clock(); val p = SongPlayer { c.ns }; p.load(t, "x"); p.setOutputs(songOutputsFor(r, i, e)); p.play()
            var g = 0; while (g++ < 100_000) { val n = p.step(c.ns); if (n < 0) break; c.ns = maxOf(n, c.ns) }
            assertEquals("$r internal", expI * t.events.size, internal.size); assertEquals("$r external", expE * t.events.size, external.size)
        }
        assertEquals("Drums (kit 1)", GmNames.name(9, 0)); assertEquals("Acoustic Grand Piano", GmNames.name(0, 0)); assertEquals("Drums (kit 26)", GmNames.name(3, 25, 127))
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
