package com.mink.guardian.bus

import com.mink.guardian.AlertLevel
import com.mink.guardian.AlertSource
import com.mink.guardian.BaselineSummary
import com.mink.guardian.GuardianAlert
import com.mink.guardian.ModelStatus
import com.mink.guardian.Observation
import com.mink.guardian.compose.Author
import com.mink.guardian.route.Mode
import com.mink.guardian.route.Surface

/** What kicked off a sweep, for [GuardianEvent.SweepStarted]. */
enum class SweepTrigger {
    /** A user action (the dashboard button) or an unattributed caller. */
    MANUAL,

    /** The guardian being enabled. */
    ENABLE,

    /** The periodic service loop or the WorkManager worker. */
    SCHEDULED,
}

/**
 * The typed, post-commit vocabulary of the guardian event bus. Every event is a
 * **receipt of something that already happened** — it is published only after
 * the corresponding durable write or StateFlow update, never as a command. The
 * StateFlows remain canonical; the bus is advisory (see [GuardianBus]).
 *
 * Each event carries a bus-assigned monotonic [seq] and wall-clock [atEpochMs],
 * set at emit time. They are delivery metadata, deliberately outside each data
 * class's primary constructor so two events with the same payload compare equal
 * regardless of when they were emitted. [seq] is for **gap detection**, not
 * strict delivery ordering: sweep-path events are emitted in order under the
 * sweep mutex, but near a buffer boundary delivery may interleave, so a consumer
 * that sees a gap should resync from the StateFlows rather than trust the stream.
 */
sealed class GuardianEvent {

    /** Bus-assigned monotonic sequence; [UNSEQUENCED] until emitted. */
    var seq: Long = UNSEQUENCED
        internal set

    /** Bus-assigned wall-clock stamp at emit; 0 until emitted. */
    var atEpochMs: Long = 0L
        internal set

    /** A sweep began. */
    data class SweepStarted(val trigger: SweepTrigger) : GuardianEvent()

    /**
     * A sweep finished, with the counts it produced and how long it took. Always
     * pairs a preceding [SweepStarted]; if the sweep threw partway, the counts are
     * best-effort (only what it committed before the throw) while the duration is
     * always the real elapsed span.
     */
    data class SweepCompleted(
        val newObservations: Int,
        val newAlerts: Int,
        val durationMs: Long,
    ) : GuardianEvent()

    /** One observation was recorded in the timeline. */
    data class SignalChanged(val categoryId: String, val observation: Observation) : GuardianEvent()

    /** A fresh alert was raised (post-commit; only genuinely new alerts, never a re-persist). */
    data class AlertRaised(val alert: GuardianAlert, val source: AlertSource) : GuardianEvent()

    /** An alert was allowed through the merged notification gate. */
    data class AlertNotified(val alertId: String) : GuardianEvent()

    /**
     * A stored alert's level was re-graded to match its rule (e.g. a release
     * demotes a finding). Distinct from [AlertRaised] because the alert is not
     * new — this is how the companion re-announces an upgrade.
     */
    data class AlertLevelRealigned(
        val alertId: String,
        val from: AlertLevel,
        val to: AlertLevel,
    ) : GuardianEvent()

    /** The user acknowledged an alert. */
    data class AlertAcknowledged(val alertId: String) : GuardianEvent()

    /** The learned baseline was updated and its digest republished. */
    data class BaselineUpdated(val summary: BaselineSummary) : GuardianEvent()

    /** The on-device model's status changed. */
    data class ModelStateChanged(val status: ModelStatus) : GuardianEvent()

    /** A text surface was composed, with the mode it ran at and who authored the text. */
    data class SurfaceComposed(
        val surface: Surface,
        val mode: Mode,
        val author: Author,
    ) : GuardianEvent()

    companion object {
        /** [seq] value before the bus assigns one. */
        const val UNSEQUENCED: Long = -1L
    }
}
