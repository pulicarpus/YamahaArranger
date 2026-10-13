package com.yourapp.yamahaarranger.registration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RegistrationCoordinatorTest {
    class Port : RegistrationPort {
        var state=RecallPlannerTest().current(); var delayMs=0L; var sf2Calls=0; var catalogCalls=0
        var start=CompletableDeferred<Unit>(); var gate:CompletableDeferred<Unit>?=null
        var manual=false; val events=mutableListOf<RecallStep>()
        override suspend fun snapshot()=state
        override suspend fun catalog():List<Sf2VoiceRef>{catalogCalls++;return listOf(RecallPlannerTest().voice,RecallPlannerTest().voice.copy(program=25))}
        override fun midiOutActive()=true
        override suspend fun applySf2(part:Part,voice:Sf2VoiceRef,isCurrent:()->Boolean):VoiceResult {
            sf2Calls++;start.complete(Unit); gate?.await();delay(delayMs)
            if(manual) return VoiceResult.SUPERSEDED
            state=state.copy(parts=state.parts.mapIndexed{i,p->if(i==part.channel)p.copy(sf2=voice)else p})
            events+=RecallStep.Sf2(part,voice)
            return if(isCurrent()) VoiceResult.APPLIED else VoiceResult.SUPERSEDED
        }
        override suspend fun awaitSf2Idle() {}
        override suspend fun apply(step:RecallStep,isCurrent:()->Boolean):VoiceResult { assertTrue(isCurrent());events+=step;return VoiceResult.APPLIED }
    }
    @Test fun X2_inflightOldVoiceSettlesBeforeNextSnapshotAndDiff()=runBlocking<Unit> {
        val p=Port();val v=RecallPlannerTest().voice; p.state=p.state.copy(parts=p.state.parts.map{it.copy(sf2=v)})
        p.gate=CompletableDeferred();val c=RegistrationCoordinator(this,p)
        val a=c.recall("A",RecallPlannerTest().slot(OutputMode.INTERNAL).copy(parts=p.state.parts.map{it.copy(sf2=v.copy(program=25))}))
        p.start.await();val b=c.recall("B",RecallPlannerTest().slot(OutputMode.INTERNAL));p.gate!!.complete(Unit)
        assertEquals(RecallCompletion.SUPERSEDED,a.await().completion);assertEquals(RecallCompletion.COMPLETED,b.await().completion)
        assertTrue(p.state.parts.all{it.sf2==v});c.cancel()
    }
    @Test fun X3_rapid50LatestWinsAndEveryDroppedRequestCompletes()=runBlocking<Unit> {
        val p=Port();p.delayMs=2;val c=RegistrationCoordinator(this,p)
        val jobs=(0 until 50).map{i->c.recall("R$i",RecallPlannerTest().slot(OutputMode.INTERNAL).copy(parts=List(4){PartSetup(RecallPlannerTest().voice.copy(program=if(i==49)25 else 24),null,true,100)}))}
        withTimeout(3000){jobs.forEach{it.await()}}
        assertEquals(RecallCompletion.COMPLETED,jobs.last().await().completion);assertTrue(p.state.parts.all{it.sf2!!.program==25});assertFalse(c.busy.value);c.cancel()
    }
    @Test fun X4_manualVoiceDuringFlightIsNotRetried()=runBlocking<Unit> {
        val p=Port();p.gate=CompletableDeferred();val c=RegistrationCoordinator(this,p)
        val r=c.recall("A",RecallPlannerTest().slot(OutputMode.INTERNAL));p.start.await();p.manual=true;p.gate!!.complete(Unit)
        assertEquals(VoiceResult.SUPERSEDED,r.await().results.first().result);assertEquals(4,p.sf2Calls);c.cancel()
    }
    @Test fun X6_busyRemainsTrueUntilOperationDrainedAndSaveMustWait()=runBlocking<Unit> {
        val p=Port();p.gate=CompletableDeferred();val c=RegistrationCoordinator(this,p);val r=c.recall("A",RecallPlannerTest().slot(OutputMode.INTERNAL))
        p.start.await();assertTrue(c.busy.value);p.gate!!.complete(Unit);r.await();assertFalse(c.busy.value);c.cancel()
    }
    @Test fun externalDoesNotEnumerateOrSelectSf2EvenDuringRapidRecall()=runBlocking<Unit> {
        val p=Port();val c=RegistrationCoordinator(this,p);val jobs=(1..3).map{c.recall("REG$it",RecallPlannerTest().slot(OutputMode.EXTERNAL))}
        jobs.forEach{it.await()};assertEquals(0,p.sf2Calls);assertEquals(0,p.catalogCalls);assertEquals(4,p.events.size);c.cancel()
    }
    @Test fun sameKeyDebounces150msWithoutLosingCompletion()=runBlocking<Unit> {
        val p=Port();val c=RegistrationCoordinator(this,p,{1000});val a=c.recall("same",RecallPlannerTest().slot(OutputMode.EXTERNAL));val b=c.recall("same",RecallPlannerTest().slot(OutputMode.EXTERNAL));assertSame(a,b);a.await();c.cancel()
    }
}
