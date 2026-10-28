package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.RightVelocityMode
import com.yourapp.yamahaarranger.audio.RightVelocityMonitor
import com.yourapp.yamahaarranger.audio.RightVelocityTest
import com.yourapp.yamahaarranger.audio.RightVoiceDiagParser
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RightVoiceDiagTest {
    @After fun restore() = RightVelocityTest.reset()

    @Test fun defaultIsOriginalAndOriginalNeverAltersVelocity() {
        RightVelocityTest.reset()
        assertEquals(RightVelocityMode.ORIGINAL, RightVelocityTest.mode)
        for (v in 1..127) {
            assertEquals(v, RightVelocityTest.internalVelocity127(v))
            assertEquals(v / 127f, RightVelocityTest.internalVelocity(v / 127f, v), 0f)
        }
    }

    @Test fun fixed100OnlyChangesInternalVelocityNeverMidiOut() {
        RightVelocityTest.set(RightVelocityMode.FIXED_100)
        for (v in 1..127) {
            assertEquals(100, RightVelocityTest.internalVelocity127(v))
            assertEquals(100f / 127f, RightVelocityTest.internalVelocity(v / 127f, v), 0f)
            assertEquals("MIDI OUT must keep the PSR-E343 velocity", v, RightVelocityTest.midiOutVelocity127(v))
        }
        // The native side rounds velocity*127 back to the integer sent to BASSMIDI.
        assertEquals(100, Math.round(RightVelocityTest.internalVelocity(0.2f, 25) * 127f))
    }

    @Test fun resetReturnsToOriginalLikeAFreshProcess() {
        RightVelocityTest.set(RightVelocityMode.FIXED_100)
        RightVelocityTest.reset()
        assertEquals(RightVelocityMode.ORIGINAL, RightVelocityTest.mode)
    }

    @Test fun modeIsNotPersistedAnywhere() {
        val source = sourceFile("yamahaarranger/audio/RightVoiceDiag.kt").readText()
        for (forbidden in listOf("SharedPreferences", "DataStore", "File(", "writeText", "Settings."))
            assertFalse("A/B mode must stay process-local: $forbidden", source.contains(forbidden))
    }

    @Test fun onlyRightNoteOnUsesTheTestAndMidiOutIsOriginal() {
        val brain = sourceFile("arranger/ArrangerBrain.kt").readText()
        val hits = Regex("RightVelocityTest\\.").findAll(brain).count()
        assertEquals("exactly the internal-velocity and MIDI OUT guard calls", 2, hits)
        val start = brain.indexOf("private fun startRightNote")
        val end = brain.indexOf("private fun releaseRightNote")
        assertTrue(start in 0 until end)
        val body = brain.substring(start, end)
        assertTrue(body.contains("engineNoteOn(channel, outputNote, RightVelocityTest.internalVelocity(engineVelocity, engineVelocity127))"))
        assertTrue(body.contains("midiInputManager.sendNoteOn(channel, outputNote, RightVelocityTest.midiOutVelocity127(velocity127))"))
        assertFalse("LEFT/ACMP/style paths must not reference the test",
            brain.substring(0, start).contains("RightVelocityTest.") || brain.substring(end).contains("RightVelocityTest."))
        val sequencer = sourceFile("yamahaarranger/arranger/StyleSequencer.kt").readText()
        assertFalse(sequencer.contains("RightVelocityTest"))
    }

    @Test fun monitorIsolatedPerLayerWithMinMedianMax() {
        val m = RightVelocityMonitor()
        listOf(30, 30, 30, 80, 127).forEach { m.record(0, it) }
        m.record(1, 64)
        val a = m.stats(0)
        assertEquals(5L, a.count); assertEquals(127, a.last); assertEquals(30, a.min); assertEquals(30, a.median); assertEquals(127, a.max)
        assertEquals(1L, m.stats(1).count)
        assertEquals(0L, m.stats(2).count); assertEquals(-1, m.stats(2).min)
        m.record(3, 100); m.record(-1, 100) // LEFT / invalid never counted
        assertEquals(0L, m.stats(2).count)
        m.reset()
        assertEquals(0L, m.stats(0).count)
    }

    @Test fun parserReadsQuotedAndPlainFields() {
        val row = RightVoiceDiagParser.parseLine(
            "RVD part=RIGHT2 ch=1 reqSf2='tyros4.sf2' liveName='tyros 4 grand' status=MATCH cc71=-1 fwdMed=64")
        assertNotNull(row)
        assertEquals("RIGHT2", row!!.part); assertEquals(1, row.int("ch"))
        assertEquals("tyros 4 grand", row["liveName"]); assertEquals("MATCH", row.status)
        assertEquals(-1, row.int("cc71")); assertEquals(64, row.int("fwdMed"))
        assertNull(RightVoiceDiagParser.parseLine("KEYBOARD VOICE part=RIGHT1"))
        assertEquals(2, RightVoiceDiagParser.parse("RVD part=RIGHT1 ch=0 status=MATCH\nRVD part=RIGHT2 ch=1 status=UNVERIFIED\n").size)
    }

    private fun sourceFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/com/yourapp/$relative")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("source not found: $relative")
    }
}
