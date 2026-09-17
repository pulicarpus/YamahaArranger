package com.yourapp.chord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AcmpChordAnalyzerTest {
    private fun midi(vararg notes: Int) = notes.toList()

    @Test fun majorRootPosition() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 64, 67))
        assertNotNull(result)
        assertEquals("C", result!!.displayName)
    }

    @Test fun minorRootPosition() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 63, 67))
        assertEquals("Cm", result!!.displayName)
    }

    @Test fun sus4RootPosition() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 65, 67))
        assertEquals("Csus4", result!!.displayName)
    }

    @Test fun dominantSeven() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 64, 67, 70))
        assertEquals("C7", result!!.displayName)
    }

    @Test fun minorSeven() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 63, 67, 70))
        assertEquals("Cm7", result!!.displayName)
    }

    @Test fun majorSeven() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 64, 67, 71))
        assertEquals("CM7", result!!.displayName)
    }

    @Test fun sixth() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 64, 67, 69))
        assertEquals("C6", result!!.displayName)
    }

    @Test fun diminishedSeven() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 63, 66, 69))
        assertEquals("Cdim7", result!!.displayName)
    }

    @Test fun inversionWithSlashBass() {
        val result = AcmpChordAnalyzer.analyze(midi(64, 67, 72))
        assertEquals("C/E", result!!.displayName)
        assertEquals(4, result.bassNote)
    }

    @Test fun unsupportedIncompleteVoicingIsNotPretendedToBeFullChord() {
        val result = AcmpChordAnalyzer.analyze(midi(60, 61))
        assertEquals("C5", result!!.displayName)
    }

    @Test fun emptyInput() {
        assertNull(AcmpChordAnalyzer.analyze(emptyList()))
    }
}
