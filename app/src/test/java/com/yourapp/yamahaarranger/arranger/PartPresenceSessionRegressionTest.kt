package com.yourapp.yamahaarranger.arranger

import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.style.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Real ArrangerBrain export entry point used by the Inspector's ViewModel. */
class PartPresenceSessionRegressionTest {
    private fun style(name: String) = ParsedStyle(name, 480, mapOf("MainD" to
        StyleSectionModel("MainD", 480, listOf(StylePartModel("Bass", listOf(
            StyleNoteEvent(0,true,60,42,10)), casm = CasmPolicyModel(10,10,"Bass",0,0,0,0,11,0,127,1,false,-1))))))
    @Test fun loadedPlaybackStyleExportsEvenWhenUiDiagnosticCacheIsAbsent() = runBlocking {
        val audio=mock(AudioEngineManager::class.java)
        val brain=ArrangerBrain(audio, mock(ChordDetector::class.java), mock(MidiInputManager::class.java))
        val first=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val replacement=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            brain.attachScope(first);val loaded=style("Love Song.T547.prs");brain.loadStyle(loaded)
            // New UI scope has no previous ViewModel-local drumAuditStyle. Playback
            // and export must retain the singleton's actual loaded style reference.
            brain.attachScope(replacement)
            assertSame(loaded,brain.diagnosticActiveStyle())
            clearInvocations(audio)
            val caller=Thread.currentThread()
            `when`(audio.noteZoneReport()).thenAnswer {
                assertNotSame("JNI snapshot stays off the caller/UI thread",caller,Thread.currentThread())
                "ROLE_PCM ch=10 dryRmsSpan=0.1\nROLE_LAYER ch=10 sample='bass'\n=== ACTUAL BASS / STRINGS NOTE ZONES ===\nlarge detail"
            }
            val report=brain.compactPartPresenceReport()
            assertFalse(report.contains("unavailable"))
            assertTrue(report.contains("style='Love Song.T547.prs'"))
            assertTrue(report.contains("ROLE_PCM"));assertTrue(report.contains("ROLE_LAYER"))
            assertFalse(report.contains("large detail"))
            verify(audio).noteZoneReport();verifyNoMoreInteractions(audio)
        } finally {first.cancel();replacement.cancel()}
    }
    @Test fun genuinelyUnloadedStateDoesNotCallNativeAndReplacementUsesNewStyle() = runBlocking {
        val audio=mock(AudioEngineManager::class.java)
        val brain=ArrangerBrain(audio,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        assertTrue(brain.compactPartPresenceReport().contains("load a style first"))
        verifyNoInteractions(audio)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            brain.attachScope(scope);brain.loadStyle(style("Old"));val current=style("New");brain.loadStyle(current)
            clearInvocations(audio);`when`(audio.noteZoneReport()).thenReturn("ROLE_PCM_SCOPE\n")
            assertSame(current,brain.diagnosticActiveStyle())
            val report=brain.compactPartPresenceReport();assertTrue(report.contains("style='New'"));assertFalse(report.contains("style='Old'"))
            verify(audio).noteZoneReport();verifyNoMoreInteractions(audio)
        } finally {scope.cancel()}
    }
    @Test fun runningPlaybackIsRejectedUsingAuthoritativeStateBeforeNativeSnapshot() = runBlocking {
        val audio=mock(AudioEngineManager::class.java)
        val brain=ArrangerBrain(audio,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            brain.attachScope(scope);brain.loadStyle(style("Running"))
            brain.selectMainVariation(ArrangerSection.MainD);brain.startStop()
            assertTrue(brain.state.value.isPlaying)
            assertTrue(brain.compactPartPresenceReport().contains("STOP before export"))
            verify(audio,never()).noteZoneReport()
            brain.startStop()
        } finally {scope.cancel()}
    }
    @Test fun styleChangeDuringSnapshotIsRejectedInsteadOfCombiningSessions() = runBlocking {
        val audio=mock(AudioEngineManager::class.java)
        val brain=ArrangerBrain(audio,mock(ChordDetector::class.java),mock(MidiInputManager::class.java))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            brain.attachScope(scope);brain.loadStyle(style("Old"))
            `when`(audio.noteZoneReport()).thenAnswer {brain.loadStyle(style("New"));"ROLE_PCM_SCOPE\n"}
            assertTrue(brain.compactPartPresenceReport().contains("style/playback changed"))
        } finally {scope.cancel()}
    }
}
