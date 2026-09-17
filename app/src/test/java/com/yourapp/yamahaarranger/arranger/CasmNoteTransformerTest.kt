package com.yourapp.yamahaarranger.arranger

import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CasmNoteTransformerTest {

    private val cMajor = DetectedChord(0, 0, ChordQuality.MAJOR)
    private val gMajor = DetectedChord(7, 7, ChordQuality.MAJOR)

    private fun policy(
        ntr: Int = 0,
        ntt: Int = 0,
        highKey: Int = 0,
        low: Int = 0,
        high: Int = 127,
        bassOn: Boolean = false
    ) = CasmPolicyModel(
        sourceChannel = 3,
        destinationChannel = 11,
        voiceName = "piano 00",
        sourceChordRoot = 0,
        sourceChordType = 0,
        ntr = ntr,
        ntt = ntt,
        highKey = highKey,
        noteLimitLow = low,
        noteLimitHigh = high,
        rtr = 0,
        bassOn = bassOn
    )

    @Test
    fun rootTranspose_movesWithChordRoot() {
        assertEquals(67, CasmNoteTransformer.transform(60, gMajor, policy()))
    }

    @Test
    fun bypass_preservesSourceNote() {
        assertEquals(60, CasmNoteTransformer.transform(60, gMajor, policy(ntt = 0)))
    }

    @Test
    fun chordNtt_snapsToChordTone() {
        assertEquals(64, CasmNoteTransformer.transform(63, cMajor, policy(ntt = 2)))
    }

    @Test
    fun bassNtt_usesChordBassWhenEnabled() {
        val chord = DetectedChord(0, 7, ChordQuality.MAJOR)
        assertEquals(67, CasmNoteTransformer.transform(60, chord, policy(ntt = 3, bassOn = true)))
    }

    @Test
    fun noteLimit_canSuppressPartNote() {
        assertNull(CasmNoteTransformer.transform(60, cMajor, policy(low = 65, high = 90)))
    }
}
