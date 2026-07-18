package com.mink.companion

import com.mink.guardian.AlertLevel
import com.mink.guardian.GuardianAlert
import com.mink.guardian.bus.GuardianEvent

/**
 * The pure decision core that turns the guardian's event stream into the small
 * batches of fresh, speakable alerts the companion reacts to — the piece that
 * used to live inline in `CompanionController` observing `guardian.alerts`, now
 * factored out of Android so it is unit-testable (mirroring [CompanionSpeechPolicy]).
 * The Android controller drives it from its single bus consumer and applies each
 * returned batch (set the mood from the richest, offer it to the speech policy,
 * voice the chosen line). Single-threaded by contract.
 *
 * Why a router at all: the bus emits one [GuardianEvent.AlertRaised] per fresh
 * alert, but the old companion reacted to a *sweep's batch* at once (the richest
 * of a burst becomes one remark). The old batching came from `StateFlow`
 * conflation and was non-deterministic; this reproduces its well-defined
 * whole-sweep interpretation **deterministically** by bracketing a sweep between
 * [GuardianEvent.SweepStarted] and [GuardianEvent.SweepCompleted]:
 *
 * - Alerts raised *inside* a sweep accumulate, then flush as one batch on
 *   `SweepCompleted` — so a sweep that surfaces camera + mic + location at once
 *   still becomes a single considered reaction, richest first.
 * - An alert raised *outside* a sweep (a sensor-in-use session) reacts on its
 *   own, immediately.
 * - An **upward** [GuardianEvent.AlertLevelRealigned] re-announces (a WARNING
 *   re-graded to CRITICAL should speak again); a downgrade does not. This is a
 *   deliberate refinement over the old code, which re-reacted to any newly-seen
 *   level. The event carries no alert, so the alert is looked up on the canonical
 *   board, guarded on the new level (which the board already reflects — the
 *   controller commits the realignment before emitting).
 *
 * Honesty about the advisory bus: [seq] is a single monotonic counter advanced by
 * *every* event, so this tracks it on every delivered event and, on a gap (the
 * consumer's channel dropped events under a burst), **resyncs from the canonical
 * board** — absorbing everything currently on it as already-seen and staying
 * silent, rather than double-speaking. Dropped alerts still live in the timeline;
 * they are simply not voiced. The `Guardian` StateFlows remain canonical.
 */
class CompanionAlertRouter {

    /** "id|level" pairs already reacted to, so a finding is announced at most once per level. */
    private val announced = HashSet<String>()

    /** True between a [GuardianEvent.SweepStarted] and its [GuardianEvent.SweepCompleted]. */
    private var inSweep = false

    /** Speakable alerts accumulated during the current sweep, flushed on completion. */
    private val pending = mutableListOf<GuardianAlert>()

    /** The seq of the last delivered event, or -1 before any — anchors gap detection. */
    private var lastSeq = -1L

    /**
     * Seed the seen-set from the current board so enabling the companion never
     * replays the backlog of alerts already on it. Call once at construction.
     */
    fun seed(board: List<GuardianAlert>) {
        board.forEach { announced += key(it.id, it.level) }
    }

    /**
     * Fold one bus [event] into a reaction. Returns the fresh, speakable alerts the
     * companion should react to now (set the mood from the richest, offer to the
     * speech policy), or null to stay quiet. [board] is `guardian.alerts.value`,
     * the canonical snapshot used to look up a realigned alert and to resync after
     * a gap.
     */
    fun onEvent(event: GuardianEvent, board: List<GuardianAlert>): List<GuardianAlert>? {
        // Gap detection first, on EVERY event: seq is one global counter, so a jump
        // means the consumer dropped events. Resync from the canonical board and
        // stay silent rather than risk double-speaking a redelivered alert.
        if (lastSeq >= 0 && event.seq > lastSeq + 1) {
            board.forEach { announced += key(it.id, it.level) }
            pending.clear()
            inSweep = false
            lastSeq = event.seq
            return null
        }
        lastSeq = event.seq

        return when (event) {
            is GuardianEvent.SweepStarted -> {
                // Normally pending is empty here — the prior sweep flushed on its
                // SweepCompleted. If a sweep aborted before completing, flush its
                // stranded batch now (spoken late) rather than lose it, then open
                // the new bracket. This is what keeps a rare aborted sweep from
                // silently swallowing its findings or a sensor alert.
                val stranded = pending.toList()
                pending.clear()
                inSweep = true
                stranded.ifEmpty { null }
            }
            is GuardianEvent.SweepCompleted -> {
                inSweep = false
                val batch = pending.toList()
                pending.clear()
                batch.ifEmpty { null }
            }
            is GuardianEvent.AlertRaised -> {
                announced += key(event.alert.id, event.alert.level)
                when {
                    !isSpeakable(event.alert.level) -> null   // seen, but never voiced
                    inSweep -> { pending += event.alert; null } // batch it
                    else -> listOf(event.alert)                // a sensor session: react now
                }
            }
            is GuardianEvent.AlertLevelRealigned -> realign(event, board)
            // SignalChanged / AlertNotified / BaselineUpdated / ModelStateChanged /
            // SurfaceComposed only advance the seq (handled above).
            else -> null
        }
    }

    /**
     * An alert was re-graded. Re-announce only an upward re-grade to a level not
     * yet reacted-to and worth speaking; a downgrade or a bounce back to an
     * already-announced level stays quiet. Looks the alert up on [board] guarded
     * on the new level, and batches it if the realignment happened inside a sweep
     * (it does — the controller realigns mid-sweep), matching the old whole-sweep
     * reaction.
     */
    private fun realign(event: GuardianEvent.AlertLevelRealigned, board: List<GuardianAlert>): List<GuardianAlert>? {
        val novel = key(event.alertId, event.to) !in announced
        announced += key(event.alertId, event.to)
        if (!novel || event.to.ordinal <= event.from.ordinal || !isSpeakable(event.to)) return null
        val alert = board.firstOrNull { it.id == event.alertId && it.level == event.to } ?: return null
        return if (inSweep) {
            pending += alert
            null
        } else {
            listOf(alert)
        }
    }

    private fun isSpeakable(level: AlertLevel): Boolean =
        level == AlertLevel.WARNING || level == AlertLevel.CRITICAL

    private fun key(id: String, level: AlertLevel): String = "$id|$level"
}
