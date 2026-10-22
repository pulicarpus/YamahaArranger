package com.yourapp.yamahaarranger.registration
import com.yourapp.yamahaarranger.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * Deterministic reproduction of the flake seen in CI run 39771378889 (LiveControlWiringTest.liveFillUsesExplicitLastMainEvenBeforeStateCollectorRuns:
 * expected "Fill C" but was "Fill A"). The view model's init collects ArrangerBrain.state and writes the section letter; the state's INITIAL replay
 * is MainA. In production viewModelScope is Dispatchers.Main.immediate, so that replay is delivered inside the constructor, before any user input.
 * The shared test Rig builds the view model on Dispatchers.Default instead, so when that pool thread is slow to start the stale MainA replay can arrive AFTER
 * the explicit "C" and overwrite it. This class controls the dispatcher so the order is fixed, independent of timing. Nothing here touches StyleSequencer.
 */
class LiveMainLetterRaceTest {
    private class QueueDispatcher : CoroutineDispatcher() {
        private val q = java.util.ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(q) { q.add(block) } }
        fun drain() { while (true) { val r = synchronized(q) { q.poll() } ?: break; r.run() } }
    }
    private fun harness(r: RecallAdapterTest.Rig, scope: CoroutineScope) = RecallViewModelHarness(r.engine, r.brain, r.midi, scope)

    @Test fun aLateStartingCollectorLetsTheStaleInitialReplayOverwriteTheExplicitMain() {
        val r = RecallAdapterTest.Rig(); val d = QueueDispatcher(); val scope = CoroutineScope(SupervisorJob() + d)
        try {
            val vm = harness(r, scope)
            vm.onLiveMainSelected("C")                // explicit user choice; the collector has not run yet
            d.drain()                                  // it now delivers the initial state (MainA)
            vm.onLiveFill()
            assertEquals("the mechanism behind the CI flake: initial replay clobbers the explicit letter", "Fill A", vm.observedSectionLabel)
        } finally { scope.cancel(); r.close() }
    }

    @Test fun anImmediateCollectorLikeProductionMainImmediateCannotBeOverwritten() {
        val r = RecallAdapterTest.Rig(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = harness(r, scope)                // the initial replay is delivered during construction
            vm.onLiveMainSelected("C"); vm.onLiveFill()
            assertEquals("Fill C", vm.observedSectionLabel)
        } finally { scope.cancel(); r.close() }
    }
}
