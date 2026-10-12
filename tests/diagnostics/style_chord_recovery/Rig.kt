package audit
import com.yourapp.yamahaarranger.arranger.*
import com.yourapp.yamahaarranger.audio.AudioEngineManager
import com.yourapp.yamahaarranger.lifecycle.ReadyToPlayGate
import com.yourapp.yamahaarranger.chord.ChordDetector
import com.yourapp.yamahaarranger.style.*
import com.yourapp.yamahaarranger.ui.DebugLog
import com.yourapp.midi.MidiInputManager
import kotlinx.coroutines.*
import org.mockito.Mockito.*
import java.io.File
class Rig(val num:Int=4,val den:Int=4,val mainBars:Int=1,val missing:Set<String> = emptySet()) {
 val trace=mutableListOf<String>();val a=mock(AudioEngineManager::class.java);val m=mock(MidiInputManager::class.java);val gate=ReadyToPlayGate();val brain=ArrangerBrain(a,mock(ChordDetector::class.java),m);val scope=CoroutineScope(SupervisorJob()+Clock)
 val barTicks=num*400/den;val barMs=barTicks*5L;val keys=ArrangerSection.values().mapIndexed{i,s->s.styleName to 40+i}.toMap()
 val style:ParsedStyle
 init {Clock.reset();DebugLog.rows.clear();`when`(a.readiness).thenReturn(gate)
  val sections=ArrangerSection.values().filter{it.styleName !in missing}.associate{s->
   val length=barTicks*(if(s.styleName.startsWith("Main"))mainBars else 1);val key=keys[s.styleName]!!
   val p=CasmPolicyModel(5,12,"Guitar",0,0,0,0,11,0,127,1,false,-1L)
   s.styleName to StyleSectionModel(s.styleName,length,listOf(StylePartModel(s.styleName,listOf(StyleNoteEvent(0,true,key,80,5),StyleNoteEvent(length-1,false,key,0,5)),casm=p,program=25))) }
  style=ParsedStyle("synthetic",100,sections,defaultTempoBpm=120,meter=StyleMeter(num,den,100))
  brain.attachScope(scope);brain.loadStyle(style);val f=gate.beginFonts();gate.installedFonts(f,true,"m","d");gate.finishFonts(f,true);gate.finishStyle(gate.beginStyle(),sections.keys,style);gate.outputResult(gate.beginOutput(),true)
  fun capture(name:String,args:Array<out Any?>){trace.add("${Clock.ms}|$name|${args.toList()}")}
  doAnswer{capture("ON",it.arguments);null}.`when`(a).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
  doAnswer{capture("ON",it.arguments);null}.`when`(a).noteOnChannel(anyInt(),anyInt(),anyFloat())
  doAnswer{capture("OFF",it.arguments);null}.`when`(a).noteOffChannel(anyInt(),anyInt())
  doAnswer{capture("ALL_OFF",it.arguments);null}.`when`(a).allNotesOff()
  doAnswer{capture("MIDI_ON",it.arguments);null}.`when`(m).sendNoteOn(anyInt(),anyInt(),anyInt())
  doAnswer{capture("MIDI_OFF",it.arguments);null}.`when`(m).sendNoteOff(anyInt(),anyInt())
 }
 fun start(s:ArrangerSection){brain.selectMainVariation(s);brain.startStop();Clock.until(0)}
 fun onTimes(s:ArrangerSection)=trace.filter{it.contains("|ON|")&&it.substringAfter("|ON|[").split(",")[1].trim().toInt()==keys[s.styleName]}.map{it.substringBefore('|').toLong()}
 fun close(){if(brain.state.value.isPlaying)brain.startStop();scope.cancel()
  fun notes(midi:Boolean)=trace.mapNotNull { row-> val kind=row.split('|')[1];val wanted=if(midi)listOf("MIDI_ON","MIDI_OFF")else listOf("ON","OFF");if(kind !in wanted)null else {val args=row.substringAfter('[').substringBefore(']').split(',');(if(kind.endsWith("ON"))"ON" else "OFF")+":"+args.take(2).map{it.trim()}.joinToString(":")}}
  check(notes(false)==notes(true)){"audio/MIDI ordered channel/pitch parity"}
 }
}
