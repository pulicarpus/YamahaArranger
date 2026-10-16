package com.yourapp.yamahaarranger.arranger
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.midi.MidiInputManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlinx.coroutines.*
class R24IntegrationTest {
 @Test fun punchAdmissionDoesNotTouchMidiOrPlaybackClock(){
  val bridge=mock(NativeAudioBridge::class.java)
  `when`(bridge.nativeSetDrumABMode(anyBoolean())).thenReturn(true)
  `when`(bridge.nativeSetDrumPunch(anyInt())).thenReturn(true)
  val engine=AudioEngineManager(bridge,mock(SampleProvider::class.java));val midi=mock(MidiInputManager::class.java)
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val seq=StyleSequencer(engine,midi,scope)
  assertFalse(seq.setDrumPunch(2));assertEquals(0,engine.drumPunchMode)
  assertTrue(seq.setDrumABMode(true))
  val field=StyleSequencer::class.java.getDeclaredField("playbackJob").apply{isAccessible=true};val job=Job();field.set(seq,job)
  assertFalse(seq.setDrumPunch(2));job.complete();field.set(seq,null)
  val last=StyleSequencer::class.java.getDeclaredField("r23LastJob").apply{isAccessible=true};val draining=Job();last.set(seq,draining)
  assertFalse(seq.setDrumPunch(1));draining.complete()
  for(mode in listOf(1,2,0)){assertTrue(seq.setDrumPunch(mode));assertEquals(mode,engine.drumPunchMode)}
  assertFalse(seq.setDrumPunch(3));assertTrue(seq.setDrumABMode(false));assertEquals(0,engine.drumPunchMode)
  assertTrue(mockingDetails(midi).invocations.isEmpty())
  assertTrue(mockingDetails(bridge).invocations.none{it.method.name.contains("Note") || it.method.name.contains("Preset")})
  scope.cancel()
 }
}
