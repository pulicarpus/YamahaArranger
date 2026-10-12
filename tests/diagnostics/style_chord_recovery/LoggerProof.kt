package proof
import com.yourapp.yamahaarranger.ui.DebugLog
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.concurrent.thread
import java.io.File
fun messages(xs:List<String>)=xs.map{it.substringAfter("] ")}
fun main(args:Array<String>){
 val phase=args[0];val out=File(args[1]);val rows=mutableListOf<String>();fun test(n:String,f:()->Unit){f();rows.add("$n PASS")}
 test("format-prefix-duplicate-newline") {DebugLog.clear();DebugLog.add("duplicate\nline");DebugLog.add("duplicate\nline");DebugLog.traceAudio("A");DebugLog.traceMidi("M");DebugLog.traceError("E");check(messages(DebugLog.getFullAll())==listOf("duplicate\nline","duplicate\nline","🔊 AUDIO A","📤 MIDI OUT M","❌ ERROR E"));check(DebugLog.getFullAll().all{Regex("^\\[\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\] ").containsMatchIn(it)})}
 test("two-capacities") {DebugLog.clear();repeat(30100){DebugLog.add("n$it")};check(messages(DebugLog.getAll())==(30070 until 30100).map{"n$it"});check(messages(DebugLog.getFullAll())==(100 until 30100).map{"n$it"})}
 test("snapshot-detached-clear-longtext") {val snap=DebugLog.getFullAll();DebugLog.setLongText("long");DebugLog.clear();check(DebugLog.getAll().isEmpty()&&DebugLog.getFullAll().isEmpty()&&DebugLog.getLongText()=="");check(snap.size==30000);DebugLog.add("after");check(messages(DebugLog.getFullAll())==listOf("after"));check(DebugLog.getFullText()==DebugLog.getFullAll().joinToString("\n"))}
 test("concurrent-no-loss") {DebugLog.clear();val start=CountDownLatch(1);val error=AtomicReference<Throwable?>();val ts=(0..3).map{w->thread{try{start.await();repeat(1000){DebugLog.add("$w:$it")}}catch(t:Throwable){error.set(t)}}};start.countDown();ts.forEach{it.join(20000);check(!it.isAlive)};check(error.get()==null);val full=messages(DebugLog.getFullAll());check(full.size==4000&&full.toSet().size==4000);if(phase=="after")check(messages(DebugLog.getAll())==full.takeLast(30))}
 if(phase=="after") {
  test("capture-stats-enable-disable") {val cls=DebugLog.javaClass;cls.getDeclaredMethod("beginTiming").invoke(DebugLog);repeat(3){DebugLog.add("timed")};val report={cls.getDeclaredMethod("timingReport").invoke(DebugLog) as String};check(report().contains("calls=3 "));cls.getDeclaredMethod("endTiming").invoke(DebugLog);DebugLog.add("not-timed");check(report().contains("calls=3 ")&&report().contains("active=false"))}

  val lock=DebugLog.javaClass.getDeclaredField("logLock").apply{isAccessible=true}.get(DebugLog)
  test("clear-before-writer-deterministic") {DebugLog.clear();DebugLog.add("old");val started=CountDownLatch(1);lateinit var writer:Thread;synchronized(lock){writer=thread{started.countDown();DebugLog.add("new")};check(started.await(2,TimeUnit.SECONDS));DebugLog.clear()};writer.join(2000);check(!writer.isAlive);check(messages(DebugLog.getFullAll())==listOf("new"))}
  test("snapshot-before-writer-deterministic") {DebugLog.clear();DebugLog.add("old");val start=CountDownLatch(1);lateinit var writer:Thread;lateinit var snap:List<String>;synchronized(lock){writer=thread{start.countDown();DebugLog.add("new")};check(start.await(2,TimeUnit.SECONDS));snap=DebugLog.getFullAll()};writer.join(2000);check(messages(snap)==listOf("old"));check(messages(DebugLog.getFullAll())==listOf("old","new"))}
  test("snapshot-clear-no-deadlock") {DebugLog.clear();val done=AtomicBoolean();val error=AtomicReference<Throwable?>();val writers=(0..3).map{w->thread{try{repeat(2000){DebugLog.add("s$w:$it")}}catch(t:Throwable){error.set(t)}}};val reader=thread{try{while(!done.get()){check(DebugLog.getAll().size<=30);check(DebugLog.getFullAll().size<=30000)}}catch(t:Throwable){error.set(t)}};repeat(20){DebugLog.clear()};writers.forEach{it.join(20000);check(!it.isAlive)};done.set(true);reader.join(2000);check(!reader.isAlive);check(error.get()==null)}
 }
 val bench=mutableListOf<String>();for(fill in listOf(0,10000,30000)){DebugLog.clear();repeat(fill){DebugLog.add("seed$it")};repeat(200){DebugLog.add("warm")};val samples=LongArray(1500){val t=System.nanoTime();DebugLog.add("measure");System.nanoTime()-t};samples.sort();bench.add("$fill\t${samples[750]/1000.0}\t${samples[1485]/1000.0}")}
 out.writeText(rows.joinToString("\n")+"\nfill\tp50us\tp99us\n"+bench.joinToString("\n"));println("LOGGER $phase ${rows.size} checks PASS\n"+bench.joinToString("\n"))
}
