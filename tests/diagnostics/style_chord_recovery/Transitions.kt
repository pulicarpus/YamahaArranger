package audit
import com.yourapp.yamahaarranger.arranger.*
import com.yourapp.yamahaarranger.style.*
import com.yourapp.yamahaarranger.chord.*
import org.mockito.Mockito.*
import java.io.File
fun seq(r:Rig)=ArrangerBrain::class.java.getDeclaredField("sequencer").apply{isAccessible=true}.get(r.brain) as StyleSequencer
fun tick(r:Rig,key:Int)=mockingDetails(r.a).invocations.filter{it.method.name=="noteOnStyleChannel"&&it.arguments[1]==key}.map{it.arguments[6] as Long}
fun main(args:Array<String>){val out=File(args[0]);out.mkdirs();val rows=mutableListOf<String>();var id=0
 fun run(name:String,num:Int,den:Int,bars:Int,request:Long,action:(Rig)->Unit,target:ArrangerSection,ending:Boolean=false){
  val r=Rig(num,den,bars);val withDrums=r.style.copy(sections=r.style.sections.mapValues{(_,s)->s.copy(parts=s.parts+StylePartModel("Rhythm",listOf(StyleNoteEvent(0,true,36,100,8),StyleNoteEvent(s.lengthTicks/2,true,38,100,8))))})
  r.brain.loadStyle(withDrums);r.gate.finishStyle(r.gate.beginStyle(),withDrums.sections.keys,withDrums);r.brain.setAutoFill(name=="AUTO");r.start(ArrangerSection.MainA)
  seq(r).currentChord=DetectedChord(0,0,ChordQuality.MAJOR)
  Clock.until(request);action(r);Clock.until(r.barMs*4)
  val expectedTick=r.barTicks.toLong();val first=r.onTimes(target).firstOrNull();check(first!=null&&kotlin.math.abs(first-r.barMs)<=1){"$name $num/$den bars$bars req$request target=$target actual=$first expected=${r.barMs}\n${r.trace.joinToString("\n")}"}
  check(tick(r,r.keys[target.styleName]!!).first()==expectedTick){"target clock coordinate"}
  val oldKey=r.keys["MainA"]!!;check(r.onTimes(ArrangerSection.MainA).none{it>=r.barMs&&(name!="INTRO"||it<r.barMs*2)}){"old Main ON leaked past boundary"}
  if(name=="INTRO")check(r.onTimes(ArrangerSection.MainA).any{it==r.barMs*2}){"Intro did not return to Main at exact grid"}
  if(name=="REPLACE")check(r.onTimes(ArrangerSection.MainC).isEmpty()){"superseded Main was dispatched"}
  if(ending)check(!r.brain.state.value.isPlaying)else check(r.brain.state.value.isPlaying)
  check(r.trace.any{it.contains("|ON|[8,")}){"drum disappeared"}
  if(!ending)check(r.trace.none{it.substringBefore('|').toLong()>request&&it.contains("ALL_OFF")}){"global off during transition"}
  rows.add("${++id}\t$name\t$num/$den\t$bars\t$request\t$expectedTick\t$first\tPASS");File(out,"case-$id.txt").writeText(r.trace.joinToString("\n"));r.close()
 }
 if(args.getOrNull(1)!="strict-manual") for((n,d) in listOf(2 to 4,3 to 4,4 to 4,6 to 8))for(b in listOf(1,2))for(pos in listOf(1L,n*400/d*5L/2,n*400/d*5L-100)){
  run("MAIN",n,d,b,pos,{it.brain.selectMainVariation(ArrangerSection.MainB)},ArrangerSection.MainB)
  run("AUTO",n,d,b,pos,{it.brain.selectMainVariation(ArrangerSection.MainB)},ArrangerSection.FillAB)
  run("INTRO",n,d,b,pos,{it.brain.selectSection(ArrangerSection.IntroA)},ArrangerSection.IntroA)
  run("ENDING",n,d,b,pos,{it.brain.selectSection(ArrangerSection.EndingA)},ArrangerSection.EndingA,true)
 }
 // Active Fill requests retain the application's existing deferred policy,
 // not an asserted Yamaha interruption oracle.
 File(out,"matrix-core.tsv").writeText(rows.joinToString("\n"))
 if(args.getOrNull(1)=="strict-manual") for(sequence in listOf(listOf(ArrangerSection.FillBB,ArrangerSection.FillAA,ArrangerSection.MainA),listOf(ArrangerSection.FillDD,ArrangerSection.FillBB,ArrangerSection.MainB))){
  val r=Rig();r.start(ArrangerSection.MainD);Clock.until(500);r.brain.selectSection(sequence[0]);Clock.until(2500);r.brain.selectSection(sequence[1]);Clock.until(2600);r.brain.selectMainVariation(sequence[2]);Clock.until(14000)
  File(out,"manual-fill-observation.txt").writeText("sections="+sequence+" times="+sequence.map{r.onTimes(it)}+"\n"+r.trace.joinToString("\n"))
  check(r.onTimes(sequence[0]).isNotEmpty()&&r.onTimes(sequence[1]).isNotEmpty()&&r.onTimes(sequence[2]).any{it>=2600}){"active Fill application sequence failed"};check(r.brain.state.value.isPlaying)
  rows.add("${++id}\tFILL_DEFERRED_APPLICATION_POLICY\t4/4\t1\t500,2500,2600\tUNKNOWN_YAMAHA\t${r.onTimes(sequence[2])}\tHOST_PASS");File(out,"case-$id.txt").writeText(r.trace.joinToString("\n"));r.close()
 }
 if(args.getOrNull(1)!="strict-manual") for((from,to,next) in listOf(Triple(ArrangerSection.MainD,ArrangerSection.MainB,ArrangerSection.MainA),Triple(ArrangerSection.MainA,ArrangerSection.MainD,ArrangerSection.MainB))) {
  val firstFill=if(to==ArrangerSection.MainB)ArrangerSection.FillBB else ArrangerSection.FillDD
  val secondFill=if(next==ArrangerSection.MainA)ArrangerSection.FillAA else ArrangerSection.FillBB
  val r=Rig(missing=setOf("FillDB","FillBA","FillAD"));r.start(from);Clock.until(500);r.brain.selectMainVariation(to);Clock.until(2500);r.brain.selectMainVariation(next);Clock.until(8000)
  check(r.onTimes(firstFill)==listOf(2000L));check(r.onTimes(secondFill)==listOf(4000L));check(r.onTimes(next).first()==6000L);check(r.brain.state.value.isPlaying)
  rows.add("${++id}\tFILL_DEFERRED_APPLICATION_POLICY\t4/4\t1\t500,2500\t400,800,1200\t2000,4000,6000\tHOST_PASS_YAMAHA_UNKNOWN");File(out,"case-$id.txt").writeText(r.trace.joinToString("\n"));r.close()
 }
 // Latest pre-boundary request replaces pending target; old target not sent.
 if(args.getOrNull(1)!="strict-manual") run("REPLACE",4,4,2,500,{r->r.brain.selectMainVariation(ArrangerSection.MainC);Clock.until(900);r.brain.selectMainVariation(ArrangerSection.MainB)},ArrangerSection.MainB)
 File(out,"matrix.tsv").writeText("id\tscenario\tmeter\tmainBars\trequestMs\ttargetTick\tactualMs\tstatus\n"+rows.joinToString("\n"));println("SECTION_ADMISSION ${rows.size} PASS; active Fill Yamaha semantics UNKNOWN")
}
