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
 * upward-only realign re-announcement, and silent gap resync. This is the
 * faithfulness-critical state machine the old inline `guardian.alerts` collector
 * became — tested here off-Android.
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
    fun anAbortedSweepsBatchIsFlushedLateOnTheNextSweepStart() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        val board = listOf(w)
        assertNull(r.onEvent(started(0), board))
        assertNull(r.onEvent(raised(w, 1), board)) // accumulated; this sweep never completes
        // The next sweep starts with no SweepCompleted for the prior one: the
        // stranded batch is flushed late rather than lost.
        assertEquals(listOf(w), r.onEvent(started(2), board))
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

    // ---- realign ----

    @Test
    fun anUpwardRealignOutsideASweepReactsImmediately() {
        val r = CompanionAlertRouter()
        val c = alert("x", AlertLevel.CRITICAL) // the board already reflects the new level
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
    fun anUpgradeToAnAlreadyAnnouncedLevelDoesNotReAnnounce() {
        val r = CompanionAlertRouter()
        r.seed(listOf(alert("x", AlertLevel.CRITICAL))) // x|CRITICAL already seen
        assertNull(r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 0), listOf(alert("x", AlertLevel.CRITICAL))))
    }

    @Test
    fun aRealignWhoseAlertIsNoLongerOnTheBoardStaysQuiet() {
        val r = CompanionAlertRouter()
        // The board does not have x at CRITICAL (further re-graded / gone): skip.
        assertNull(r.onEvent(realigned("x", AlertLevel.WARNING, AlertLevel.CRITICAL, 0), emptyList()))
    }

    // ---- seq / gap resync ----

    @Test
    fun nonAlertEventsAdvanceTheSeqWithoutFalseGaps() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        val board = listOf(w)
        // A run of non-alert events (contiguous seq) then a sensor alert: no gap.
        assertNull(r.onEvent(signal(0), board))
        assertNull(r.onEvent(signal(1), board))
        assertNull(r.onEvent(signal(2), board))
        assertEquals("a contiguous stream never resyncs", listOf(w), r.onEvent(raised(w, 3), board))
    }

    @Test
    fun aSeqGapResyncsSilentlyAndAbsorbsTheBoard() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        val x = alert("x", AlertLevel.CRITICAL)
        val board = listOf(w, x) // both are post-commit on the canonical board
        assertNull(r.onEvent(signal(0), board)) // anchor seq
        // seq jumps 0 -> 4 (events 1..3 dropped): resync from the board, stay silent.
        assertNull(r.onEvent(raised(x, 4), board))
        // Both board alerts were absorbed as seen: an upgrade-bounce to a seen level is quiet.
        assertNull(r.onEvent(realigned("w", AlertLevel.SUGGESTION, AlertLevel.WARNING, 5), board))
    }

    @Test
    fun theFirstEventIsNeverAGapRegardlessOfItsSeq() {
        val r = CompanionAlertRouter()
        val w = alert("w", AlertLevel.WARNING)
        // Attaching mid-stream: the first delivered event has a large seq but must not resync.
        assertEquals(listOf(w), r.onEvent(raised(w, 999), listOf(w)))
    }
}
