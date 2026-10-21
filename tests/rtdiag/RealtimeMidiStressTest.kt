package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.ArrangerSection
import com.yourapp.yamahaarranger.audio.NoteLogTuning
import com.yourapp.yamahaarranger.ui.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REALTIME MIDI & AUDIO STRESS DIAGNOSTIC (host). Characterization of the production chain with synthetic MIDI;
 * results are printed as `RTDIAG ...` lines. HOST/MOCK evidence only: no BASSMIDI, no Oboe, no USB, x86 JVM.
 */
class RealtimeMidiStressTest {
    private fun report(scenario: String, vararg kv: Pair<String, Any?>) =
        println("RTDIAG scenario=$scenario " + kv.joinToString(" ") { "${it.first}=${it.second}" })

    private fun logLines(prefix: String) = DebugLog.getFullAll().count { it.contains(prefix) }
    private val C = listOf(36, 40, 43); private val F = listOf(41, 45, 48); private val G = listOf(43, 47, 50); private val Am = listOf(45, 48, 52)

    private fun rig(out: Boolean = false, lean: Boolean = false, tempo: Int = 120): RtRig {
        DebugLog.clear(); NoteLogTuning.lean = lean; return RtRig(out, tempo)
    }
    private fun RtRig.playChord(notes: List<Int>, gapMs: Long = 0) { notes.forEach { noteOn(it); if (gapMs > 0) Thread.sleep(gapMs) } }
    private fun RtRig.releaseChord(notes: List<Int>) { notes.forEach { noteOff(it) } }
    private fun RtRig.startStyle() { brain.startStop(); Thread.sleep(150) }
    private fun RtRig.stopAndSettle() { brain.startStop(); Thread.sleep(120) }

    // ---------------------------------------------------------------- 5. RIGHT1 only, no Style
    @Test fun right1OnlyBalancedAndOrdered() {
        val r = rig()
        val keys = (60..71).toList()
        keys.forEach { r.noteOn(it); Thread.sleep(3); r.noteOff(it); Thread.sleep(3) }
        r.noteOn(64); r.noteOn(64); r.noteOff(64); r.noteOff(64)       // overlap of the same pitch
        r.noteOn(65); r.noteOff(65); r.noteOn(65); r.noteOff(65)
        val ons = r.count('O', 0..0); val offs = r.count('F', 0..0)
        report("right1_only", "midiIn" to (keys.size * 2 + 8), "engineOn" to ons, "engineOff" to offs, "stuck" to r.oracle.stuckOnChannels(0..15),
            "orphanOff" to r.oracle.orphanOffs, "maxPoly" to r.oracle.maxPolyphony, "usbWrites" to r.usbWrites.size)
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); assertEquals(0L, r.oracle.orphanOffs)
        assertEquals(ons, offs)
        r.close()
    }

    // ---------------------------------------------------------------- 1. chord pressed and released slowly (ACMP ON, no style)
    @Test fun chordSlowPressRelease() {
        val r = rig()
        r.playChord(C, 60); Thread.sleep(300); r.releaseChord(C); Thread.sleep(100)
        r.playChord(F, 60); Thread.sleep(300); r.releaseChord(F); Thread.sleep(100)
        report("chord_slow", "settle" to logLines("CHORD SETTLE"), "applied" to logLines("ACMP chord"), "engineEvents" to r.engineEvents.size,
            "stuck" to r.oracle.stuckOnChannels(0..15))
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    // ---------------------------------------------------------------- chord recognition: chord changes produced by one physical chord
    @Test fun chordRollSpreadVsSettleWindow() {
        for (spread in listOf(0L, 5L, 10L, 15L, 20L, 40L, 80L)) {
            val r = rig()
            r.playChord(C, spread); Thread.sleep(150)
            report("chord_roll_spread_${spread}ms", "notes" to 3, "settleRequests" to logLines("CHORD SETTLE"), "chordApplied" to logLines("ACMP chord"))
            r.releaseChord(C); r.close()
        }
    }

    // ---------------------------------------------------------------- 2. fast repeated chord, 3. fast chord changes (no Style)
    @Test fun fastRepeatedChordsAndChangesWithoutStyle() {
        for (periodMs in listOf(250L, 120L, 60L)) {
            val r = rig()
            val d = Dispatcher(r); val items = ArrayList<Dispatcher.Item>(); var t = 0L
            repeat(20) { n -> val chord = listOf(C, F, G, Am)[n % 4]
                chord.forEach { items += Dispatcher.Item(t * 1_000_000 + it % 7 * 1_000_000, intArrayOf(0x90, it, 90)) }
                chord.forEach { items += Dispatcher.Item((t + periodMs / 2) * 1_000_000 + it % 5 * 1_000_000, intArrayOf(0x80, it, 0)) }
                t += periodMs }
            items.sortBy { it.atNs }
            d.run(items); Thread.sleep(150)
            report("fast_chord_changes_noStyle_${periodMs}ms", "midiIn" to items.size, "settle" to logLines("CHORD SETTLE"), "applied" to logLines("ACMP chord"),
                "startLateP95us" to pct(d.lateNs, 0.95) / 1000, "startLateMaxus" to (d.lateNs.maxOrNull() ?: 0) / 1000,
                "callCostP50us" to pct(d.costNs, 0.5) / 1000, "callCostMaxus" to (d.costNs.maxOrNull() ?: 0) / 1000, "stuck" to r.oracle.stuckOnChannels(0..15))
            r.close()
        }
    }

    // ---------------------------------------------------------------- 6/7/8. Style with ACMP OFF / ON, fast chord changes, MAIN->FILL->MAIN
    private fun styleRun(name: String, acmp: Boolean, periodMs: Long, changes: Int, midiOut: Boolean, transition: Boolean = false, lean: Boolean = false, nativeCostUs: Long = 0): RtRig {
        val r = rig(midiOut, lean, 140); r.nativeCallCostUs = nativeCostUs
        r.brain.setAcmpEnabled(acmp)
        r.startStyle()
        val d = Dispatcher(r); val items = ArrayList<Dispatcher.Item>(); var t = 0L
        val seq = listOf(C, F, G, Am)
        for (n in 0 until changes) { val chord = seq[n % 4]
            if (n > 0) seq[(n - 1) % 4].forEach { items += Dispatcher.Item(t * 1_000_000, intArrayOf(0x80, it, 0)) }
            chord.forEachIndexed { i, k -> items += Dispatcher.Item((t + i) * 1_000_000, intArrayOf(0x90, k, 95)) }
            items += Dispatcher.Item((t + 5) * 1_000_000, intArrayOf(0x90, 72 + n % 5, 100)); items += Dispatcher.Item((t + periodMs - 5) * 1_000_000, intArrayOf(0x80, 72 + n % 5, 0))
            t += periodMs }
        items.sortBy { it.atNs }
        if (transition) Thread { Thread.sleep(900); r.brain.selectMainVariation(ArrangerSection.MainB) }.start()
        d.run(items)
        Thread.sleep(250)
        val mainEv = r.engineEvents.filter { it.thread == "MainSim" }
        val midiEv = r.engineEvents.filter { it.thread == "MIDI-dispatch" }
        val seqEv = r.engineEvents.filter { it.thread != "MainSim" && it.thread != "MIDI-dispatch" }
        report(name, "periodMs" to periodMs, "changes" to changes, "acmp" to acmp, "midiOut" to midiOut, "lean" to lean, "nativeCostUs" to nativeCostUs,
            "midiIn" to items.size, "settle" to logLines("CHORD SETTLE"), "applied" to logLines("ACMP chord"),
            "engineOnMain" to mainEv.count { it.kind == 'O' }, "engineOffMain" to mainEv.count { it.kind == 'F' },
            "engineOnSeq" to seqEv.count { it.kind == 'O' }, "engineOnMidi" to midiEv.count { it.kind == 'O' },
            "callsUnderKeyboardLock" to r.engineEvents.count { it.kbLock }, "callsUnderLifecycleLock" to r.engineEvents.count { it.lcLock },
            "callsUnderMixerMonitor" to r.engineEvents.count { it.mixLock }, "usbOnMain" to r.usbWrites.count { it.thread == "MainSim" },
            "usbUnderKeyboardLock" to r.usbWrites.count { it.kbLock }, "usbUnderLifecycleLock" to r.usbWrites.count { it.lcLock }, "usbWrites" to r.usbWrites.size,
            "startLateP95us" to pct(d.lateNs, 0.95) / 1000, "startLateMaxus" to (d.lateNs.maxOrNull() ?: 0) / 1000,
            "callCostP50us" to pct(d.costNs, 0.5) / 1000, "callCostP95us" to pct(d.costNs, 0.95) / 1000, "callCostMaxus" to (d.costNs.maxOrNull() ?: 0) / 1000,
            "maxPoly" to r.oracle.maxPolyphony, "orphanOff" to r.oracle.orphanOffs, "logLines" to DebugLog.getFullAll().size)
        return r
    }

    @Test fun styleAcmpOffThenOn() {
        for (acmp in listOf(false, true)) {
            val r = styleRun("style_acmp_${if (acmp) "on" else "off"}_fastChanges", acmp, 180, 16, false)
            r.stopAndSettle()
            report("style_acmp_${if (acmp) "on" else "off"}_after_stop", "stuckStyleChannels" to r.oracle.stuckOnChannels(4..15), "stuckAll" to r.oracle.stuckOnChannels(0..15))
            assertEquals(0, r.oracle.stuckOnChannels(4..15)); r.close()
        }
    }

    @Test fun styleChordChangeRateSweep() {
        for (period in listOf(1200L, 400L, 200L, 120L, 80L)) {
            val r = styleRun("style_acmp_on_period_${period}ms", true, period, (4800 / period).toInt().coerceAtLeast(4), false)
            report("style_acmp_on_period_${period}ms_voice_load", "peakInstances_noTail" to r.peakInstances(0.0), "peakInstances_tail0.5s" to r.peakInstances(0.5),
                "peakInstances_tail1.5s" to r.peakInstances(1.5), "peakInstances_tail3s" to r.peakInstances(3.0), "totalNativeNoteOn" to r.count('O'))
            r.stopAndSettle(); r.close()
        }
    }

    @Test fun mainFillMainWhileChordsChangeFast() {
        val r = styleRun("main_fill_main_fast_chords", true, 150, 24, false, transition = true)
        r.brain.selectMainVariation(ArrangerSection.MainA); Thread.sleep(2500)
        r.stopAndSettle()
        report("main_fill_main_after_stop", "stuckStyleChannels" to r.oracle.stuckOnChannels(4..15), "transitionLogs" to logLines("MASTER TRANSITION"),
            "released" to logLines("TRANSITION: released"))
        assertEquals(0, r.oracle.stuckOnChannels(4..15)); r.close()
    }

    // ---------------------------------------------------------------- 9. STOP while notes are active (style notes and held keyboard keys)
    @Test fun stopWithActiveNotes() {
        val r = rig(true, false, 140)
        r.startStyle(); r.playChord(C, 5); r.noteOn(72); r.noteOn(76); Thread.sleep(700)
        val activeBefore = r.oracle.polyphony; val keyboardBefore = r.oracle.stuckOnChannels(0..3)
        r.brain.startStop(); Thread.sleep(150)
        val afterStop = r.oracle.stuckOnChannels(0..15); val allOff = r.engineEvents.count { it.kind == 'A' }
        r.noteOff(72); r.noteOff(76); r.releaseChord(C); Thread.sleep(50)
        report("stop_with_active_notes", "activeBeforeStop" to activeBefore, "heldKeyboardNotesBeforeStop" to keyboardBefore, "stuckAfterStop" to afterStop,
            "globalAllNotesOffCalls" to allOff, "orphanOffsAfterKeyUp" to r.oracle.orphanOffs, "usbAllNotesOffWrites" to r.usbWrites.count { (it.status and 0xF0) == 0xB0 && it.d1 == 123 })
        assertEquals(0, afterStop); r.close()
    }

    // ---------------------------------------------------------------- sustain CC64, panic CCs
    @Test fun sustainAndPanicControllers() {
        val r = rig()
        r.cc(64, 127); (60..64).forEach { r.noteOn(it); Thread.sleep(2); r.noteOff(it) }
        val heldWhilePedal = r.oracle.stuckOnChannels(0..0)
        r.noteOn(60); r.noteOff(60)
        r.cc(64, 0); Thread.sleep(20)
        val afterPedalUp = r.oracle.stuckOnChannels(0..0)
        r.noteOn(70); r.cc(123, 0); r.cc(120, 0); val afterPanicCC = r.oracle.stuckOnChannels(0..0)
        r.cc(64, 127, ch = 1)
        report("sustain_and_panic", "heldWhilePedalDown" to heldWhilePedal, "heldAfterPedalUp" to afterPedalUp, "heldAfterIncomingCC123_CC120" to afterPanicCC, "orphanOff" to r.oracle.orphanOffs)
        assertEquals(5, heldWhilePedal); assertEquals(0, afterPedalUp)
        assertEquals(1, afterPanicCC)    // CHARACTERIZATION: incoming CC123/CC120 from the keyboard are not interpreted
        r.close()
    }

    // ---------------------------------------------------------------- loopback: what if YA's MIDI OUT returns to its MIDI IN?
    @Test fun midiOutLoopbackAmplification() {
        val r = rig(true); r.loopback = true
        r.noteOn(72); Thread.sleep(300)
        val at300 = r.usbWrites.size; Thread.sleep(300); val at600 = r.usbWrites.size
        report("midi_out_loopback_if_echoed_1ms", "usbWritesFirst300ms" to at300, "usbWritesNext300ms" to (at600 - at300), "selfSustaining" to (at600 - at300 > 20),
            "engineOnCalls" to r.count('O'), "polyphonyNow" to r.oracle.polyphony)
        r.close()
    }

    // ---------------------------------------------------------------- logging cost
    @Test fun loggingCostPerKeyEvent() {
        for (lean in listOf(false, true)) {
            val r = rig(false, lean); val n = 3000; val costs = ArrayList<Long>(n)
            repeat(n) { i -> val k = 60 + i % 24; val t0 = System.nanoTime(); r.noteOn(k); r.noteOff(k); costs.add(System.nanoTime() - t0) }
            report("logging_cost_lean_$lean", "pairs" to n, "p50us" to pct(costs, 0.5) / 1000.0, "p95us" to pct(costs, 0.95) / 1000.0, "maxus" to (costs.maxOrNull() ?: 0) / 1000.0,
                "logLinesPerPair" to DebugLog.getFullAll().size.toDouble() / n, "suppressedByLean" to NoteLogTuning.suppressed)
            r.close()
        }
        NoteLogTuning.lean = false
    }

    // ---------------------------------------------------------------- sensitivity: native call cost vs backlog (MODEL, not measurement)
    @Test fun nativeCallCostSensitivity() {
        for (cost in listOf(0L, 150L, 400L, 1000L)) {
            val r = rig(); r.nativeCallCostUs = cost
            r.brain.setRightVoiceEnabled(1, true); r.brain.setRightVoiceEnabled(2, true)
            val d = Dispatcher(r); val items = ArrayList<Dispatcher.Item>()
            repeat(40) { n -> val base = n * 30_000_000L; listOf(60, 64, 67, 72).forEachIndexed { i, k -> items += Dispatcher.Item(base + i * 800_000L, intArrayOf(0x90, k, 90)) }
                listOf(60, 64, 67, 72).forEachIndexed { i, k -> items += Dispatcher.Item(base + 15_000_000L + i * 800_000L, intArrayOf(0x80, k, 0)) } }
            items.sortBy { it.atNs }; d.run(items)
            report("native_cost_model_${cost}us_per_call", "keyEventsIn" to items.size, "engineCalls" to r.engineEvents.size,
                "startLateP50ms" to pct(d.lateNs, 0.5) / 1_000_000.0, "startLateP95ms" to pct(d.lateNs, 0.95) / 1_000_000.0, "startLateMaxms" to (d.lateNs.maxOrNull() ?: 0) / 1_000_000.0,
                "stuck" to r.oracle.stuckOnChannels(0..15))
            r.close()
        }
    }

    // ---------------------------------------------------------------- structural facts
    @Test fun lockAndThreadFacts() {
        val r = rig(true, false, 140)
        r.startStyle(); r.playChord(C, 2); Thread.sleep(500); r.releaseChord(C); r.playChord(F, 2); Thread.sleep(500); r.releaseChord(F); r.noteOn(72); Thread.sleep(50); r.noteOff(72)
        val rightNotes = r.engineEvents.filter { it.ch == 0 }
        val retarget = r.engineEvents.filter { it.thread == "MainSim" }
        report("lock_and_thread_facts",
            "rightNoteNativeCallsUnderKeyboardLock" to rightNotes.count { it.kbLock }, "rightNoteNativeCalls" to rightNotes.size,
            "usbWritesUnderKeyboardLock" to r.usbWrites.count { it.kbLock },
            "chordRetargetNativeCallsOnMainThread" to retarget.size, "retargetUnderLifecycleLock" to retarget.count { it.lcLock },
            "styleNoteNativeCallsUnderLifecycleLock" to r.engineEvents.count { it.lcLock && it.thread != "MainSim" },
            "styleUsbWritesUnderLifecycleLock" to r.usbWrites.count { it.lcLock })
        assertTrue(rightNotes.isNotEmpty()); r.stopAndSettle(); r.close()
    }

    // ---------------------------------------------------------------- chord modes: chord changes per physical chord (rolled by hand)
    @Test fun chordModesVsSettleWindow() {
        for (mode in com.yourapp.yamahaarranger.chord.ChordMode.values()) {
            for (spread in listOf(0L, 10L, 30L, 60L)) {
                val r = rig()
                com.yourapp.yamahaarranger.chord.ChordModeController.setMode(mode)   // after the rig: ChordDetector.init resets the global mode
                r.playChord(C, spread); Thread.sleep(120)
                val rootPos = Pair(logLines("CHORD SETTLE"), logLines("ACMP chord"))
                r.releaseChord(C); Thread.sleep(40); DebugLog.clear()
                val inv = listOf(40, 43, 48)       // first inversion rolled E-G-C: first notes are NOT the root
                r.playChord(inv, spread); Thread.sleep(120)
                report("chord_mode_${mode}_spread_${spread}ms", "rootPosSettleRequests" to rootPos.first, "rootPosChordApplied" to rootPos.second,
                    "inversionSettleRequests" to logLines("CHORD SETTLE"), "inversionChordApplied" to logLines("ACMP chord"))
                r.releaseChord(inv); r.close()
            }
        }
        com.yourapp.yamahaarranger.chord.ChordModeController.setMode(com.yourapp.yamahaarranger.chord.ChordMode.MultiFinger)
    }

    // ---------------------------------------------------------------- Main-thread cost of one chord change (MODEL: native + USB cost per call)
    @Test fun mainThreadChordRetargetCostModel() {
        for ((nativeUs, usbUs) in listOf(0L to 0L, 100L to 50L, 300L to 150L, 700L to 300L)) {
            val control = rig(true, false, 140); control.startStyle(); Thread.sleep(300 + 6 * 420L); val controlLines = DebugLog.getFullAll().size; control.stopAndSettle(); control.close()
            val r = rig(true, false, 140); r.nativeCallCostUs = nativeUs; r.usbWriteCostUs = usbUs
            r.startStyle(); Thread.sleep(300)
            repeat(6) { n -> val chord = listOf(C, F, G, Am)[n % 4]; if (n > 0) listOf(C, F, G, Am)[(n - 1) % 4].forEach { r.noteOff(it) }; r.playChord(chord, 1); Thread.sleep(420) }
            val main = r.engineEvents.filter { it.thread == "MainSim" }.sortedBy { it.t }
            val clusters = ArrayList<Pair<Long, Int>>(); var start = 0L; var last = 0L; var n = 0
            for (e in main) { if (n == 0 || e.t - last > 30_000_000) { if (n > 0) clusters += (last - start) to n; start = e.t; n = 0 }; last = e.t; n++ }
            if (n > 0) clusters += (last - start) to n
            val usbMain = r.usbWrites.count { it.thread == "MainSim" }
            report("main_thread_retarget_model_native${nativeUs}us_usb${usbUs}us", "chordChanges" to clusters.size, "nativeCallsPerChange" to (clusters.map { it.second }.average()),
                "usbWritesOnMainTotal" to usbMain, "extraLogLinesPerChordChange" to ((DebugLog.getFullAll().size - controlLines).toDouble() / clusters.size.coerceAtLeast(1)), "mainBlockedP50ms" to pct(clusters.map { it.first }, 0.5) / 1_000_000.0, "mainBlockedMaxms" to (clusters.maxOfOrNull { it.first } ?: 0) / 1_000_000.0,
                "frameBudgetMs_60Hz_90Hz_120Hz" to "16.7/11.1/8.3")
            r.stopAndSettle(); r.close()
        }
    }

    // ---------------------------------------------------------------- parser robustness: running status, realtime bytes, split packets, SysEx, other channels
    @Test fun parserHandlesRunningStatusRealtimeBytesAndSplitPackets() {
        val r = rig()
        r.feed(0x90, 60, 90, 62, 90, 64, 90)                       // running status: three NOTE_ONs in one packet
        r.feed(0x90, 65, 0xFE, 90)                                 // Active Sensing between data bytes
        r.feed(0x90, 67); r.feed(90)                               // data split across two packets
        r.feed(0xF0, 0x7E, 0x7F, 0x09, 0x01, 0xF7, 69, 90)         // SysEx, then stray data (must be ignored, no status)
        r.feed(0x90, 71, 90, 0x80, 71, 0)                          // two messages in one packet
        r.feed(0x91, 72, 90)                                       // other channel: ignored by design (chordInputChannel = 0)
        r.feed(0x90, 73, 90, 0x90, 73, 0)                          // NOTE_ON velocity 0 == NOTE_OFF
        val ons = r.count('O', 0..0); val offs = r.count('F', 0..0)
        listOf(60, 62, 64, 65, 67).forEach { r.noteOff(it) }
        report("parser_robustness", "engineOn" to ons, "engineOffBeforeCleanup" to offs, "stuckAfterCleanup" to r.oracle.stuckOnChannels(0..15), "orphanOff" to r.oracle.orphanOffs)
        assertEquals(7, ons)       // 60,62,64,65,67,71,73 (69 stray data and ch2 are not notes)
        assertEquals(0, r.oracle.stuckOnChannels(0..15)); r.close()
    }

    // ---------------------------------------------------------------- chord transition breakdown (LatencyProbe hooks, wired in this branch)
    private fun transitionRun(label: String, spreadMs: Long, waitUntilPhase: (RtRig) -> Unit, midiOut: Boolean, nativeUs: Long = 0, usbUs: Long = 0, fill: Boolean = false) {
        val r = rig(midiOut, false, 120); r.nativeCallCostUs = nativeUs; r.usbWriteCostUs = usbUs
        com.yourapp.yamahaarranger.audio.LatencyProbe.reset()
        r.startStyle()
        val chords = listOf(C, F, G, Am)
        r.playChord(chords[0], spreadMs); Thread.sleep(600)
        if (fill) { r.brain.selectMainVariation(ArrangerSection.MainB); Thread.sleep(300) }
        for (n in 1..6) { waitUntilPhase(r); chords[(n - 1) % 4].forEach { r.noteOff(it) }; r.playChord(chords[n % 4], spreadMs); Thread.sleep(250) }
        r.stopAndSettle()
        val rows = com.yourapp.yamahaarranger.audio.LatencyProbe.report().lines()
        fun stat(name: String) = rows.firstOrNull { it.contains("stat=$name") }?.substringAfter("count=")?.substringBefore(" buckets") ?: "NA"
        report(label, "spreadMs" to spreadMs, "midiOut" to midiOut, "nativeUs" to nativeUs, "usbUs" to usbUs,
            "firstKeyToRecognized" to stat("chordFirstKeyToRecognized_includesFingerSpread"), "recognizedToApply" to stat("chordRecognizedToApplyStart_settleAndMainQueue"),
            "applyDuration" to stat("chordApplyDuration_handleChordChange"), "firstKeyToDone" to stat("chordFirstKeyToRetargetDone_synchronousJniAndUsb"),
            "schedulerWakeLateness" to stat("styleEventWakeLatenessBeforeLifecycle"), "dispatchLateness" to stat("styleEventDispatchLatenessAfterLifecycle"),
            "counters" to rows.firstOrNull { it.startsWith("LATENCY_KOTLIN_COUNTERS") }?.substringAfter("midiEventsIn="))
        r.close()
    }
    /** Waits until the Style master tick is `phaseTicks` into the 1920-tick bar (PPQ 480, 4/4). */
    private fun phase(phaseTicks: Long): (RtRig) -> Unit = { r ->
        val src = com.yourapp.yamahaarranger.audio.ArrangerBrainClock(r.brain); val bar = 1920L; var guard = 0
        while (guard++ < 4000) { val t = src.masterTickNow(); val inBar = ((t % bar) + bar) % bar; val d = (phaseTicks - inBar + bar) % bar; if (d < 20) break; Thread.sleep(2) }
    }
    @Test fun chordTransitionBreakdownByBarPhase() {
        transitionRun("chord_transition_midBeat_spread0", 0, phase(240), false)
        transitionRun("chord_transition_midBeat_spread20", 20, phase(240), false)
        transitionRun("chord_transition_nearBarLine_spread0", 0, phase(1860), false)
        transitionRun("chord_transition_justAfterBarLine_spread0", 0, phase(30), false)
        transitionRun("chord_transition_duringFill_spread0", 0, phase(240), false, fill = true)
        transitionRun("chord_transition_midBeat_midiOut_model300us150us", 0, phase(240), true, 300, 150)
    }
}
