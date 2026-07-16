package com.mink.guardian

import com.mink.monitor.DataUsageFinding
import com.mink.monitor.formatBytes

/** Caps to keep one busy interval (many apps sipping cellular) readable. */
const val MAX_DATA_USE_OBSERVATIONS = 8
const val MAX_DATA_USE_ALERTS = 4

/** The timeline category every data-use observation and alert is filed under. */
const val DATA_USE_CATEGORY = "data_use"

/** Observations and alerts produced from one interval's data-use findings. */
class NetworkUsageGuardResult(
    val observations: List<Observation>,
    val alerts: List<GuardianAlert>,
)

/**
 * Map data-use findings to guardian observations/alerts. Pure; id/time injected.
 *
 * Every finding becomes one observation ([ObservationKind.CHANGE], categoryId
 * [DATA_USE_CATEGORY]) and one WARNING alert. Both cases — heavy background
 * cellular and heavy roaming — are worth a glance but never certain trouble, so
 * neither is CRITICAL and none is [GuardianAlert.fromImmutableRule].
 *
 * Design note (see docs/memory-architecture.md, lane 5): this introduces NO
 * lane-5 immutable rule. The thresholds are deterministic and tunable by design,
 * and the copy is volumes only — it says how much an app used and, plainly, that
 * Android does not reveal where the data went. It must never imply a destination,
 * server, or host.
 *
 * Caps mirror [appAccessFindingsToGuardian]: observations at
 * [MAX_DATA_USE_OBSERVATIONS] with a rollup, alerts at [MAX_DATA_USE_ALERTS] with
 * a WARNING rollup. There is no never-drop case here, since no finding is CRITICAL.
 */
fun dataUsageFindingsToGuardian(
    findings: List<DataUsageFinding>,
    nowMs: Long,
    idFactory: () -> String,
): NetworkUsageGuardResult {
    val observations = mutableListOf<Observation>()
    val alerts = mutableListOf<GuardianAlert>()

    for (finding in findings) {
        observations += Observation(
            id = idFactory(),
            categoryId = DATA_USE_CATEGORY,
            summary = finding.observationSummary(),
            epochMs = nowMs,
            kind = ObservationKind.CHANGE,
        )
        alerts += finding.alert(nowMs, idFactory)
    }

    return NetworkUsageGuardResult(
        observations = capObservations(observations, nowMs, idFactory),
        alerts = capAlerts(alerts, nowMs, idFactory),
    )
}

// ---- per-finding wording ----

private fun DataUsageFinding.observationSummary(): String = when (this) {
    is DataUsageFinding.HeavyBackgroundMobile ->
        "${app.label} used ${formatBytes(app.backgroundMobileBytes)} of cellular data in the background."
    is DataUsageFinding.HeavyRoaming ->
        "${app.label} used ${formatBytes(app.roamingBytes)} of data while roaming."
}

private fun DataUsageFinding.alert(nowMs: Long, idFactory: () -> String): GuardianAlert = when (this) {
    is DataUsageFinding.HeavyBackgroundMobile -> GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "${app.label} used background cellular data",
        body = "${app.label} used ${formatBytes(app.backgroundMobileBytes)} of cellular data in " +
            "the background since I last looked. Android does not reveal where it went, only how " +
            "much. If you did not expect that, it is worth a look.",
        categoryId = DATA_USE_CATEGORY,
        createdAtEpochMs = nowMs,
    )
    is DataUsageFinding.HeavyRoaming -> GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "${app.label} used data while roaming",
        body = "${app.label} used ${formatBytes(app.roamingBytes)} while roaming since I last " +
            "looked. Roaming data can be costly. Android shows the amount, not the destination.",
        categoryId = DATA_USE_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}

// ---- caps and rollups ----

private fun capObservations(
    observations: List<Observation>,
    nowMs: Long,
    idFactory: () -> String,
): List<Observation> {
    if (observations.size <= MAX_DATA_USE_OBSERVATIONS) return observations
    val kept = observations.take(MAX_DATA_USE_OBSERVATIONS)
    val hidden = observations.size - kept.size
    return kept + Observation(
        id = idFactory(),
        categoryId = DATA_USE_CATEGORY,
        summary = "...and $hidden more data-use notes.",
        epochMs = nowMs,
        kind = ObservationKind.CHANGE,
    )
}

private fun capAlerts(
    alerts: List<GuardianAlert>,
    nowMs: Long,
    idFactory: () -> String,
): List<GuardianAlert> {
    if (alerts.size <= MAX_DATA_USE_ALERTS) return alerts
    val kept = alerts.take(MAX_DATA_USE_ALERTS)
    val hidden = alerts.size - kept.size
    return kept + GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "More apps used notable data",
        body = "...and $hidden more apps used notable data.",
        categoryId = DATA_USE_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}
