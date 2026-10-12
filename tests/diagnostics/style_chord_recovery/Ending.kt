package audit
import com.yourapp.yamahaarranger.arranger.*
import org.mockito.Mockito.*
import java.io.File
fun main(args:Array<String>){val r=Rig(mainBars=2);r.start(ArrangerSection.MainA);Clock.until(500);r.brain.selectSection(ArrangerSection.EndingA);Clock.until(7000);val t=r.onTimes(ArrangerSection.EndingA).first();val k=r.keys["EndingA"];val tick=mockingDetails(r.a).invocations.first{it.method.name=="noteOnStyleChannel"&&it.arguments[1]==k}.arguments[6];check(!r.brain.state.value.isPlaying);File(args[1]).writeText("phase=${args[0]} requestMs=500 endingMs=$t targetMs=2000 endingTick=$tick stopped=true\n"+r.trace.joinToString("\n"));println("ENDING ${args[0]} firstMs=$t tick=$tick stopped=true");if(args[0]=="after")check(t==2000L&&tick==400L)else check(t==4000L&&tick==800L);r.close()}
