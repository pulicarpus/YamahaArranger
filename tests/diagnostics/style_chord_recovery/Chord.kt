package chordproof
import com.yourapp.yamahaarranger.arranger.*
import com.yourapp.yamahaarranger.audio.*
import com.yourapp.yamahaarranger.chord.*
import com.yourapp.yamahaarranger.style.*
import com.yourapp.midi.MidiInputManager
import android.content.Context
import android.media.midi.MidiInputPort
import audit.Clock
import org.mockito.Mockito.*
import kotlinx.coroutines.*
import java.io.File
fun field(o:Any,n:String)=o.javaClass.getDeclaredField(n).apply{isAccessible=true}
fun main(args:Array<String>){val rows=mutableListOf<String>();val wire=mutableListOf<String>();for(rtr in 0..5){Clock.reset();val a=mock(AudioEngineManager::class.java);val m=MidiInputManager(mock(Context::class.java));val port=mock(MidiInputPort::class.java);field(m,"inputPort").set(m,port);m.midiOutEnabled=true;val scope=CoroutineScope(SupervisorJob()+Clock);val s=StyleSequencer(a,m,scope)
 doAnswer{rows.add("$rtr|${Clock.ms}|ON|${it.arguments[0]}:${it.arguments[1]}");null}.`when`(a).noteOnStyleChannel(anyInt(),anyInt(),anyFloat(),anyInt(),anyInt(),anyInt(),anyLong(),anyLong(),anyBoolean(),anyLong(),anyInt())
 doAnswer{rows.add("$rtr|${Clock.ms}|ON|${it.arguments[0]}:${it.arguments[1]}");null}.`when`(a).noteOnChannel(anyInt(),anyInt(),anyFloat())
 doAnswer{rows.add("$rtr|${Clock.ms}|OFF|${it.arguments[0]}:${it.arguments[1]}");null}.`when`(a).noteOffChannel(anyInt(),anyInt())
 doAnswer{val b=it.arguments[0] as ByteArray;val i=it.arguments[1] as Int;val cmd=b[i].toInt() and 0xf0;if(cmd==0x90||cmd==0x80)wire.add("$rtr|${Clock.ms}|${if(cmd==0x90)"ON" else "OFF"}|${b[i].toInt() and 15}:${b[i+1].toInt() and 127}");null}.`when`(port).send(any(ByteArray::class.java)?:byteArrayOf(),anyInt(),anyInt())
 s.currentChord=DetectedChord(0,0,ChordQuality.MAJOR)
 val parts=(0..5).map{j->StylePartModel(listOf("Bass","Guitar","Chord2","Pad","Phrase1","Phrase2")[j],listOf(StyleNoteEvent(0,true,48+j*2,100,5+j),StyleNoteEvent(799,false,48+j*2,0,5+j)),casm=CasmPolicyModel(5+j,10+j,"Voice",0,1,1,0,11,0,127,rtr,false,-1L),program=25)}
 s.play(StyleSectionModel("MainA",800,parts),100,1);Clock.until(0)
 for((ms,chord) in listOf(250L to DetectedChord(5,5,ChordQuality.MAJOR),500L to DetectedChord(7,7,ChordQuality.MAJOR),750L to DetectedChord(0,0,ChordQuality.MAJOR),1000L to DetectedChord(0,0,ChordQuality.MINOR),1250L to DetectedChord(0,0,ChordQuality.DOM7))){Clock.until(ms);s.currentChord=chord}
 Clock.until(4500);scope.cancel()
 }
 check(rows==wire){"native request/MIDI wire parity"};File(args[0]+".native").writeText(rows.joinToString("\n"));File(args[0]+".wire").writeText(wire.joinToString("\n"));println("RTR baseline/candidate capture: 6 modes × 6 parts; C-F-G-C, Major-Minor-Seventh; parity PASS, Yamaha held-voice semantics UNKNOWN")
}
