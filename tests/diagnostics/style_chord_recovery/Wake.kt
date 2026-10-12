package audit
import com.yourapp.yamahaarranger.arranger.*
import com.yourapp.yamahaarranger.style.*
import org.mockito.Mockito.*
import java.io.File
fun main(args:Array<String>){val r=Rig(mainBars=2);val old=r.style.sections.getValue("MainA");val altered=old.copy(parts=old.parts.map{it.copy(events=it.events+StyleNoteEvent(600,true,100,80,5))});val style=r.style.copy(sections=r.style.sections+ ("MainA" to altered));r.brain.loadStyle(style);r.gate.finishStyle(r.gate.beginStyle(),style.sections.keys,style);r.start(ArrangerSection.MainA);Clock.until(500)
 val s=ArrangerBrain::class.java.getDeclaredField("sequencer").apply{isAccessible=true}.get(r.brain) as StyleSequencer
 s.queueSeamlessTransition(listOf(style.sections.getValue("FillBB") to 1,style.sections.getValue("MainB") to -1),100,4,4)
 Clock.until(5000);val first=r.onTimes(ArrangerSection.FillBB).firstOrNull();val leaked=mockingDetails(r.a).invocations.any{it.method.name=="noteOnStyleChannel"&&it.arguments[1]==100}
 File(args[1]).writeText("phase=${args[0]} firstFillMs=$first admittedMs=500 oldFutureON=$leaked\n"+r.trace.joinToString("\n"));println("WAKE ${args[0]} fillMs=$first oldFutureON=$leaked")
 if(args[0]=="after")check(first==500L&&!leaked)else check(first!=500L&&leaked)
 r.close()
}
