package com.mink.companion

import com.mink.guardian.AlertLevel
import com.mink.guardian.GuardianAlert

/**
 * The calm engine that decides WHICH fresh alert, if any, the companion speaks
 * aloud, and when. Mood and animation are separate and happen for every finding;
 * this gates only speech, so the sprite stays lively while the voice stays rare.
 *
 * Three rules keep the voice calm: a throttle (at most one remark per window), a
 * dedup (ignore a repeat of the same finding within a short window), and a
 * burst-merge (a sweep that surfaces camera, mic, and location at once becomes a
 * single remark about the richest of them). One rule keeps it honest: a lane-5
 * immutable critical always speaks — a real surveillance combo is never silenced.
 *
 * Pure over its own small state; no Android, so it is unit-testable with no
 * device. Single-threaded by contract, called from the companion's collector.
 */
class CompanionSpeechPolicy(
    private val throttleMs: Long = SPEAK_THROTTLE_MS,
    private val dedupMs: Long = SPEAK_DEDUP_MS,
) {

    /** When the companion last spoke, or null until it has spoken once. */
    private var lastSpokeMs: Long? = null

    /** Last time each (categoryId|title) key was spoken, for the dedup window. */
    private val lastKeyMs = mutableMapOf<String, Long>()

    /**
     * Choose the one alert to speak from a sweep's [freshAlerts], or null to stay
     * quiet. Filters to warnings and criticals, burst-merges to the richest, then
     * applies the throttle and dedup — except a [GuardianAlert.fromImmutableRule]
     * critical, which bypasses both. A spoken alert records the timestamps.
     */
    fun chooseToSpeak(freshAlerts: List<GuardianAlert>, nowMs: Long): GuardianAlert? {
        // Evict dedup entries older than the window so the map cannot grow without
        // bound over a long session. Only evict when the clock has moved forward,
        // so a backwards clock jump never drops a still-valid entry.
        lastKeyMs.entries.removeAll { nowMs >= it.value && nowMs - it.value >= dedupMs }

        val candidates = freshAlerts.filter {
            it.level == AlertLevel.WARNING || it.level == AlertLevel.CRITICAL
        }
        // Burst-merge: pick the single richest finding so a combo becomes one line.
        val chosen = candidates.maxWithOrNull(RICHEST) ?: return null

        val bypass = chosen.level == AlertLevel.CRITICAL && chosen.fromImmutableRule
        if (!bypass) {
            val spoke = lastSpokeMs
            if (spoke != null && nowMs - spoke < throttleMs) return null
            val key = dedupKey(chosen)
            val keyMs = lastKeyMs[key]
            if (keyMs != null && nowMs - keyMs < dedupMs) return null
        }

        lastSpokeMs = nowMs
        lastKeyMs[dedupKey(chosen)] = nowMs
        return chosen
    }

    private fun dedupKey(alert: GuardianAlert): String = "${alert.categoryId}|${alert.title}"

    private companion object {
        /** At most one remark per this window. Tunable, not a lane-5 immutable. */
        const val SPEAK_THROTTLE_MS = 10_000L

        /** Ignore a repeat of the same finding within this window. Tunable. */
        const val SPEAK_DEDUP_MS = 5_000L

        /**
         * Richest first: highest severity (critical over warning), tie-broken by
         * the longest body, then the newest finding.
         */
        val RICHEST: Comparator<GuardianAlert> =
            compareBy<GuardianAlert> { if (it.level == AlertLevel.CRITICAL) 1 else 0 }
                .thenBy { it.body.length }
                .thenBy { it.createdAtEpochMs }
    }
}
