package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.LatencyProbe
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The 15 ms settle stays; only the thread that waits and applies the chord depends on the tuning bit. */
class CpvChordThreadTest {
    private val threads = CopyOnWriteArrayList<String>()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

    @After fun cleanup() {
        scope.cancel(); executor.shutdownNow()
        LatencyProbe.tuning = LatencyProbe.TUNE_ALL; LatencyProbe.cpuClock = { 0L }; LatencyProbe.reset()
    }

    private fun applyOneChord(tuning: Int): Pair<String, Long> {
        threads.clear(); LatencyProbe.reset(); LatencyProbe.tuning = tuning
        LatencyProbe.cpuClock = { threads.add(Thread.currentThread().name); 0L }   // chordApplyStart runs on the applying thread
        val bridge = mock(NativeAudioBridge::class.java)
        val engine = AudioEngineManager(bridge, mock(SampleProvider::class.java))
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.attachScope(scope)
        val onChord = ArrangerBrain::class.java.getDeclaredMethod("onChordChanged", DetectedChord::class.java).apply { isAccessible = true }
        val start = System.nanoTime()
        onChord.invoke(brain, DetectedChord(0, 0, ChordQuality.MAJOR))
        val deadline = System.currentTimeMillis() + 3000
        while (threads.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        val waited = (System.nanoTime() - start) / 1_000_000
        return threads.firstOrNull().orEmpty() to waited
    }

    @Test fun tuningOffKeepsTheChordOnTheCallersDispatcher() {
        val (thread, waitedMs) = applyOneChord(LatencyProbe.TUNE_ALL and LatencyProbe.TUNE_CHORD_OFF_MAIN.inv())
        assertEquals("fake-main", thread)
        assertTrue("settle must still wait ~15 ms, was $waitedMs", waitedMs >= 14)
    }

    @Test fun tuningOnMovesTheChordToAWorkerButKeepsTheSettle() {
        val (thread, waitedMs) = applyOneChord(LatencyProbe.TUNE_ALL)
        assertTrue("expected a Default worker, was $thread", thread.startsWith("DefaultDispatcher-worker"))
        assertTrue("settle must still wait ~15 ms, was $waitedMs", waitedMs >= 14)
    }

    @Test fun aNewChordInsideTheSettleStillCancelsTheOldOne() {
        threads.clear(); LatencyProbe.reset(); LatencyProbe.tuning = LatencyProbe.TUNE_ALL
        LatencyProbe.cpuClock = { threads.add(Thread.currentThread().name); 0L }
        val engine = AudioEngineManager(mock(NativeAudioBridge::class.java), mock(SampleProvider::class.java))
        val brain = ArrangerBrain(engine, ChordDetector(), mock(MidiInputManager::class.java))
        brain.attachScope(scope)
        val onChord = ArrangerBrain::class.java.getDeclaredMethod("onChordChanged", DetectedChord::class.java).apply { isAccessible = true }
        onChord.invoke(brain, DetectedChord(0, 0, ChordQuality.MAJOR))
        onChord.invoke(brain, DetectedChord(5, 5, ChordQuality.MAJOR))            // within the 15 ms settle
        Thread.sleep(120)
        assertEquals("one apply reads the clock at its start and end", 2, threads.size)
        assertEquals("only the newest chord is applied", 1, LatencyProbe.chordRetarget.count())
    }
}
