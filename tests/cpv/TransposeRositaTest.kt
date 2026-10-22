package com.yourapp.yamahaarranger.cpv

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.StyleSequencer
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.audio.NativeAudioBridge
import com.yourapp.yamahaarranger.audio.SampleProvider
import com.yourapp.yamahaarranger.chord.ChordQuality
import com.yourapp.yamahaarranger.chord.DetectedChord
import com.yourapp.yamahaarranger.chord.transposedBy
import com.yourapp.yamahaarranger.style.CasmPolicyModel
import com.yourapp.yamahaarranger.style.ParsedStyle
import com.yourapp.yamahaarranger.style.StyleNoteEvent
import com.yourapp.yamahaarranger.style.StylePartModel
import com.yourapp.yamahaarranger.style.StyleSectionModel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails

/**
 * Real-style musical comparison on ROSITA.STY (parsed by the unmodified production C++ parser, rebuilt exactly as StyleRepository does).
 * The production StyleSequencer plays Main A once per scenario and the MIDI OUT notes are compared:
 *   A: chord F, transpose 0   B: chord C, transpose +5   C: chord G, transpose 0   D: chord C, transpose -5.
 * HOST evidence of the CASM result (pitches/channels); it says nothing about the sound on a device. Skipped when ROSITA_TSV is not set.
 */
class TransposeRositaTest {
    private fun load(): ParsedStyle? {
        val path = System.getenv("ROSITA_TSV") ?: return null
        val sections = linkedMapOf<String, StyleSectionModel>()
        var ppq = 480; var name = ""; var len = 0
        var parts = ArrayList<StylePartModel>()
        var events = ArrayList<StyleNoteEvent>(); var policies = ArrayList<CasmPolicyModel>(); var partName: String? = null
        fun closePart() {
            val n = partName ?: return
            var msb = 0; var lsb = 0; var program = -1
            events.filter { it.tick == 0 }.forEach { e -> when {
                e.isControlChange && e.note == 0 -> msb = e.velocity
                e.isControlChange && e.note == 32 -> lsb = e.velocity
                e.isProgramChange -> program = e.note } }
            parts += StylePartModel(n, events, policies.firstOrNull(), policies, program, msb, lsb)
            events = ArrayList(); policies = ArrayList(); partName = null
        }
        fun closeSection() { closePart(); if (name.isNotEmpty()) sections[name] = StyleSectionModel(name, len, parts); parts = ArrayList() }
        File(path).readLines(Charsets.ISO_8859_1).forEach { line ->
            val f = line.split('\t')
            when (f[0]) {
                "PPQ" -> ppq = f[1].toInt()
                "S" -> { closeSection(); name = f[1]; len = f[2].toInt() }
                "P" -> { closePart(); partName = f.getOrElse(1) { "" }.trim() }
                "C" -> policies += CasmPolicyModel(f[1].toInt(), f[2].toInt(), f[3].trim(), f[4].toInt(), f[5].toInt(), f[6].toInt(),
                    f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(), f[11].toInt(), f[12] == "1", f[13].toLong(), f[14].toInt(), f[15].toInt())
                "E" -> { val status = f[2].toInt()
                    if (status in 0x80..0xEF) events += StyleNoteEvent(f[1].toInt(), (status and 0xF0) == 0x90 && f[4].toInt() > 0, f[3].toInt(), f[4].toInt(), status and 0x0F, status) }
            }
        }
        closeSection()
        return ParsedStyle("ROSITA.STY", ppq, sections)
    }

    private fun play(style: ParsedStyle, chord: DetectedChord?): Map<Int, List<Int>> {
        val midi: MidiInputManager = mock(MidiInputManager::class.java)
        val engine = AudioEngineManager(mock(NativeAudioBridge::class.java), mock(SampleProvider::class.java))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seq = StyleSequencer(engine, midi, scope)
        try {
            seq.tempoBpm = 280
            seq.currentChord = chord
            val done = CountDownLatch(1)
            seq.play(style.sections.getValue("MainA"), style.ppq, loopLimit = 1, onComplete = { done.countDown() })
            assertTrue("Main A finished", done.await(30, TimeUnit.SECONDS))
            val ons = mockingDetails(midi).invocations.filter { it.method.name == "sendNoteOn" }.map { (it.arguments[0] as Int) to (it.arguments[1] as Int) }
            return ons.groupBy({ it.first }, { it.second }).mapValues { it.value.sorted() }
        } finally { seq.stop(); scope.cancel() }
    }

    @Test fun cMinusFiveIsGAndCPlusFiveIsFOnTheRealStyle() {
        val style = load(); assumeTrue("ROSITA_TSV not set", style != null); style!!
        val c = DetectedChord(0, 0, ChordQuality.MAJOR)
        val raw = play(style, c)
        val a = play(style, DetectedChord(5, 5, ChordQuality.MAJOR)); val b = play(style, c.transposedBy(5))
        val g = play(style, DetectedChord(7, 7, ChordQuality.MAJOR)); val d = play(style, c.transposedBy(-5))
        println("ROSITA_TRANSPOSE_TABLE channel: min..max (notes) raw-C | A:F | B:C+5 | C:G | D:C-5")
        for (ch in (raw.keys + a.keys + g.keys).toSortedSet()) {
            fun r(m: Map<Int, List<Int>>) = m[ch]?.let { "${it.minOrNull()}..${it.maxOrNull()} (${it.size})" } ?: "-"
            println("ROSITA_TRANSPOSE_TABLE ch$ch: ${r(raw)} | ${r(a)} | ${r(b)} | ${r(g)} | ${r(d)}")
        }
        assertTrue("real style produced MIDI", raw.isNotEmpty())
        assertEquals("C +5 sounds exactly like F (every channel)", a, b)
        assertEquals("C -5 sounds exactly like G (every channel)", g, d)
        assertTrue("the style is chord sensitive (C != F)", raw != a)
        for (drum in listOf(8, 9)) { assertEquals("drum ch$drum untouched by F", raw[drum], a[drum]); assertEquals("drum ch$drum untouched by G", raw[drum], g[drum]) }
        for (m in listOf(raw, a, b, g, d)) assertTrue(m.values.flatten().all { it in 0..127 })
    }
}
