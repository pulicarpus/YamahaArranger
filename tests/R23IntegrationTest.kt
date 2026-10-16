package com.yourapp.yamahaarranger.arranger
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.style.*
import com.yourapp.midi.MidiInputManager
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*
import kotlinx.coroutines.*
import java.io.File

class R23IntegrationTest {
 @Test fun stopSwitchStartBindingsAndExternalMidiStaySeparate()=runBlocking {
  val bridge=mock(NativeAudioBridge::class.java)
  `when`(bridge.nativeStart()).thenReturn(true)
  `when`(bridge.nativeLoadMelodySoundFont(anyString())).thenReturn(true)
  `when`(bridge.nativeLoadDrumSoundFont(anyString())).thenReturn(true)
  `when`(bridge.nativeSetDrumABMode(anyBoolean())).thenReturn(true)
  `when`(bridge.nativeBindDrumAB(anyInt(),anyString(),anyInt(),anyInt(),anyString())).thenReturn(true)
  val engine=AudioEngineManager(bridge,mock(SampleProvider::class.java));engine.loadSoundFontPair("m","d")
  val midi=mock(MidiInputManager::class.java);val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  val seq=StyleSequencer(engine,midi,scope)
  fun section(pc:Int):StyleSectionModel {
   val policy=CasmPolicyModel(9,9,"Drums",0,0,0,0,127,0,127,0,false)
   return StyleSectionModel("PC$pc",48,listOf(StylePartModel("Rhythm2",listOf(
    StyleNoteEvent(0,false,0,127,9,0xB9),StyleNoteEvent(0,false,32,0,9,0xB9),
    StyleNoteEvent(0,false,pc,0,9,0xC9),StyleNoteEvent(12,true,53,127,9)),casm=policy,program=pc,bankMsb=127)))
  }
  val sections=listOf(0,8,16).map { section(it) }
  DrumKitShadowRuntime.prepare(ParsedStyle("fixture",480,sections.associateBy { it.name }),byteArrayOf(1),File("app/src/main/assets/r22/kits.tsv.bin").inputStream())
  val apply=StyleSequencer::class.java.getDeclaredMethod("applyVoicesFromCasm",StyleSectionModel::class.java).apply { isAccessible=true }
  // STOP mode admission uses actual playback job state, including cancelled-but-not-drained jobs.
  val field=StyleSequencer::class.java.getDeclaredField("playbackJob").apply { isAccessible=true }
  val active=Job();field.set(seq,active);assertFalse(seq.setDrumABMode(true));assertFalse(engine.drumABResolved)
  field.set(seq,null);active.complete()
  val draining=Job();val last=StyleSequencer::class.java.getDeclaredField("r23LastJob").apply { isAccessible=true };last.set(seq,draining)
  assertFalse(seq.setDrumABMode(true));draining.complete()
  assertTrue(seq.setDrumABMode(true));assertTrue(engine.drumABResolved)
  for(s in sections)apply.invoke(seq,s)
  val binds=mockingDetails(bridge).invocations.filter { it.method.name=="nativeBindDrumAB" }
  assertEquals(3,binds.size)
  assertEquals(listOf(0,8,16),binds.map { it.arguments[3] })
  assertTrue(binds.all { it.arguments[1]=="ca157455dc307fd988d1edef1e2a0cfca245ab7d2bb5970f4c415c29444ba506" && it.arguments[2]==127 })
  assertTrue(mockingDetails(bridge).invocations.none { it.method.name in listOf("nativeSetChannelPreset","nativeSetChannelPresetWithName") })
  val external=mockingDetails(midi).invocations.filter { it.method.name=="sendProgramChange" }
  assertEquals(3,external.size);assertTrue(external.all { it.arguments[0]==9 && it.arguments[2]==127 && it.arguments[3]==0 })
  val before=mockingDetails(midi).invocations.size
  assertTrue(seq.setDrumABMode(false));assertFalse(engine.drumABResolved)
  assertEquals(before,mockingDetails(midi).invocations.size) // mode toggle sends no E343 commands
  apply.invoke(seq,sections[1])
  assertTrue(mockingDetails(bridge).invocations.any { it.method.name=="nativeSetChannelPresetWithName" && it.arguments[2]==8 })
  scope.cancel();DrumKitShadowRuntime.setEnabled(false)
 }
}
