package com.yourapp.yamahaarranger.lifecycle

import com.yourapp.yamahaarranger.audio.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReadyToPlayEngineTest {
    private fun engine(): Pair<AudioEngineManager, NativeAudioBridge> {
        val b=mock(NativeAudioBridge::class.java)
        `when`(b.nativeStart()).thenReturn(true)
        `when`(b.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
        `when`(b.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
        val e=AudioEngineManager(b, object:SampleProvider {override fun sampleForNote(midiNote:Int):NoteSample?=null})
        e.readiness.finishStyle(e.readiness.beginStyle(),setOf("MainA"))
        return e to b
    }
    @Test fun corePairAndOutputUseActualBooleanResults() {
        val(e,b)=engine();assertTrue(e.start());assertTrue(e.loadSoundFontPair("m","d"));assertTrue(e.readiness.state.value.permits("MainA"))
        verify(b).nativeLoadMelodySoundFont("m");verify(b).nativeLoadDrumSoundFont("d")
    }
    @Test fun partialCoreFailureCannotReady() {
        val(e,b)=engine();e.start();`when`(b.nativeLoadDrumSoundFont("d")).thenReturn(false)
        assertFalse(e.loadSoundFontPair("m","d"));assertEquals(ReadyToPlayGate.Phase.ERROR,e.readiness.state.value.phase)
    }
    @Test fun optionalFallbackIsNotAnAdmissionPrerequisite() {
        val(e,b)=engine();e.start();`when`(b.nativeLoadMelodyFallbackSoundFont("optional")).thenReturn(false)
        assertTrue(e.loadSoundFontPairWithFallback("m","optional","d"));assertTrue(e.readiness.state.value.permits("MainA"))
    }
    @Test fun outputStartFailureCannotReady() {
        val(e,b)=engine();`when`(b.nativeStart()).thenReturn(false);assertFalse(e.start());e.loadSingleSoundFont("f")
        assertEquals(ReadyToPlayGate.Phase.ERROR,e.readiness.state.value.phase)
    }
    @Test fun outputResumeFailureCannotReady() {
        val(e,b)=engine();`when`(b.nativeStart()).thenReturn(true,false);e.start();assertTrue(e.loadSingleSoundFont("f"))
        assertEquals(ReadyToPlayGate.Phase.ERROR,e.readiness.state.value.phase)
    }
    @Test fun staleRequestCannotInvokeNativeLoad() {
        val(e,b)=engine();val old=e.readiness.beginFonts();e.readiness.beginFonts()
        assertFalse(e.loadSingleSoundFont("old",old));verify(b,never()).nativeLoadMelodySoundFont(anyString())
    }
    @Test fun unloadInvalidatesEvenWhenLegacyOrFlagWasTrue() {
        val(e,_)=engine();e.start();e.loadSingleSoundFont("f");e.unloadSoundFont()
        assertFalse(e.readiness.state.value.permits("MainA"))
    }
    @Test fun stopDuringLoadPreventsStaleResume() {
        val(e,b)=engine();e.start();val entered=CountDownLatch(1);val release=CountDownLatch(1)
        `when`(b.nativeLoadMelodySoundFont("m")).thenAnswer {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));true}
        val t=Thread {e.loadSoundFontPair("m","d")};t.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        e.stop();release.countDown();t.join(5000);assertFalse(t.isAlive)
        assertFalse(e.readiness.state.value.permits("MainA"));verify(b,times(1)).nativeStart()
    }
    @Test fun actualPhysicalLoadExcludesInitialAdmission() {
        val(e,b)=engine();e.start();e.loadSingleSoundFont("initial");val entered=CountDownLatch(1);val release=CountDownLatch(1)
        `when`(b.nativeLoadMelodySoundFont("new")).thenAnswer {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));true}
        val t=Thread {e.loadSingleSoundFont("new")};t.start();assertTrue(entered.await(5,TimeUnit.SECONDS))
        assertFalse(e.readiness.admit("MainA") {fail("must not wait or start")});release.countDown();t.join(5000);assertFalse(t.isAlive)
    }
    @Test fun thrownNativeLoadCannotLeaveOldBindingsCertified() {
        val(e,b)=engine();e.start();e.loadSingleSoundFont("old")
        `when`(b.nativeLoadMelodySoundFont("bad")).thenThrow(IllegalStateException("native boundary failed"))
        try {e.loadSoundFontPair("bad","d");fail()} catch(expected:IllegalStateException) {}
        assertEquals(ReadyToPlayGate.Phase.ERROR,e.readiness.state.value.phase)
        assertFalse(e.readiness.admit("MainA") {fail()})
        assertTrue(e.loadDrumSoundFont("d"))
        assertFalse(e.readiness.state.value.permits("MainA"))
    }
}
