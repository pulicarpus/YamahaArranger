package com.yourapp.rtdiag

import com.yourapp.yamahaarranger.arranger.OrderedNoteIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** P1 ordered-dispatch contract, pure (no audio chain): per-key ticket order, deadlock freedom, STOP epoch/gate, retirement on failure. */
class OrderedNoteIoUnitTest {
    @Test fun perKeyExecutionOrderEqualsLedgerOrderUnderConcurrency() {
        val io = OrderedNoteIo(); val monitor = Any(); val ledgerSeq = AtomicInteger()
        val executed = Array(8) { java.util.Collections.synchronizedList(ArrayList<Int>()) }
        val threads = 6; val perThread = 4000; val pool = Executors.newFixedThreadPool(threads); val start = CountDownLatch(1)
        val futures = (0 until threads).map { t -> pool.submit {
            val rnd = java.util.Random(t.toLong()); start.await()
            val b = io.batch()
            repeat(perThread) {
                b.depth++
                synchronized(monitor) {                       // stands in for noteLifecycleLock: the ledger order
                    repeat(1 + rnd.nextInt(3)) {                // a retarget stages several commands, possibly on several keys
                        val k = rnd.nextInt(8); val seq = ledgerSeq.getAndIncrement()
                        io.stage(b, k / 4, k % 4) { if (seq % 97 == 0) Thread.sleep(1); executed[k].add(seq) }
                    }
                }
                b.depth--; io.flush(b)
            }
        } }
        start.countDown()
        futures.forEach { it.get(60, TimeUnit.SECONDS) }   // a deadlock would time out here
        pool.shutdownNow()
        var total = 0
        for (k in 0 until 8) { val l = executed[k]; total += l.size; for (i in 1 until l.size) assertTrue("key $k out of ledger order at $i: ${l[i - 1]} then ${l[i]}", l[i - 1] < l[i]) }
        assertEquals(ledgerSeq.get(), total)
    }

    @Test fun differentKeysDoNotWaitForABlockedCommand() {
        val io = OrderedNoteIo(); val monitor = Any()
        val blocked = CountDownLatch(1); val release = CountDownLatch(1); val other = CountDownLatch(1)
        val a = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 1, 60) { blocked.countDown(); release.await(5, TimeUnit.SECONDS) } }; io.flush(b) }
        a.start(); assertTrue(blocked.await(2, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val b2 = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 2, 61) { other.countDown() } }; io.flush(b) }
        b2.start(); assertTrue("unrelated key must run while another command is blocked", other.await(1, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
        release.countDown(); a.join(2000)
    }

    @Test fun sameKeyWaitsForTheEarlierTicket() {
        val io = OrderedNoteIo(); val monitor = Any(); val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val inFirst = CountDownLatch(1); val letFirstEnd = CountDownLatch(1)
        val b1 = io.batch()
        val first = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 3, 40) { inFirst.countDown(); letFirstEnd.await(3, TimeUnit.SECONDS); order.add("ON") } }; io.flush(b) }
        first.start(); assertTrue(inFirst.await(2, TimeUnit.SECONDS))
        val second = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 3, 40) { order.add("OFF") } }; io.flush(b) }
        second.start(); Thread.sleep(80); assertTrue("the later OFF must not overtake the blocked ON", order.isEmpty())
        letFirstEnd.countDown(); first.join(2000); second.join(2000)
        assertEquals(listOf("ON", "OFF"), order); assertTrue(b1.cmds.isEmpty())
    }

    @Test fun stopSessionSkipsStaleStagedCommandsAndWaitsForInFlightOnes() {
        val io = OrderedNoteIo(); val monitor = Any(); val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val inFlight = CountDownLatch(1); val release = CountDownLatch(1)
        val a = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 5, 70) { inFlight.countDown(); release.await(3, TimeUnit.SECONDS); log.add("A-ran") } }; io.flush(b) }
        a.start(); assertTrue(inFlight.await(2, TimeUnit.SECONDS))
        // B is staged (old session) behind A on the same key and has not started
        val bThread = Thread { val b = io.batch(); synchronized(monitor) { io.stage(b, 5, 70) { log.add("B-stale-ran") } }; io.flush(b) }
        bThread.start(); Thread.sleep(50)
        val stopper = Thread { io.stopSession(monitor, { log.add("ledger-cleared") }, { log.add("kill-all") }) }
        stopper.start(); Thread.sleep(80)
        assertTrue("STOP must wait for the in-flight command", !log.contains("kill-all"))
        release.countDown(); a.join(2000); bThread.join(2000); stopper.join(2000)
        assertEquals(listOf("A-ran", "ledger-cleared", "kill-all"), log.toList())
        // a command of the NEW session runs normally on the same key
        val after = java.util.concurrent.atomic.AtomicBoolean()
        val b = io.batch(); synchronized(monitor) { io.stage(b, 5, 70) { after.set(true) } }; io.flush(b)
        assertTrue(after.get())
    }

    @Test fun aFailingCommandRetiresItsTicket() {
        val io = OrderedNoteIo(); val monitor = Any(); val ran = AtomicInteger()
        val b = io.batch()
        synchronized(monitor) { io.stage(b, 0, 10) { throw IllegalStateException("boom") }; io.stage(b, 0, 10) { ran.incrementAndGet() } }
        try { io.flush(b); throw AssertionError("expected the failure to surface") } catch (e: IllegalStateException) { assertEquals("boom", e.message) }
        assertEquals("the later command of the same key still ran", 1, ran.get())
        val c = io.batch(); synchronized(monitor) { io.stage(c, 0, 10) { ran.incrementAndGet() } }; io.flush(c)
        assertEquals(2, ran.get())
    }
}
