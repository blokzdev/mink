package com.mink.guardian

import com.mink.monitor.MIC_SCREEN_OFF_ALERT_MIN_MS
import com.mink.monitor.SensorUseSession
import com.mink.monitor.TrackedSession
import com.mink.monitor.WatchedSensor

/** The timeline category every sensor-use observation and alert is filed under. */
const val SENSOR_USE_CATEGORY = "sensor_use"

/** The observation (always) and alert (screen-off cases only) for one session. */
class SensorUseGuardResult(
    val observation: Observation,
    val alert: GuardianAlert?,
)

/**
 * Map one tracked sensor-use session to a guardian observation and, when the
 * screen was off, an alert. Pure; id/time injected.
 *
 * Alert rules are deterministic and their thresholds are tunable in a future
 * release by design — these are NOT lane 5 immutable rules (see
 * docs/memory-architecture.md):
 * - Camera used while the screen was off: WARNING, any duration.
 * - Microphone used while the screen was off for at least
 *   [MIC_SCREEN_OFF_ALERT_MIN_MS]: WARNING. The duration floor exists because
 *   hotword assistants legitimately blip the microphone with the screen off.
 * - Everything else: observation only.
 *
 * Attribution honesty: the platform anonymises sensor use, so any app named
 * here is a foreground correlation and every string that names one says
 * "likely". The alert body states plainly that Mink cannot know for sure.
 */
fun sensorSessionToGuardian(
    tracked: TrackedSession,
    nowMs: Long,
    idFactory: () -> String,
): SensorUseGuardResult {
    val session = tracked.session

    val observation = Observation(
        id = idFactory(),
        categoryId = SENSOR_USE_CATEGORY,
        summary = observationSummary(session, tracked.alsoSuppressed),
        epochMs = nowMs,
        kind = ObservationKind.CHANGE,
    )

    val alert = when {
        session.sensor == WatchedSensor.CAMERA && session.screenWasOff ->
            screenOffAlert(session, nowMs, idFactory)
        session.sensor == WatchedSensor.MICROPHONE && session.screenWasOff &&
            session.durationMs >= MIC_SCREEN_OFF_ALERT_MIN_MS ->
            screenOffAlert(session, nowMs, idFactory)
        else -> null
    }

    return SensorUseGuardResult(observation, alert)
}

// ---- wording ----

private fun observationSummary(session: SensorUseSession, alsoSuppressed: Int): String {
    val summary = StringBuilder()
    summary.append("The ${session.sensor.label} was in use for ${formatDuration(session.durationMs)}")
    session.sourceHint?.let { summary.append(" during $it") }
    summary.append(".")
    session.likelyApp?.let { summary.append(" Likely $it, going by what was on screen.") }
    if (session.screenWasOff) summary.append(" The screen was off at the time.")
    if (alsoSuppressed > 0) {
        summary.append(" It was used $alsoSuppressed more times since Mink's last note.")
    }
    return summary.toString()
}

private fun screenOffAlert(
    session: SensorUseSession,
    nowMs: Long,
    idFactory: () -> String,
): GuardianAlert {
    val body = StringBuilder()
    body.append("The ${session.sensor.label} was in use for ${formatDuration(session.durationMs)}")
    session.sourceHint?.let { body.append(" during $it") }
    body.append(" while the screen was off.")
    session.likelyApp?.let { body.append(" Likely $it, going by what was on screen.") }
    body.append(
        " Android does not tell apps which app was using it; Mink can only see that it happened.",
    )
    return GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "The ${session.sensor.label} was used while the screen was off",
        body = body.toString(),
        categoryId = SENSOR_USE_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}

/** Format a duration as "12s" under a minute, else whole minutes "3m"; never below "1s". */
private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000L).coerceAtLeast(1L)
    return if (seconds < 60L) "${seconds}s" else "${seconds / 60L}m"
}
