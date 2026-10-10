package com.yourapp.yamahaarranger.lifecycle

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.*
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Actual initial admission and Stop; native/audio are not an oracle here. */
class ReadyToPlayBrainTest {
    private fun style() = ParsedStyle("readiness",480,mapOf(
        "MainA" to StyleSectionModel("MainA",4800,emptyList()),
        "MainB" to StyleSectionModel("MainB",4800,emptyList())))
    private fun ready(g:ReadyToPlayGate,s:ParsedStyle) {
        val f=g.beginFonts();g.installedFonts(f,true,"m","d");g.finishFonts(f,true)
        g.finishStyle(g.beginStyle(),s.sections.keys,s);g.outputResult(g.beginOutput(),true)
    }
    @Test fun bypassedUiCannotStartOrQueueAutostart() {
        val a=mock(AudioEngineManager::class.java);val g=ReadyToPlayGate();`when`(a.readiness).thenReturn(g)
        val b=ArrangerBrain(a,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            b.attachScope(scope);val s=style();b.loadStyle(s);b.startStop();assertFalse(b.state.value.isPlaying)
            ready(g,s);assertFalse(b.state.value.isPlaying);b.startStop();assertTrue(b.state.value.isPlaying)
            g.beginFonts();b.startStop();assertFalse(b.state.value.isPlaying)
        } finally {scope.cancel()}
    }
    @Test fun pendingResourceDoesNotGateRunningMainContinuationOrStop() {
        val a=mock(AudioEngineManager::class.java);val g=ReadyToPlayGate();`when`(a.readiness).thenReturn(g)
        val b=ArrangerBrain(a,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            b.attachScope(scope);val s=style();b.loadStyle(s);ready(g,s);b.startStop();assertTrue(b.state.value.isPlaying)
            g.beginFonts();clearInvocations(a);b.selectMainVariation(ArrangerSection.MainB)
            assertTrue(b.state.value.isPlaying);b.startStop();assertFalse(b.state.value.isPlaying)
            verify(a,never()).readiness
        } finally {scope.cancel()}
    }
    @Test fun directStyleReplacementCannotUseOldStyleCertificate() {
        val a=mock(AudioEngineManager::class.java);val g=ReadyToPlayGate();`when`(a.readiness).thenReturn(g)
        val b=ArrangerBrain(a,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            b.attachScope(scope);val old=style();b.loadStyle(old);ready(g,old)
            b.loadStyle(style());b.startStop();assertFalse(b.state.value.isPlaying)
        } finally {scope.cancel()}
    }
}
