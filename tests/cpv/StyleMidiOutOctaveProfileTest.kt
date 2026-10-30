package com.yourapp.yamahaarranger.cpv

import com.yourapp.yamahaarranger.style.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

/** MIDI OUT octave correction metadata: per Style (keyed by the Style's content hash), persisted beside the Style file, absent in old profiles, 0 = no entry. */
class StyleMidiOutOctaveProfileTest {
    private val shaA = StyleOverrideStore.sha256("styleA".toByteArray())
    private val shaB = StyleOverrideStore.sha256("styleB".toByteArray())
    private fun base(sha: String = shaA) = StyleOverrideRecord(sha, "Some.STY", emptyList())

    @Test fun zeroIsNoEntryAndValuesAreBoundedToMelodicParts() {
        var r = base()
        assertEquals(0, r.midiOutOctave(10)); assertTrue(r.isEmpty)
        r = r.withMidiOutOctave(10, -2).withMidiOutOctave(13, 1)
        assertEquals(-2, r.midiOutOctave(10)); assertEquals(1, r.midiOutOctave(13)); assertEquals(0, r.midiOutOctave(11)); assertFalse(r.isEmpty)
        r = r.withMidiOutOctave(10, 0)
        assertEquals("0 removes the entry", listOf(13), r.midiOutOctaves.map { it.channel })
        for (bad in listOf(8 to 1, 9 to 1, 0 to 1, 16 to 1, 10 to 3, 10 to -3)) assertThrows(IllegalArgumentException::class.java) { r.withMidiOutOctave(bad.first, bad.second) }
    }

    @Test fun roundTripThroughTheStoreAndRestart() {
        val dir = Files.createTempDirectory("midioct")
        val rec = base().withMidiOutOctave(10, -2).withMidiOutOctave(11, 1).withMidiOutOctave(15, 2).copy(savedAt = 7)
        StyleOverrideStore(dir).write(rec)
        val back = StyleOverrideStore(dir).read(shaA)   // a fresh store object = an app restart
        assertEquals(OverrideReadStatus.VALID, back.status)
        assertEquals(listOf(StyleMidiOutOctave(10, -2), StyleMidiOutOctave(11, 1), StyleMidiOutOctave(15, 2)), back.record!!.midiOutOctaves)
    }

    @Test fun settingsNeverLeakBetweenStyles() {
        val dir = Files.createTempDirectory("midioct2")
        val store = StyleOverrideStore(dir)
        store.write(base(shaA).withMidiOutOctave(10, -2).copy(savedAt = 1))
        store.write(base(shaB).withMidiOutOctave(11, 2).copy(savedAt = 2))
        assertEquals(listOf(StyleMidiOutOctave(10, -2)), store.read(shaA).record!!.midiOutOctaves)
        assertEquals(listOf(StyleMidiOutOctave(11, 2)), store.read(shaB).record!!.midiOutOctaves)
        val third = StyleOverrideStore.sha256("styleC".toByteArray())
        assertNull("a Style without a stored profile has no correction", store.read(third).record)
    }

    @Test fun profilesWithoutTheFieldAreByteIdenticalAndStillDecode() {
        val old = base().copy(savedAt = 3)
        val text = StyleOverrideCodec.encode(old)
        assertFalse("no key is written when there is no correction: old profiles stay exactly as they were", text.contains("midiOutOctaves"))
        val back = StyleOverrideCodec.decode(text)
        assertTrue(back.midiOutOctaves.isEmpty()); assertEquals(old.octaveShifts, back.octaveShifts)
        val withIt = StyleOverrideCodec.encode(old.withMidiOutOctave(12, -1))
        assertTrue(withIt.contains("midiOutOctaves")); assertEquals(-1, StyleOverrideCodec.decode(withIt).midiOutOctave(12))
        assertEquals("schema stays the shipped one", 3L, StyleOverrideCodec.SCHEMA)
    }

    @Test fun invalidStoredValuesAreRejectedNotApplied() {
        val good = StyleOverrideCodec.encode(base().withMidiOutOctave(10, 1))
        assertThrows(Exception::class.java) { StyleOverrideCodec.decode(good.replace("\"octaves\":1", "\"octaves\":3")) }  // CRC and range both refuse it
        assertThrows(IllegalArgumentException::class.java) { StyleOverrideCodec.encode(base().copy(midiOutOctaves = listOf(StyleMidiOutOctave(9, 1)))) }
        assertThrows(IllegalArgumentException::class.java) { StyleOverrideCodec.encode(base().copy(midiOutOctaves = listOf(StyleMidiOutOctave(10, 0)))) }
    }

    @Test fun resetsAndTheRestorerCoverTheCorrection() {
        val r = base().withMidiOutOctave(10, -2).withMidiOutOctave(14, 1)
        assertTrue(r.withoutPart(10).midiOutOctaves.map { it.channel } == listOf(14))
        assertTrue("RESET ALL clears it", r.cleared().midiOutOctaves.isEmpty() && r.cleared().isEmpty)
        assertEquals("RESET SECTION keeps the part-wide MIDI OUT correction", r.midiOutOctaves, r.withoutSection("MainA").midiOutOctaves)
        val applied = mutableListOf<Pair<Int, Int>>()
        val results = StyleOverrideRestorer.restore(r, pdtaByFile = { emptyMap() }, loadedPresetName = { null }, setSf2Pin = { _, _, _ -> true }, setExternal = { _, _, _ -> true },
            setMidiOutOctave = { c, o -> applied += c to o; true })
        assertEquals(listOf(10 to -2, 14 to 1), applied)
        assertEquals(listOf("MIDI_OUT_OCTAVE", "MIDI_OUT_OCTAVE"), results.map { it.kind })
    }
}
