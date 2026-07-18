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
 * untouched, per-hook drop accounting independent) and that a bus with no hooks
 * is an inert no-op that never replays.
 *
 * [emit] delivers to each hook's channel directly (no fan-out collector to
 * subscribe), so there is no subscription race to hide. The delivery/isolation
 * tests run on an [UnconfinedTestDispatcher] because that models the real
 * behavior under scrutiny — each hook's consumer draining *concurrently* with
 * the emitter — which a serialized dispatcher (emit-all-then-drain) could not.
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
    private fun idOf(e: GuardianEvent) = (e as GuardianEvent.AlertNotified).alertId

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
    fun anAttachedHookReceivesEveryEventInContiguousSeqOrder() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val received = mutableListOf<GuardianEvent>()
        bus.attach { received += it }
        repeat(5) { bus.emit(evt("a$it")) }
        advanceUntilIdle()
        assertEquals(5, received.size)
        // Contiguous seqs are what makes gap-detection possible for a consumer.
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
        assertEquals(listOf("before"), received.map { idOf(it) })
    }

    // ---- the zero-hook / no-replay invariant ----

    @Test
    fun aBusWithNoHooksIsAnInertNoOp() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        repeat(1_000) { bus.emit(evt("x$it")) } // never throws, never blocks
        advanceUntilIdle()
        assertEquals("nothing is dropped when nothing is listening", 0L, bus.droppedCount)
    }

    @Test
    fun aHookAttachedAfterEmitsGetsNoBackfill() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        repeat(5) { bus.emit(evt("early$it")) } // emitted before any hook exists
        val received = mutableListOf<GuardianEvent>()
        bus.attach { received += it }
        bus.emit(evt("late"))
        advanceUntilIdle()
        // replay is 0: a late hook sees only what is emitted after it attaches.
        assertEquals(listOf("late"), received.map { idOf(it) })
    }

    // ---- isolation: a wedged hook ----

    @Test
    fun aWedgedHookDropsExactlyItsOwnTailWithIndependentAccounting() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val gate = CompletableDeferred<Unit>() // never completed: wedges the hook on its first event
        val healthy = mutableListOf<GuardianEvent>()
        val wedged = bus.attach { gate.await() }
        val healthyHandle = bus.attach { healthy += it }

        repeat(300) { bus.emit(evt("e$it")) }
        advanceUntilIdle()

        // The wedged hook consumed event 0 (then stuck), buffered the next 64
        // (its capacity), and shed the remaining 235 — the tail, counted. Its
        // sibling drained concurrently and saw all 300 with zero drops of its own.
        assertEquals(300, healthy.size)
        assertEquals("the wedged hook drops exactly its tail", 235L, wedged.droppedCount)
        assertEquals("drop accounting is per hook", 0L, healthyHandle.droppedCount)
        assertEquals("the bus total matches the one wedged hook", 235L, bus.droppedCount)
    }

    // ---- isolation: a throwing hook ----

    @Test
    fun aThrowingHookIsCountedPerEventAndNeverAffectsASibling() = runTest(UnconfinedTestDispatcher()) {
        val bus = GuardianBus(backgroundScope)
        val healthy = mutableListOf<GuardianEvent>()
        val thrower = bus.attach { error("boom") }
        bus.attach { healthy += it }

        repeat(4) { bus.emit(evt("e$it")) }
        advanceUntilIdle()

        // Each event reached the throwing hook and threw, and its consumer kept
        // going (4 catches, not 1) — the sibling was untouched throughout.
        assertEquals(4L, thrower.exceptionCount)
        assertEquals(0L, thrower.droppedCount)
        assertEquals(4, healthy.size)
    }
}
