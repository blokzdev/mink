package com.mink.guardian

import com.mink.monitor.AppAccessFinding
import com.mink.monitor.AppGrant
import com.mink.monitor.PermCapability
import com.mink.monitor.SURVEILLANCE_COMBO
import com.mink.monitor.WATCH_SENSITIVE

/** Caps to keep one noisy sweep (e.g. an OS update re-granting many system apps) readable. */
const val MAX_APP_ACCESS_OBSERVATIONS = 12
const val MAX_APP_ACCESS_ALERTS = 6

/** The timeline category every app-access observation and alert is filed under. */
private const val APP_ACCESS_CATEGORY = "app_access"

/** Observations and alerts produced from one sweep's app-access diff. */
class AppAccessGuardResult(
    val observations: List<Observation>,
    val alerts: List<GuardianAlert>,
)

/**
 * Map diff findings to guardian observations/alerts. Pure; id/time injected.
 * Severity policy:
 * - NewApp whose granted contains ALL of [SURVEILLANCE_COMBO]: CRITICAL alert
 *   "can see, hear, and locate you" — the immutable rule (see
 *   docs/memory-architecture.md, lane 5, and [SURVEILLANCE_COMBO]); emitted even
 *   for system apps (a new system app with all three is exactly as interesting)
 *   and never dropped by the alert cap.
 * - NewApp (user app) with any [WATCH_SENSITIVE] capability: WARNING alert
 *   listing the sensitive capabilities it arrived with.
 * - CapabilityGained (user app, WATCH_SENSITIVE): WARNING alert
 *   "<label> gained <capability>".
 * - CapabilityGained (system app or non-sensitive): observation only.
 * - CapabilityRevoked: observation only, phrased as good news
 *   ("<label> no longer has <capability>").
 * - AppRemoved: observation only.
 * - Every finding gets an observation ([ObservationKind.CHANGE], categoryId
 *   "app_access") subject to [MAX_APP_ACCESS_OBSERVATIONS] with a final rollup
 *   observation "...and N more app access changes." when truncated. Alerts capped
 *   at [MAX_APP_ACCESS_ALERTS] by severity (CRITICAL first), with a rollup WARNING
 *   "...and N more apps changed what they can reach." when truncated. A
 *   SURVEILLANCE_COMBO CRITICAL alert is never dropped by the cap.
 */
fun appAccessFindingsToGuardian(
    findings: List<AppAccessFinding>,
    nowMs: Long,
    idFactory: () -> String,
): AppAccessGuardResult {
    val observations = mutableListOf<Observation>()
    val alerts = mutableListOf<GuardianAlert>()

    for (finding in findings) {
        observations += Observation(
            id = idFactory(),
            categoryId = APP_ACCESS_CATEGORY,
            summary = finding.observationSummary(),
            epochMs = nowMs,
            kind = ObservationKind.CHANGE,
        )
        finding.alertOrNull(nowMs, idFactory)?.let { alerts += it }
    }

    return AppAccessGuardResult(
        observations = capObservations(observations, nowMs, idFactory),
        alerts = capAlerts(alerts, nowMs, idFactory),
    )
}

// ---- per-finding wording ----

private fun AppAccessFinding.observationSummary(): String = when (this) {
    is AppAccessFinding.NewApp ->
        "${app.label} was installed and can reach ${formatCapabilities(app.granted)}."
    is AppAccessFinding.CapabilityGained ->
        "${app.label} gained ${capability.label}."
    is AppAccessFinding.CapabilityRevoked ->
        "${app.label} no longer has ${capability.label}."
    is AppAccessFinding.AppRemoved ->
        "$label was removed."
}

private fun AppAccessFinding.alertOrNull(nowMs: Long, idFactory: () -> String): GuardianAlert? =
    when (this) {
        is AppAccessFinding.NewApp -> newAppAlert(app, nowMs, idFactory)
        is AppAccessFinding.CapabilityGained ->
            if (!app.isSystem && capability in WATCH_SENSITIVE) {
                GuardianAlert(
                    id = idFactory(),
                    level = AlertLevel.WARNING,
                    title = "${app.label} gained ${capability.label}",
                    body = "${app.label} gained access to ${capability.label} since I last " +
                        "looked. If you did not expect that, it is worth a glance.",
                    categoryId = APP_ACCESS_CATEGORY,
                    createdAtEpochMs = nowMs,
                )
            } else {
                null
            }
        is AppAccessFinding.CapabilityRevoked -> null
        is AppAccessFinding.AppRemoved -> null
    }

private fun newAppAlert(app: AppGrant, nowMs: Long, idFactory: () -> String): GuardianAlert? {
    // Immutable rule: a new app holding camera + microphone + location, whether
    // user-installed or system, always raises a CRITICAL alert (see
    // docs/memory-architecture.md, lane 5). Never tunable.
    if (app.granted.containsAll(SURVEILLANCE_COMBO)) {
        return GuardianAlert(
            id = idFactory(),
            level = AlertLevel.CRITICAL,
            title = "New app can see, hear, and locate you",
            body = "${app.label} was just installed already holding camera, microphone, and " +
                "location. Together those let it see, hear, and locate you. If you did not " +
                "install it, it is worth a look.",
            categoryId = APP_ACCESS_CATEGORY,
            createdAtEpochMs = nowMs,
        )
    }
    if (app.isSystem) return null
    val sensitive = WATCH_SENSITIVE.filter { it in app.granted }.sortedBy { it.ordinal }
    if (sensitive.isEmpty()) return null
    return GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "New app with access: ${app.label}",
        body = "${app.label} was just installed and already holds " +
            "${formatCapabilities(sensitive)}. If you did not expect that, it is worth a glance.",
        categoryId = APP_ACCESS_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}

// ---- caps and rollups ----

private fun capObservations(
    observations: List<Observation>,
    nowMs: Long,
    idFactory: () -> String,
): List<Observation> {
    if (observations.size <= MAX_APP_ACCESS_OBSERVATIONS) return observations
    val kept = observations.take(MAX_APP_ACCESS_OBSERVATIONS)
    val hidden = observations.size - kept.size
    return kept + Observation(
        id = idFactory(),
        categoryId = APP_ACCESS_CATEGORY,
        summary = "...and $hidden more app access changes.",
        epochMs = nowMs,
        kind = ObservationKind.CHANGE,
    )
}

private fun capAlerts(
    alerts: List<GuardianAlert>,
    nowMs: Long,
    idFactory: () -> String,
): List<GuardianAlert> {
    if (alerts.size <= MAX_APP_ACCESS_ALERTS) return alerts
    // Stable sort keeps finding order within a severity; CRITICAL leads.
    val bySeverity = alerts.sortedByDescending { severityRank(it.level) }
    val critical = bySeverity.filter { it.level == AlertLevel.CRITICAL }
    val rest = bySeverity.filter { it.level != AlertLevel.CRITICAL }
    // A SURVEILLANCE_COMBO CRITICAL is never dropped, even past the cap.
    val kept = critical + rest.take((MAX_APP_ACCESS_ALERTS - critical.size).coerceAtLeast(0))
    val hidden = alerts.size - kept.size
    if (hidden <= 0) return kept
    return kept + GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "More apps changed access",
        body = "...and $hidden more apps changed what they can reach.",
        categoryId = APP_ACCESS_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}

private fun severityRank(level: AlertLevel): Int = when (level) {
    AlertLevel.CRITICAL -> 3
    AlertLevel.WARNING -> 2
    AlertLevel.SUGGESTION -> 1
    AlertLevel.INFO -> 0
}

/** Join capability labels as "Camera", "Camera and Location", "Camera, Microphone, and Location". */
private fun formatCapabilities(capabilities: Collection<PermCapability>): String {
    val labels = capabilities.sortedBy { it.ordinal }.map { it.label }
    return when (labels.size) {
        0 -> "nothing sensitive"
        1 -> labels[0]
        2 -> "${labels[0]} and ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + ", and " + labels.last()
    }
}
