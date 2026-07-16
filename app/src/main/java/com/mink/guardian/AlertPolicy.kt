package com.mink.guardian

/** A muteable family of guardian findings, as the user sees them. */
enum class AlertSource(val label: String) {
    ACCESS_CHANGES("Access changes"),
    SENSOR_USE("Sensor use"),
    SECURITY_CHANGES("Security settings"),
    SIGNAL_CHANGES("Signal changes"),
    EXPOSURE_INSIGHTS("Exposure insights"),
}

/** The [AlertSource] family an alert belongs to, derived from its id and category. */
fun alertSource(alert: GuardianAlert): AlertSource = when {
    alert.id.startsWith("rule.") -> AlertSource.EXPOSURE_INSIGHTS
    alert.categoryId == APP_ACCESS_CATEGORY -> AlertSource.ACCESS_CHANGES
    alert.categoryId == SENSOR_USE_CATEGORY -> AlertSource.SENSOR_USE
    alert.categoryId == HIGH_RISK_CATEGORY -> AlertSource.SECURITY_CHANGES
    else -> AlertSource.SIGNAL_CHANGES
}

/**
 * How long a repeated identical non-critical alert stays suppressed for
 * notifications. A deterministic threshold, tunable by design, NOT a lane-5
 * immutable.
 */
const val NOTIFICATION_COOLDOWN_MS = 30L * 60L * 1000L

/**
 * Decides which alerts become notifications. Pure decision logic with one piece
 * of state: the last time each (categoryId, title) pair was allowed through,
 * for the cooldown. Single-threaded by contract (the controller calls it under
 * sweepMutex).
 *
 * Precedence, first match wins:
 * 1. [GuardianAlert.fromImmutableRule] -> notify, always (no dial, no mute, no
 *    cooldown applies).
 * 2. muted source -> never notify.
 * 3. dial floor: [Alertness.QUIET] notifies CRITICAL only; [Alertness.STANDARD]
 *    notifies WARNING and up; [Alertness.PARANOID] notifies SUGGESTION and up.
 *    (INFO never notifies: it is timeline material on every setting.)
 * 4. cooldown: a non-CRITICAL alert with the same (categoryId, title) allowed
 *    through less than [NOTIFICATION_COOLDOWN_MS] ago is suppressed — this is
 *    the notification frequency policy deferred from the sensor-monitor review.
 *    CRITICAL is exempt.
 *
 * A `true` return records elapsedMs for the cooldown key. elapsedMs is a
 * monotonic reading (SystemClock.elapsedRealtime in production), not the wall
 * clock: the map never outlives a boot, mirroring the project's wall/monotonic
 * split, and a wall-clock jump can neither stretch nor cancel the window.
 */
class NotificationGate {

    /** Last monotonic time each (categoryId, title) pair was allowed through. */
    private val lastAllowedMs = mutableMapOf<String, Long>()

    fun shouldNotify(alert: GuardianAlert, settings: GuardianSettings, elapsedMs: Long): Boolean {
        val allowed = passesPolicy(alert, settings, elapsedMs)
        if (allowed) {
            // Cheap leak guard: the map is bounded by distinct titles in
            // practice, but if it ever grows past this, clearing costs at
            // worst one early re-notification.
            if (lastAllowedMs.size > MAX_COOLDOWN_ENTRIES) lastAllowedMs.clear()
            lastAllowedMs[cooldownKey(alert)] = elapsedMs
        }
        return allowed
    }

    private fun passesPolicy(alert: GuardianAlert, settings: GuardianSettings, elapsedMs: Long): Boolean {
        if (alert.fromImmutableRule) return true
        if (alertSource(alert) in settings.mutedSources) return false
        val floor = when (settings.alertness) {
            Alertness.QUIET -> AlertLevel.CRITICAL
            Alertness.STANDARD -> AlertLevel.WARNING
            Alertness.PARANOID -> AlertLevel.SUGGESTION
        }
        if (alert.level < floor) return false
        if (alert.level != AlertLevel.CRITICAL) {
            val last = lastAllowedMs[cooldownKey(alert)]
            if (last != null && elapsedMs - last < NOTIFICATION_COOLDOWN_MS) return false
        }
        return true
    }

    private fun cooldownKey(alert: GuardianAlert): String = "${alert.categoryId}|${alert.title}"

    private companion object {
        const val MAX_COOLDOWN_ENTRIES = 200
    }
}
