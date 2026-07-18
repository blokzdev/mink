package com.mink.companion

import com.mink.guardian.AlertLevel
import com.mink.guardian.AlertSource
import com.mink.guardian.GuardianAlert
import com.mink.guardian.Observation
import com.mink.guardian.ObservationKind
import com.mink.guardian.bus.GuardianEvent
import com.mink.guardian.bus.SweepTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The companion's pure routing core: sweep-bracket batching, sensor singletons,
 * upward-only realign re-announcement, permanent per-level suppression, and
 * silent gap resync (tracking the single global seq, falling through so a gap on
 * a SweepStarted still brackets, and keeping the batch across a mid-sweep gap).
 * This is the faithfulness-critical state machine the old inline `guardian.alerts`
 * collector became — tested here off-Android.
 */
class CompanionAlertRouterTest {

    private fun alert(id: String, level: AlertLevel, body: String = "body") = GuardianAlert(
        id = id, level = level, title = "title", body = body, categoryId = "cat", createdAtEpochMs = 0L,
    )

    private fun started(seq: Long) = GuardianEvent.SweepStarted(SweepTrigger.MANUAL).apply { this.seq = seq }
    private fun completed(seq: Long) = GuardianEvent.SweepCompleted(0, 0, 0L).apply { this.seq = seq }
    private fun raised(a: GuardianAlert, seq: Long) =
        GuardianEvent.AlertRaised(a, AlertSource.SIGNAL_CHANGES).apply { this.seq = seq }
    private fun realigned(id: String, from: AlertLevel, to: AlertLevel, seq: Long) =
        GuardianEvent.AlertLevelRealigned(id, from, to).apply { this.seq = seq }
    private fun signal(seq: Long) =
        GuardianEvent.SignalChanged("cat", Observation("o$seq", "cat", "s", 0L, ObservationKind.CHANGE))
            .apply { this.seq = seq }

    // ---- sweep-bracket batching ----

    @Test
    fun sweepAlertsBatchAndFlushSpeakableOnlyAtCompletion() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        val c = alert("c", AlertLevel.CRITICAL)
        val i = alert("i", AlertLevel.INFO) // non-speakable
        val board = listOf(w, c, i)
        assertNull(r.onEvent(started(0), board))
        assertNull("raised alerts accumulate, they do not react yet", r.onEvent(raised(w, 1), board))
        assertNull(r.onEvent(raised(c, 2), board))
        assertNull("a non-speakable alert is seen but never batched", r.onEvent(raised(i, 3), board))
        assertEquals("the whole sweep flushes as one speakable batch", listOf(w, c), r.onEvent(completed(4), board))
    }

    @Test
    fun aSweepThatRaisesNothingSpeakableStaysQuiet() {
        val r = CompanionAlertRouter()
        val board = listOf(alert("i", AlertLevel.INFO), alert("s", AlertLevel.SUGGESTION))
        assertNull(r.onEvent(started(0), board))
        assertNull(r.onEvent(raised(board[0], 1), board))
        assertNull(r.onEvent(raised(board[1], 2), board))
        assertNull(r.onEvent(completed(3), board))
    }

    @Test
    fun anAbortedSweepsBatchIsFlushedLateOnTheNextSweepStartWhichThenBracketsNormally() {
        val r = CompanionAlertRouter()
        val w1 = alert("w1", AlertLevel.WARNING)
        val w2 = alert("w2", AlertLevel.WARNING)
        assertNull(r.onEvent(started(0), listOf(w1)))
        assertNull(r.onEvent(raised(w1, 1), listOf(w1))) // accumulated; this sweep never completes
        // The next sweep starts with no SweepCompleted for the prior one: the
        // stranded batch flushes late rather than lost, and the new bracket opens.
        assertEquals(listOf(w1), r.onEvent(started(2), listOf(w1)))
        assertNull("the reopened bracket batches again", r.onEvent(raised(w2, 3), listOf(w1, w2)))
        assertEquals(listOf(w2), r.onEvent(completed(4), listOf(w1, w2)))
    }

    // ---- sensor singletons (outside a sweep) ----

    @Test
    fun anAlertRaisedOutsideASweepReactsImmediately() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        assertEquals(listOf(w), r.onEvent(raised(w, 0), listOf(w)))
    }

    @Test
    fun aNonSpeakableAlertOutsideASweepStaysQuiet() {
        val r = CompanionAlertRouter()
        val s = alert("s", AlertLevel.SUGGESTION)
        assertNull(r.onEvent(raised(s, 0), listOf(s)))
    }

    @Test
    fun aReRaisedAlertAtTheSameLevelIsSuppressed() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        assertEquals(listOf(w), r.onEvent(raised(w, 0), listOf(w)))
        assertNull("the same id at the same level reacts at most once", r.onEvent(raised(w, 1), listOf(w)))
    }

    @Test
    fun aNewAlertNotInTheSeedReacts() {
        val r = CompanionAlertRouter()
        r.seed(listOf(alert("old", AlertLevel.WARNING)))
        val fresh = alert("new", AlertLevel.CRITICAL)
        assertEquals(listOf(fresh), r.onEvent(raised(fresh, 0), listOf(alert("old", AlertLevel.WARNING), fresh)))
    }

    // ---- realign ----

    @Test
    fun anUpwardRealignOutsideASweepReactsImmediately() {
        val r = CompanionAlertRouter()
        val c = alert("x", AlertLevel.CRITICAL)
        assertEquals(listOf(c), r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 0), listOf(c)))
    }

    @Test
    fun anUpwardRealignInsideASweepBatchesWithTheSweep() {
        val r = CompanionAlertRouter()
        val c = alert("x", AlertLevel.CRITICAL)
        val board = listOf(c)
        assertNull(r.onEvent(started(0), board))
        assertNull("the realign batches, it does not react mid-sweep", r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 1), board))
        assertEquals(listOf(c), r.onEvent(completed(2), board))
    }

    @Test
    fun aDowngradeRealignStaysQuiet() {
        val r = CompanionAlertRouter()
        val w = alert("x", AlertLevel.WARNING)
        assertNull(r.onEvent(realigned("x", AlertLevel.CRITICAL, AlertLevel.WARNING, 0), listOf(w)))
    }

    @Test
    fun anUpwardRealignToANonSpeakableLevelStaysQuiet() {
        val r = CompanionAlertRouter()
        assertNull(r.onEvent(realigned("x", AlertLevel.INFO, AlertLevel.SUGGESTION, 0), listOf(alert("x", AlertLevel.SUGGESTION))))
    }

    @Test
    fun anUpgradeToAnAlreadyAnnouncedLevelDoesNotReAnnounce() {
        val r = CompanionAlertRouter()
        r.seed(listOf(alert("x", AlertLevel.CRITICAL))) // x|CRITICAL already seen
        assertNull(r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 0), listOf(alert("x", AlertLevel.CRITICAL))))
    }

    @Test
    fun aRealignWhoseAlertIsNoLongerOnTheBoardStaysQuiet() {
        val r = CompanionAlertRouter()
        assertNull(r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 0), emptyList()))
    }

    // ---- seq / gap resync ----

    @Test
    fun nonAlertEventsAdvanceTheSeqSoALaterAlertDoesNotFalseGap() {
        val r = CompanionAlertRouter()
        val a0 = alert("a0", AlertLevel.WARNING)
        val a3 = alert("a3", AlertLevel.WARNING)
        val board = listOf(a0, a3)
        assertEquals(listOf(a0), r.onEvent(raised(a0, 0), board))
        assertNull(r.onEvent(signal(1), board))
        assertNull(r.onEvent(signal(2), board))
        // Had the SignalChanged events not advanced the seq, this would look like a
        // gap (3 vs a stale lastSeq of 0), resync-absorb a3, and return null.
        assertEquals("a contiguous stream never resyncs", listOf(a3), r.onEvent(raised(a3, 3), board))
    }

    @Test
    fun outOfOrderDeliveryDoesNotManufactureAFalseGapOnTheNextEvent() {
        val r = CompanionAlertRouter()
        val a = alert("a", AlertLevel.WARNING)
        val late = alert("late", AlertLevel.WARNING)
        val board = listOf(a, late)
        assertEquals(listOf(a), r.onEvent(raised(a, 2), board)) // first event, seq 2
        // A lower seq arrives out of order (a concurrent emitter's send raced ahead):
        // it must not regress lastSeq below 2.
        assertNull(r.onEvent(signal(1), board))
        // So the next in-order event (3) is contiguous with the max, not a false gap.
        assertEquals(listOf(late), r.onEvent(raised(late, 3), board))
    }

    @Test
    fun aGapAbsorbsTheBoardAndDoesNotDoubleSpeakTheTriggeringAlert() {
        val r = CompanionAlertRouter()
        val x = alert("x", AlertLevel.CRITICAL)
        val board = listOf(x)
        assertNull(r.onEvent(signal(0), board)) // anchor seq
        // seq jumps (events 1..4 dropped) on an AlertRaised for x, which is already
        // post-commit on the board: resync absorbs it, then the triggering event is
        // processed and suppressed — no double-speak.
        assertNull(r.onEvent(raised(x, 5), board))
    }

    @Test
    fun aGapLandingOnSweepStartedStillOpensTheBracket() {
        val r = CompanionAlertRouter()
        val old = alert("old", AlertLevel.WARNING)
        val fresh = alert("fresh", AlertLevel.WARNING)
        assertNull(r.onEvent(signal(0), listOf(old))) // anchor seq
        // A gap lands on SweepStarted: it must still open the bracket (fall-through),
        // not leave the sweep unbracketed and route its alerts as singletons.
        assertNull(r.onEvent(started(5), listOf(old)))
        assertNull("the sweep is bracketed: this batches", r.onEvent(raised(fresh, 6), listOf(old, fresh)))
        assertEquals(listOf(fresh), r.onEvent(completed(7), listOf(old, fresh)))
    }

    @Test
    fun aMidSweepGapKeepsBatchingTheRestOfTheSweep() {
        val r = CompanionAlertRouter()
        val w1 = alert("w1", AlertLevel.WARNING)
        val w2 = alert("w2", AlertLevel.WARNING)
        assertNull(r.onEvent(started(0), listOf(w1)))
        assertNull(r.onEvent(raised(w1, 1), listOf(w1))) // batched
        // A gap mid-sweep resyncs (absorbing w1 as seen, clearing pending) but keeps
        // inSweep, so the rest of the sweep still batches rather than de-batching.
        assertNull(r.onEvent(signal(5), listOf(w1)))
        assertNull("the tail still batches", r.onEvent(raised(w2, 6), listOf(w1, w2)))
        assertEquals(listOf(w2), r.onEvent(completed(7), listOf(w1, w2)))
    }

    @Test
    fun theFirstEventIsNeverAGapRegardlessOfItsSeq() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        // Attaching mid-stream: the first delivered event has a large seq but must not resync.
        assertEquals(listOf(w), r.onEvent(raised(w, 999), listOf(w)))
    }

    // ---- richestOf (mood selection, moved off the untested Android side) ----

    @Test
    fun richestOfRanksBySeverityThenBodyThenNull() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING, body = "short")
        val c = alert("c", AlertLevel.CRITICAL, body = "x")
        assertEquals("critical outranks warning", c, r.richestOf(listOf(w, c)))
        val short = alert("w1", AlertLevel.WARNING, body = "short")
        val long = alert("w2", AlertLevel.WARNING, body = "a much longer body")
        assertEquals("at the same level the longer body wins", long, r.richestOf(listOf(short, long)))
        assertNull(r.richestOf(emptyList()))
    }
}
