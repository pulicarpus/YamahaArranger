package com.yourapp.yamahaarranger.ui

import com.yourapp.audio.Sf2SemanticInventory
import com.yourapp.audio.Sf2SemanticInventoryTest
import com.yourapp.midi.MidiInputManager
import com.yourapp.yamahaarranger.arranger.ArrangerBrain
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.style.StyleRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Invokes the actual ViewModel export with observable audio/MIDI/arranger boundaries. */
class Sf2MetadataReadOnlyTest {
    private class Fixture(playing: Boolean = false) {
        // Bypass init/autoload, so interactions belong to the production diagnostic method alone.
        val vm = mock(MainViewModel::class.java, CALLS_REAL_METHODS)
        val files = mock(ContentResolverProvider::class.java)
        val audio = mock(AudioEngineManager::class.java)
        val brain = mock(ArrangerBrain::class.java)
        val midi = mock(MidiInputManager::class.java)
        val styles = mock(StyleRepository::class.java)
        val state = MainUiState(isPlaying=playing)
        init {
            for ((field,value) in listOf("contentResolver" to files, "audioEngine" to audio,
                "arrangerBrain" to brain, "midiInputManager" to midi, "styleRepository" to styles,
                "uiState" to MutableStateFlow(state))) {
                MainViewModel::class.java.getDeclaredField(field).apply { isAccessible=true }.set(vm,value)
            }
            doReturn(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("id","test.sf2") {
                ByteArrayInputStream(Sf2SemanticInventoryTest.fixture())
            }))).`when`(files).sf2MetadataSources()
        }
        fun assertReadOnly() {
            verifyNoInteractions(audio,brain,midi,styles)
            assertSame(state,vm.uiState.value)
        }
    }
    @Test fun compactExportDoesNotTouchNotesPresetMixerMappingOrArrangerState() = runBlocking {
        val f=Fixture();val output=ByteArrayOutputStream()
        val result=f.vm.exportSf2Metadata(output,false)
        assertEquals(1,result.valid); assertTrue(output.size()>0);f.assertReadOnly()
        verify(f.files).sf2MetadataSources(); verifyNoMoreInteractions(f.files)
    }
    @Test fun fullExportDoesNotLoadActivateAuditionOrModifyPlayback() = runBlocking {
        val f=Fixture(); val output=ByteArrayOutputStream()
        val result=f.vm.exportSf2Metadata(output,true)
        assertEquals(1,result.valid);assertEquals('P'.code,output.toByteArray()[0].toInt());f.assertReadOnly()
        verify(f.files).sf2MetadataSources(); verifyNoMoreInteractions(f.files)
    }
    @Test fun exportWhilePlayingIsRejectedBeforeOpeningAnyManagedFile() = runBlocking {
        val f=Fixture(true);val output=ByteArrayOutputStream()
        try { f.vm.exportSf2Metadata(output,false);fail("must STOP first") } catch (_: IllegalStateException) {}
        assertEquals(0,output.size());verifyNoInteractions(f.files);f.assertReadOnly()
    }
    @Test fun failedSourceStillDoesNotTouchPlayback() = runBlocking {
        val f=Fixture()
        doReturn(Sf2SemanticInventory.Discovery(listOf(Sf2SemanticInventory.Source("bad","bad.sf2") {
            throw java.io.IOException("denied")
        }))).`when`(f.files).sf2MetadataSources()
        val result=f.vm.exportSf2Metadata(ByteArrayOutputStream(),false)
        assertEquals(1,result.failed);f.assertReadOnly()
    }
    @Test fun auditionFingerprintScanDoesNotTouchProductionAudioOrArrangerState() = runBlocking {
        val f=Fixture();val fonts=f.vm.managedAuditionFonts()
        assertEquals(1,fonts.size);assertEquals(64,fonts.single().sha256.length);f.assertReadOnly()
    }
    @Test fun managedCrossKeyAuditionCallsOnlyIsolatedBackendAndKeepsArrangerState() = runBlocking {
        val f=Fixture();val font=f.vm.managedAuditionFonts().single()
        val dir=kotlin.io.path.createTempDirectory("vm-audition-test").toFile();var calls=0
        try {
            val request=com.yourapp.audio.ManagedSf2Audition.Request(font,128,36,62,42)
            val result=f.vm.managedSf2Audition(request,dir) { file,req ->
                calls++;assertTrue(file.isFile);assertEquals(request,req)
                com.yourapp.audio.ManagedSf2Audition.NativeResult("RIFF".toByteArray()+ByteArray(40),"actual verified")
            }
            assertEquals(1,calls);assertTrue(result.sidecar.contains("sourceKey=62"))
            assertTrue(dir.listFiles()!!.isEmpty());f.assertReadOnly()
        } finally {dir.deleteRecursively()}
    }
    @Test fun managedAuditionWhilePlayingRejectsBeforeDiscoveryOrNative() = runBlocking {
        val f=Fixture(true);var calls=0;val dir=kotlin.io.path.createTempDirectory("vm-audition-test").toFile()
        try {
            val font=com.yourapp.audio.ManagedSf2Audition.Font("id","test.sf2","a".repeat(64))
            try { f.vm.managedSf2Audition(com.yourapp.audio.ManagedSf2Audition.Request(font,128,36,62,42),dir) { _,_ ->
                calls++;error("must not render") };fail() } catch (_: IllegalStateException) {}
            assertEquals(0,calls);verifyNoInteractions(f.files);f.assertReadOnly();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun rejectedManagedAuditionDoesNotLoadPresetSendNoteOrMutateState() = runBlocking {
        val f=Fixture();val font=f.vm.managedAuditionFonts().single();var calls=0
        val dir=kotlin.io.path.createTempDirectory("vm-audition-test").toFile()
        try {
            try { f.vm.managedSf2Audition(com.yourapp.audio.ManagedSf2Audition.Request(font,128,36,21,42),dir) { _,_ ->
                calls++;error("must not render") };fail() } catch (_: IllegalStateException) {}
            assertEquals(0,calls);f.assertReadOnly();assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
}
