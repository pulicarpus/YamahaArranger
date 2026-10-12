package audit
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import java.util.PriorityQueue
@OptIn(InternalCoroutinesApi::class)
object Clock:CoroutineDispatcher(),Delay {
 data class Task(val at:Long,val order:Long,val run:Runnable,var cancelled:Boolean=false)
 private val q=PriorityQueue<Task>(compareBy<Task>{it.at}.thenBy{it.order});var now=1_000_000_000L;private var order=0L
 fun nanoTime()=now
 override fun dispatch(context:CoroutineContext,block:Runnable){q.add(Task(now,++order,block))}
 override fun scheduleResumeAfterDelay(timeMillis:Long,continuation:CancellableContinuation<Unit>){val t=Task(now+timeMillis*1000000,++order,Runnable{if(continuation.isActive)continuation.resume(Unit){}});q.add(t);continuation.invokeOnCancellation{t.cancelled=true}}
 override fun invokeOnTimeout(timeMillis:Long,block:Runnable,context:CoroutineContext):DisposableHandle {val t=Task(now+timeMillis*1000000,++order,block);q.add(t);return DisposableHandle{t.cancelled=true}}
 fun until(ms:Long){val target=1_000_000_000L+ms*1000000;var steps=0;while(q.isNotEmpty()&&q.peek().at<=target){check(++steps<100000){"Virtual loop cap"};val t=q.poll();now=t.at;if(!t.cancelled)t.run.run()};now=target}
 fun reset(){q.clear();now=1000000000;order=0}
 val ms:Long get()=(now-1000000000)/1000000
}
