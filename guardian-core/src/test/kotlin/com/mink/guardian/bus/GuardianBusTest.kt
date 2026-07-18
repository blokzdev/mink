package com.mink.guardian.bus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bus's mechanics on virtual time: monotonic sequencing and clock stamping,
 * ordered per-hook delivery, and — the load-bearing properties — that a wedged
 * or throwing hook is fully isolated (its own tail dropped and counted, siblings
 * and the emitter untouched) and that a bus with no hooks is an inert no-op.
 *
 * Delivery tests run on an [UnconfinedTestDispatcher] so the fan-out collector
 * and per-hook consumers subscribe eagerly before the first emit (a lazy
 * dispatcher would miss the replay-0 stream).
 *
 * The controller-integration invariants the design also names for this PR
 * (persist-then-emit ordering, and `AlertRaised(immutable)` always followed by
 * `AlertNotified` in the same sweep) live in `GuardianController`, which has no
 * unit harness yet; the policy half of the latter — an immutable alert always
 * passes the gate — is already pinned in `AlertPolicyTest`. The controller
 * characterization harness lands with the sweep extraction (refactor PR 7).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardianBusTest {

    private fun evt(id: String) = GuardianEvent.AlertNotified(id)

    // ---- sequencing + stamping (synchronous; dispatcher-independent) ----

    @Test
    fun eachEmitGetsTheNextMonotonicSeqAndAClockStamp() = runTest {
        var tick = 1_000L
        val bus = GuardianBus(backgroundScope, clock = { tick++ })
        val a = evt("a")
        val b = evt("b")
        val c = evt("c")
        bus.emit(a)
        bus.emit(b)
        bus.emit(c)
        assertEquals(listOf(0L, 1L, 2L), listOf(a.seq, b.seq, c.seq))
        assertEquals(listOf(1_000L, 1_001L, 1_002L), listOf(a.atEpochMs, b.atEpochMs, c.atEpochMs))
    }

    @Test
    fun seqAndStampAreNotPartOfEventIdentity() = runTest {
        val bus = GuardianBus(backgroundScope)
        val a = GuardianEvent.SweepStarted(SweepTrigger.MANUAL)
        val b = GuardianEvent.SweepStarted(SweepTrigger.MANUAL)
        bus.emit(a)
        bus.emit(b)
        assertEquals("same payload compares equal regardless of seq", a, b)
        assertNotEquals(a.seq, b.seq)
    }

    // ---- delivery ----

    @Test
    fun anAttachedHookReceivesEveryEventInOrder() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val received = mutableListOf<GuardianEvent>()
        bus.attach { received += it }
        repeat(5) { bus.emit(evt("a$it")) }
        advanceUntilIdle()
        assertEquals(5, received.size)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), received.map { it.seq })
    }

    @Test
    fun aDetachedHookStopsReceiving() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val received = mutableListOf<GuardianEvent>()
        val handle = bus.attach { received += it }
        bus.emit(evt("before"))
        advanceUntilIdle()
        handle.detach()
        bus.emit(evt("after"))
        advanceUntilIdle()
        assertEquals(1, received.size)
        assertEquals("before", (received.single() as GuardianEvent.AlertNotified).alertId)
    }

    // ---- the zero-hook invariant ----

    @Test
    fun aBusWithNoHooksIsAnInertNoOp() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        repeat(1_000) { bus.emit(evt("x$it")) } // never throws, never blocks
        advanceUntilIdle()
        assertEquals("nothing is dropped when nothing is listening", 0L, bus.droppedCount)
    }

    // ---- isolation: a wedged hook ----

    @Test
    fun aWedgedHookDropsItsOwnTailWithoutStarvingASibling() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val gate = CompletableDeferred<Unit>() // never completed: wedges the hook
        val healthy = mutableListOf<GuardianEvent>()
        val wedged = bus.attach { gate.await() }
        bus.attach { healthy += it }

        repeat(300) { bus.emit(evt("e$it")) }
        advanceUntilIdle()

        // The emitter never blocked (the test reached here) and the healthy hook
        // saw everything, while the wedged hook's bounded channel shed its tail.
        assertEquals(300, healthy.size)
        assertTrue("the wedged hook should have dropped events", wedged.droppedCount > 0)
        assertTrue("bus drop counter reflects it", bus.droppedCount > 0)
    }

    // ---- isolation: a throwing hook ----

    @Test
    fun aThrowingHookIsCountedAndNeverAffectsASibling() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val healthy = mutableListOf<GuardianEvent>()
        val thrower = bus.attach { error("boom") }
        bus.attach { healthy += it }

        repeat(4) { bus.emit(evt("e$it")) }
        advanceUntilIdle()

        assertEquals("every event reached the throwing hook and each threw", 4L, thrower.exceptionCount)
        assertEquals("the sibling was untouched by the thrower", 4, healthy.size)
    }
}
