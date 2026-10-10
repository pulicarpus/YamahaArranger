package com.yourapp.yamahaarranger.lifecycle

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReadyToPlayGateTest {
    private fun ready(): ReadyToPlayGate = ReadyToPlayGate().also { g ->
        val f = g.beginFonts(); g.installedFonts(f, true, "melody", "drum"); g.finishFonts(f, true)
        val s = g.beginStyle(); g.finishStyle(s, setOf("MainA", "FillAA"))
        g.outputResult(g.beginOutput(), true)
    }
    @Test fun noResourcesRejectsBackendStart() { assertFalse(ReadyToPlayGate().admit("MainA") { fail() }) }
    @Test fun missingStyleRejects() {
        val g=ReadyToPlayGate();val f=g.beginFonts();g.installedFonts(f,true,"m","d");g.finishFonts(f,true);g.outputResult(g.beginOutput(),true)
        assertFalse(g.admit("MainA") { fail() })
    }
    @Test fun missingDrumRejects() {
        val g=ready();g.clearFonts();val f=g.beginFonts();g.installedFonts(f,true,"m",null);g.finishFonts(f,true)
        assertFalse(g.admit("MainA") { fail() })
    }
    @Test fun allRequiredResourcesAdmitExactlyOnce() {
        val g=ready();var n=0;assertTrue(g.admit("MainA") { n++ });assertEquals(1,n)
    }
    @Test fun sf2LoadingRejectsWithoutAutostart() {
        val g=ready();val f=g.beginFonts();var n=0;assertFalse(g.admit("MainA") {n++})
        g.installedFonts(f,true,"new-m","new-d");g.finishFonts(f,true);assertEquals(0,n);assertTrue(g.state.value.permits("MainA"))
    }
    @Test fun failedSf2DoesNotRestoreOldReady() {
        val g=ready();val f=g.beginFonts();g.installedFonts(f,false,null,null);g.finishFonts(f,false)
        assertEquals(ReadyToPlayGate.Phase.ERROR,g.state.value.phase);assertFalse(g.admit("MainA") {fail()})
    }
    @Test fun retryAfterSf2Failure() {
        val g=ready();val old=g.beginFonts();g.finishFonts(old,false);val f=g.beginFonts();g.installedFonts(f,true,"m","d");g.finishFonts(f,true)
        assertTrue(g.state.value.permits("MainA"))
    }
    @Test fun staleFontCompletionCannotClearPending() {
        val g=ready();val a=g.beginFonts();val b=g.beginFonts();g.installedFonts(a,true,"a","a");g.finishFonts(a,true)
        assertEquals(ReadyToPlayGate.Phase.LOADING,g.state.value.phase);assertFalse(g.current(a));assertTrue(g.current(b))
    }
    @Test fun staleFailureCannotInvalidateCompletedLatest() {
        val g=ready();val a=g.beginFonts();val b=g.beginFonts();g.installedFonts(b,true,"b","b");g.finishFonts(b,true);g.finishFonts(a,false)
        assertTrue(g.state.value.permits("MainA"))
    }
    @Test fun styleLoadingRejects() {val g=ready();g.beginStyle();assertFalse(g.admit("MainA") {fail()})}
    @Test fun parseFailureIsError() {val g=ready();val s=g.beginStyle();g.finishStyle(s,null);assertEquals(ReadyToPlayGate.Phase.ERROR,g.state.value.phase)}
    @Test fun staleStyleCannotReplaceLatest() {
        val g=ready();val a=g.beginStyle();val b=g.beginStyle();g.finishStyle(b,setOf("MainB"));g.finishStyle(a,setOf("MainA"))
        assertTrue(g.state.value.permits("MainB"));assertFalse(g.state.value.permits("MainA"))
    }
    @Test fun invalidInitialSectionRejects() {assertFalse(ready().admit("MainD") {fail()})}
    @Test fun audioFailureRejects() {val g=ready();g.outputResult(g.beginOutput(),false);assertEquals(ReadyToPlayGate.Phase.ERROR,g.state.value.phase)}
    @Test fun failedResumeRejects() {val g=ready();val e=g.outputToken();g.paused(e);g.outputResult(e,false);assertFalse(g.admit("MainA") {fail()})}
    @Test fun stoppedOutputRejectsStaleResume() {
        val g=ready();val e=g.outputToken();g.invalidateOutput();g.outputResult(e,true);assertFalse(g.admit("MainA") {fail()})
    }
    @Test fun explicitEngineRestartCanReadyWithoutPretendingProcessSurvival() {
        val g=ready();g.invalidateOutput();g.outputResult(g.beginOutput(),true);assertTrue(g.state.value.permits("MainA"))
        assertFalse(ReadyToPlayGate().state.value.permits("MainA"))
    }
    @Test fun mutexBusyRejectsImmediately() {val g=ready();g.operations.tryLock();try {assertFalse(g.admit("MainA") {fail()})}finally{g.operations.unlock()}}
    @Test fun doubleStartDuringAdmissionRejects() {
        val g=ready();val entered=CountDownLatch(1);val release=CountDownLatch(1);val n=AtomicInteger()
        val t=Thread {assertTrue(g.admit("MainA") {n.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))})}
        t.start();assertTrue(entered.await(5,TimeUnit.SECONDS));assertFalse(g.admit("MainA") {n.incrementAndGet()});release.countDown();t.join(5000)
        assertFalse(t.isAlive);assertEquals(1,n.get())
    }
    @Test fun exceptionReleasesAdmissionMutex() {
        val g=ready();try {g.admit("MainA") {throw IllegalStateException("test")};fail()}catch(expected:IllegalStateException){}
        assertTrue(g.admit("MainA") {})
    }
    @Test fun noOptionalVoiceOrControllerPrerequisite() {assertTrue(ready().admit("MainA") {})}
    @Test fun resourceCommitCannotOverlapInitialStart() {
        val g=ready();assertTrue(g.admit("MainA") {assertFalse(g.operations.tryLock())})
    }
    @Test fun cancelledRequestCannotReadyButExplicitRetryCan() {
        val g=ready();val cancelled=g.beginFonts();g.finishFonts(cancelled,false)
        assertFalse(g.admit("MainA") {fail()})
        val retry=g.beginFonts();g.installedFonts(retry,true,"m","d");g.finishFonts(retry,true)
        assertTrue(g.state.value.permits("MainA"))
    }
    @Test fun staleStyleIdentityRejectsEvenWhenSectionNamesMatch() {
        val g=ready();val current=Any();g.finishStyle(g.beginStyle(),setOf("MainA"),current)
        assertFalse(g.admit("MainA",Any()) {fail()});assertTrue(g.admit("MainA",current) {})
    }
}
