package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.audio.InternalGain
import com.yourapp.yamahaarranger.audio.InternalGainStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalGainTest {
    @Test fun defaultIsLegacyZeroDbAndStepsAreBounded() {
        val f = File.createTempFile("igain", ".txt"); f.delete()
        assertEquals("missing file = legacy gain", 0, InternalGainStore(f).read())
        assertEquals(0, InternalGain.STEPS.first()); assertEquals(InternalGain.MAX_DB, InternalGain.STEPS.last())
        assertEquals(12, InternalGain.clamp(99)); assertEquals(0, InternalGain.clamp(-4))
    }

    @Test fun preferenceRoundTripAndGarbageFallsBackToZero() {
        val f = File.createTempFile("igain", ".txt"); f.delete()
        val s = InternalGainStore(f)
        for (d in InternalGain.STEPS) { s.write(d); assertEquals(d, InternalGainStore(f).read()) }
        s.write(40); assertEquals(12, s.read())
        f.writeText("loud"); assertEquals(0, s.read()); f.writeText(""); assertEquals(0, s.read())
        f.delete()
    }

    @Test fun reportParsingFlagsClipRisk() {
        val ok = InternalGain.parse("IGAIN db=6.0 lin=1.995 peakPreDb=-3.2 peakOutDb=-3.2 rmsDb=-18.5 samples=96000 overSamples=0 limitedSamples=0")
        assertNotNull(ok); assertFalse(ok!!.wouldClip); assertFalse(ok.limiting); assertEquals(-3.2f, ok.peakOutDb, 1e-4f)
        val hot = InternalGain.parse("IGAIN db=12.0 lin=3.981 peakPreDb=4.1 peakOutDb=-0.0 rmsDb=-9.0 samples=10 overSamples=7 limitedSamples=12")!!
        assertTrue(hot.wouldClip); assertTrue(hot.limiting); assertEquals(7L, hot.overSamples)
        assertNull(InternalGain.parse("IGAIN engine_absent")); assertNull(InternalGain.parse("garbage"))
    }

    @Test fun nativeStageSitsAfterTheSynthAndNeverNearMidiOrVelocity() {
        val root = File(System.getProperty("user.dir"))
        val engine = File(root, "app/src/main/cpp/audio_engine.cpp").readText()
        val i = engine.indexOf("soundFont_.render(out, numFrames);")
        assertTrue(i > 0)
        val after = engine.substring(i, i + 200)
        assertTrue("gain stage directly after the synth and before the clamp: $after", after.contains("internalGain_.process(out, sampleCount);") && after.indexOf("internalGain_") < after.indexOf("std::max(-1.0f"))
        val brain = File(root, "app/src/main/java/com/yourapp/arranger/ArrangerBrain.kt").readText()
        assertFalse("Brain (velocity / MIDI OUT) must not know the gain", brain.contains("InternalGain") || brain.contains("internalGain"))
        val midi = File(root, "app/src/main/java/com/yourapp/midi").walkTopDown().filter { it.isFile }.joinToString { it.readText() }
        assertFalse(midi.contains("InternalGain") || midi.contains("internalGain"))
    }
}
