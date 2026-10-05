package com.yourapp.yamahaarranger.ui

import com.yourapp.audio.DrumShadowPlanner
import com.yourapp.audio.Sf2SemanticInventory
import com.yourapp.audio.Sf2SemanticInventoryTest
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.arranger.ArrangerState
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.style.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Actual export boundary: two observational getters, never an audio/MIDI/arranger mutation. */
class DrumShadowReadOnlyTest {
    private class Fixture(playing:Boolean=false,target:com.yourapp.audio.DrumSemanticEvidenceRegistry.Target?=null) {
        val vm=mock(MainViewModel::class.java,CALLS_REAL_METHODS)
        val files=mock(ContentResolverProvider::class.java)
        val audio=mock(AudioEngineManager::class.java)
        val brain=mock(ArrangerBrain::class.java)
        val midi=mock(MidiInputManager::class.java)
        val styles=mock(StyleRepository::class.java)
        val state=MainUiState(isPlaying=playing)
        val flow=MutableStateFlow(state)
        val style=ParsedStyle("fixture",1920,mapOf("Main" to StyleSectionModel("Main",100,listOf(
            StylePartModel("Rhythm",listOf(StyleNoteEvent(10,true,target?.key?:60,42,9)),program=target?.rawPc?:12,bankMsb=target?.msb?:121,bankLsb=target?.lsb?:3)))))
        fun field(name:String,value:Any?) {MainViewModel::class.java.getDeclaredField(name).apply {isAccessible=true}.set(vm,value)}
        init {
            for((name,value) in listOf("contentResolver" to files,"audioEngine" to audio,"arrangerBrain" to brain,
                "midiInputManager" to midi,"styleRepository" to styles,"uiState" to flow,"drumAuditStyle" to style,
                "drumShadowCache" to DrumShadowPlanner.Cache()))field(name,value)
            doReturn(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("id","fixture.sf2") {
                ByteArrayInputStream(Sf2SemanticInventoryTest.fixture())
            }))).`when`(files).sf2MetadataSources()
            doReturn("GEN value=7\nLIVE ch=9 inputBank=15491 inputPC=12 source=6964 bank=128 pc=36 verified=1\n").`when`(audio).shadowDrumSnapshot()
        }
        fun unchanged(readSnapshot:Boolean=false) {
            if(readSnapshot)verify(audio,times(2)).shadowDrumSnapshot()
            verifyNoMoreInteractions(audio);verifyNoInteractions(brain,midi,styles)
            assertSame(state,vm.uiState.value)
            assertSame(style,MainViewModel::class.java.getDeclaredField("drumAuditStyle").apply {isAccessible=true}.get(vm))
        }
    }
    // Exercise the real authoritative owner/formatter. Keep the separate mock
    // brain in Fixture for the unchanged shadow tests; it cannot run the new
    // session export because constructor-owned state would be null.
    private class PartSession(val fixture: Fixture, loaded: Boolean = true) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val brain = spy(ArrangerBrain(fixture.audio, mock(ChordDetector::class.java), fixture.midi))
        init {
            brain.attachScope(scope)
            if (loaded) brain.loadStyle(fixture.style)
            if (fixture.state.isPlaying) {
                @Suppress("UNCHECKED_CAST")
                val state = ArrangerBrain::class.java.getDeclaredField("_state").apply { isAccessible = true }
                    .get(brain) as MutableStateFlow<ArrangerState>
                state.value = state.value.copy(isPlaying = true)
            }
            fixture.field("arrangerBrain", brain)
            fixture.field("drumAuditStyle", null) // UI-local cache is irrelevant.
            clearInvocations(brain, fixture.audio)
        }
        fun readOnly() {
            val allowed = setOf("compactPartPresenceReport", "diagnosticActiveStyle", "partPresenceReport", "getState")
            assertTrue(mockingDetails(brain).invocations.all { it.method.name in allowed })
            verifyNoInteractions(fixture.files, fixture.midi, fixture.styles, fixture.brain)
            assertSame(fixture.state, fixture.vm.uiState.value)
        }
        override fun close() { scope.cancel() }
    }
    @Test fun partPresenceExportCallsOnlySchedulerAndNativeReadOnlyGetters() = runBlocking {
        val f=Fixture()
        PartSession(f).use { session ->
            doReturn("NATIVE ch=9 BASS_NOTE_ON_SENT=3\nROLE_PCM_SCOPE\nROLE_LAYER ch=10\n=== ACTUAL BASS / STRINGS NOTE ZONES ===\nold detailed rows").`when`(f.audio).noteZoneReport()
            val out=f.vm.compactPartPresenceReport()
            assertTrue(out.contains("parsedRaw=1"));assertTrue(out.contains("BASS_NOTE_ON_SENT=3"))
            assertTrue(out.contains("ROLE_PCM_SCOPE"));assertTrue(out.contains("ROLE_LAYER"))
            assertFalse(out.contains("old detailed rows"));assertTrue(out.toByteArray().size<=48*1024)
            verify(session.brain).partPresenceReport(f.style);verify(f.audio).noteZoneReport()
            verifyNoMoreInteractions(f.audio);session.readOnly()
        }
    }
    @Test fun partPresenceWhilePlayingRejectsBeforeAnyPlaybackOrFilesystemInteraction() = runBlocking {
        val f=Fixture(true)
        PartSession(f).use { session ->
            assertTrue(f.vm.compactPartPresenceReport().contains("STOP"))
            verifyNoInteractions(f.audio);session.readOnly()
        }
    }
    @Test fun partPresenceWithoutStyleRejectsWithoutReadingOrChangingEngine() = runBlocking {
        val f=Fixture()
        PartSession(f,loaded=false).use { session ->
            assertTrue(f.vm.compactPartPresenceReport().contains("load a style"))
            verifyNoInteractions(f.audio);session.readOnly()
        }
    }
    @Test fun shadowScanCompileExportCallsOnlyReadOnlySnapshotAndKeepsProductionState() = runBlocking {
        val f=Fixture();val original=f.style.toString();val out=f.vm.exportShadowDrum()
        assertTrue(out.contains("productionDispatch=UNCHANGED"));assertTrue(out.contains("shadow=ABSTAIN"))
        assertTrue(out.contains("msb=121, lsb=3, rawPc=12"));assertTrue(out.contains("productionKey=UNKNOWN"))
        assertEquals(original,f.style.toString());f.unchanged(true)
        assertTrue(out.contains("ENGINEERING_PROOF_HEADER"));assertTrue(out.contains("productionActivation=NONE"))
        verify(f.files).sf2MetadataSources();verifyNoMoreInteractions(f.files)
    }
    @Test fun playingRejectsBeforeScanSnapshotOrAnyProductionCall() = runBlocking {
        val f=Fixture(true)
        try {f.vm.exportShadowDrum();fail("STOP required")} catch(_:IllegalStateException) {}
        verifyNoInteractions(f.files);f.unchanged()
    }
    @Test fun missingStyleRejectsBeforeInventoryOrNativeGetter() = runBlocking {
        val f=Fixture();f.field("drumAuditStyle",null)
        try {f.vm.exportShadowDrum();fail("style required")} catch(_:IllegalStateException) {}
        verifyNoInteractions(f.files,f.audio,f.brain,f.midi,f.styles);assertSame(f.state,f.vm.uiState.value)
    }
    @Test fun malformedSourceRejectsWithoutTouchingAudioOrArranger() = runBlocking {
        val f=Fixture()
        doReturn(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("bad","bad.sf2") {
            ByteArrayInputStream(byteArrayOf(1,2,3))
        }))).`when`(f.files).sf2MetadataSources()
        try {f.vm.exportShadowDrum();fail("incomplete inventory must fail closed")} catch(_:IllegalStateException) {}
        f.unchanged()
    }
    @Test fun playbackStartedDuringScanRejectsBeforeSnapshotAndSendsNoEvents() = runBlocking {
        val f=Fixture()
        doReturn(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("id","fixture.sf2") {
            f.flow.value=f.state.copy(isPlaying=true)
            ByteArrayInputStream(Sf2SemanticInventoryTest.fixture())
        }))).`when`(f.files).sf2MetadataSources()
        try {f.vm.exportShadowDrum();fail("changed playback must discard plan")} catch(_:IllegalStateException) {}
        verifyNoInteractions(f.audio,f.brain,f.midi,f.styles);assertTrue(f.vm.uiState.value.isPlaying)
    }
    @Test fun importedClaimsStillCannotCauseSynthEventOrStateMutation() = runBlocking {
        val f=Fixture();val sha=DrumShadowPlanner.digest(Sf2SemanticInventoryTest.fixture())
        val report=f.vm.exportShadowDrum("121|3|12|60|$sha|128|36|62|COMPATIBLE|80|reviewed fixture")
        assertTrue(report.contains("crossKey=true"));assertTrue(report.contains("shadow=ABSTAIN"))
        assertTrue(report.contains("UNPROVEN_NOTE_OWNERSHIP"));f.unchanged(true)
    }
    @Test fun pass2RegistryExportKeepsActualVmAudioAndArrangerReadOnlyEvenWithFingerprintFailures() = runBlocking {
        val target=com.yourapp.audio.DrumSemanticEvidenceRegistry.bundled().targets.single {it.id=="hat-pedal-closed"}
        val f=Fixture(target=target);val out=f.vm.exportShadowDrum()
        assertTrue(out.contains("REGISTRY version=audit770-semantic-v1"));assertTrue(out.contains("class=COMPATIBLE"))
        assertTrue(out.contains("FINGERPRINT_MISMATCH_OR_FONT_ABSENT"));assertTrue(out.contains("ACTUAL_RUNTIME_DISPATCH=UNKNOWN"))
        assertTrue(out.contains("shadow=ABSTAIN"));f.unchanged(true)
    }

    @Test fun generationChangeDuringPreparationExportsFailureWithoutChangingPlayback() = runBlocking {
        val target=com.yourapp.audio.DrumSemanticEvidenceRegistry.bundled().targets.single {it.id=="hat-pedal-closed"}
        val f=Fixture(target=target)
        doReturn("GEN value=7\n","GEN value=8\n").`when`(f.audio).shadowDrumSnapshot()
        val out=f.vm.exportShadowDrum()
        assertTrue(out.contains("GENERATION_OR_HANDLE_SNAPSHOT_CHANGED"));assertTrue(out.contains("resourceReady=FAIL"))
        f.unchanged(true)
    }

    @Test fun playbackChangedAtSecondGetterDiscardsEngineeringReportWithoutEvents() = runBlocking {
        val f=Fixture();var reads=0
        doAnswer { reads++;if(reads==2)f.flow.value=f.state.copy(isPlaying=true);"GEN value=7\n" }.`when`(f.audio).shadowDrumSnapshot()
        try { f.vm.exportShadowDrum();fail("playback change must discard report") } catch(_:IllegalStateException) {}
        verify(f.audio,times(2)).shadowDrumSnapshot();verifyNoMoreInteractions(f.audio);verifyNoInteractions(f.brain,f.midi,f.styles)
    }

    @Test fun genericFullDemandExportCannotDispatchOrMutateActualVmState() = runBlocking {
        val target=com.yourapp.audio.DrumSemanticEvidenceRegistry.bundled().targets.single {it.id=="hat-pedal-closed"}
        val f=Fixture(target=target);val out=f.vm.exportGenericDrumShadow(requestExperimental=true)
        assertTrue(out.contains("GENERIC DRUM RESOLVER SHADOW"));assertTrue(out.contains("enabled=false"))
        assertTrue(out.contains("outcome=ABSTAIN"));f.unchanged(true)
    }
    @Test fun genericPlayingRejectsBeforeScanOrAnyAudioInteraction() = runBlocking {
        val f=Fixture(true)
        try { f.vm.exportGenericDrumShadow();fail("STOP required") } catch(_:IllegalStateException) {}
        verifyNoInteractions(f.files);f.unchanged()
    }

}
