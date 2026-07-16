package com.mink.guardian

import com.mink.monitor.HighRiskFinding
import com.mink.monitor.defaultRoleLabel

/** Caps to keep one noisy sweep (e.g. a work profile enrolling several surfaces) readable. */
const val MAX_HIGH_RISK_OBSERVATIONS = 12
const val MAX_HIGH_RISK_ALERTS = 6

/** The timeline category every high-risk observation and alert is filed under. */
const val HIGH_RISK_CATEGORY = "high_risk"

/** Observations and alerts produced from one sweep's high-risk diff. */
class HighRiskGuardResult(
    val observations: List<Observation>,
    val alerts: List<GuardianAlert>,
)

/**
 * Map high-risk diff findings to guardian observations/alerts. Pure; id/time
 * injected.
 *
 * Every finding becomes one observation ([ObservationKind.CHANGE], categoryId
 * [HIGH_RISK_CATEGORY]). The gains that a stalkerware or MITM setup would need
 * become WARNING alerts; the losses (a service disabled, an admin removed) and
 * the ambiguous cases (a VPN turning on) stay observation-only, phrased neutrally.
 *
 * Alert policy (all WARNING, and none [GuardianAlert.fromImmutableRule]):
 * - AccessibilityEnabled: WARNING — a new service can read the screen and act.
 * - NotificationListenerEnabled: WARNING — a new listener can read notifications,
 *   including messages and one-time codes.
 * - DeviceAdminAdded: WARNING — a device owner uses a distinct, stronger title.
 * - UserCertificateAdded: WARNING — a new CA can let its owner read private traffic.
 * - DefaultAppChanged with a new package: WARNING, per role.
 * - VpnActivated: observation only — most VPNs are user-run and legitimate, and
 *   the owner is unknowable, so an alert would misfire too often.
 * - Every *Disabled / *Removed / VpnDeactivated / a default role losing its app:
 *   observation only, as neutral or good news.
 *
 * Design note (see docs/memory-architecture.md, lane 5): iteration F introduces
 * NO lane-5 immutable rule. Every one of these surfaces has a legitimate use — an
 * accessibility app, a corporate MDM device admin, a developer or proxy CA, a
 * third-party keyboard, a personal VPN — so a never-tunable CRITICAL would misfire
 * on real users. These are WARNINGs under the alertness dial and per-source mute;
 * the camera+microphone+location surveillance combo remains the only immutable
 * rule. A cross-surface "one new app holds accessibility + notification-listener +
 * device-admin" spyware combo is a candidate future immutable rule, deliberately
 * not added now.
 *
 * Caps mirror [appAccessFindingsToGuardian]: observations at
 * [MAX_HIGH_RISK_OBSERVATIONS] with a rollup, alerts at [MAX_HIGH_RISK_ALERTS]
 * with a WARNING rollup. There is no never-drop case here, since no finding is
 * CRITICAL.
 */
fun highRiskFindingsToGuardian(
    findings: List<HighRiskFinding>,
    nowMs: Long,
    idFactory: () -> String,
): HighRiskGuardResult {
    val observations = mutableListOf<Observation>()
    val alerts = mutableListOf<GuardianAlert>()

    for (finding in findings) {
        observations += Observation(
            id = idFactory(),
            categoryId = HIGH_RISK_CATEGORY,
            summary = finding.observationSummary(),
            epochMs = nowMs,
            kind = ObservationKind.CHANGE,
        )
        finding.alertOrNull(nowMs, idFactory)?.let { alerts += it }
    }

    return HighRiskGuardResult(
        observations = capObservations(observations, nowMs, idFactory),
        alerts = capAlerts(alerts, nowMs, idFactory),
    )
}

// ---- per-finding wording ----

private fun HighRiskFinding.observationSummary(): String = when (this) {
    is HighRiskFinding.AccessibilityEnabled ->
        "${component.label} was enabled as an accessibility service, so it can read the screen and act on your behalf."
    is HighRiskFinding.AccessibilityDisabled ->
        "${component.label} is no longer an accessibility service."
    is HighRiskFinding.NotificationListenerEnabled ->
        "${component.label} can now read your notifications."
    is HighRiskFinding.NotificationListenerDisabled ->
        "${component.label} can no longer read your notifications."
    is HighRiskFinding.DeviceAdminAdded ->
        if (admin.isDeviceOwner) {
            "${admin.label} became a device owner, which controls this device."
        } else {
            "${admin.label} became a device admin."
        }
    is HighRiskFinding.DeviceAdminRemoved ->
        "${admin.label} is no longer a device admin."
    is HighRiskFinding.UserCertificateAdded ->
        "${cert.label} was added to your trusted certificate authorities."
    is HighRiskFinding.UserCertificateRemoved ->
        "${cert.label} was removed from your trusted certificate authorities."
    is HighRiskFinding.DefaultAppChanged -> {
        val roleLabel = defaultRoleLabel(role)
        fun name(pkg: String?, label: String?) = label?.takeIf { it.isNotBlank() } ?: pkg ?: "an app"
        when {
            toPackage == null -> "Your $roleLabel is no longer set. It was ${name(fromPackage, fromLabel)}."
            fromPackage == null -> "Your $roleLabel is now ${name(toPackage, toLabel)}."
            else -> "Your $roleLabel changed from ${name(fromPackage, fromLabel)} to ${name(toPackage, toLabel)}."
        }
    }
    is HighRiskFinding.VpnActivated ->
        "A VPN is now routing this device's traffic. Mink cannot tell which app runs it."
    is HighRiskFinding.VpnDeactivated ->
        "The VPN that was routing this device's traffic has stopped."
}

private fun HighRiskFinding.alertOrNull(nowMs: Long, idFactory: () -> String): GuardianAlert? =
    when (this) {
        is HighRiskFinding.AccessibilityEnabled -> GuardianAlert(
            id = idFactory(),
            level = AlertLevel.WARNING,
            title = "New accessibility service: ${component.label}",
            body = "${component.label} can now read everything on your screen and act on your " +
                "behalf. If you did not turn this on, it is worth a look.",
            categoryId = HIGH_RISK_CATEGORY,
            createdAtEpochMs = nowMs,
        )
        is HighRiskFinding.NotificationListenerEnabled -> GuardianAlert(
            id = idFactory(),
            level = AlertLevel.WARNING,
            title = "${component.label} can now read your notifications",
            body = "${component.label} can now read your notifications, including messages and " +
                "the one-time codes apps send. If you did not turn this on, it is worth a look.",
            categoryId = HIGH_RISK_CATEGORY,
            createdAtEpochMs = nowMs,
        )
        is HighRiskFinding.DeviceAdminAdded ->
            if (admin.isDeviceOwner) {
                GuardianAlert(
                    id = idFactory(),
                    level = AlertLevel.WARNING,
                    title = "A device owner was added: ${admin.label}",
                    body = "${admin.label} became a device owner. A device owner controls this " +
                        "device and can resist being removed. If you did not set this up, it is " +
                        "worth a look.",
                    categoryId = HIGH_RISK_CATEGORY,
                    createdAtEpochMs = nowMs,
                )
            } else {
                GuardianAlert(
                    id = idFactory(),
                    level = AlertLevel.WARNING,
                    title = "${admin.label} became a device admin",
                    body = "${admin.label} became a device admin, which lets it change security " +
                        "settings and lock or wipe the device. If you did not set this up, it is " +
                        "worth a look.",
                    categoryId = HIGH_RISK_CATEGORY,
                    createdAtEpochMs = nowMs,
                )
            }
        is HighRiskFinding.UserCertificateAdded -> GuardianAlert(
            id = idFactory(),
            level = AlertLevel.WARNING,
            title = "New certificate authority: ${cert.label}",
            body = "${cert.label} was added to your trusted certificates. A certificate authority " +
                "can let its owner read traffic your apps think is private. If you did not add it " +
                "(for work or a developer tool), it is worth a look.",
            categoryId = HIGH_RISK_CATEGORY,
            createdAtEpochMs = nowMs,
        )
        is HighRiskFinding.DefaultAppChanged ->
            if (toPackage == null) {
                null
            } else {
                val roleLabel = defaultRoleLabel(role)
                fun name(pkg: String?, label: String?) = label?.takeIf { it.isNotBlank() } ?: pkg ?: "an app"
                val change = if (fromPackage == null) {
                    "Your $roleLabel is now ${name(toPackage, toLabel)}."
                } else {
                    "Your $roleLabel changed from ${name(fromPackage, fromLabel)} to ${name(toPackage, toLabel)}."
                }
                GuardianAlert(
                    id = idFactory(),
                    level = AlertLevel.WARNING,
                    title = "Your $roleLabel changed",
                    body = "$change ${roleReason(role)} If you did not change this, it is worth a look.",
                    categoryId = HIGH_RISK_CATEGORY,
                    createdAtEpochMs = nowMs,
                )
            }
        is HighRiskFinding.AccessibilityDisabled -> null
        is HighRiskFinding.NotificationListenerDisabled -> null
        is HighRiskFinding.DeviceAdminRemoved -> null
        is HighRiskFinding.UserCertificateRemoved -> null
        is HighRiskFinding.VpnActivated -> null
        is HighRiskFinding.VpnDeactivated -> null
    }

/** Why a change of default for [role] matters, in one plain sentence. */
private fun roleReason(role: String): String = when (role) {
    "sms" -> "The default SMS app reads your texts, including the one-time codes apps send."
    "browser" -> "The default browser sees every link you open."
    "ime" -> "A keyboard can see everything you type, including passwords."
    "dialer" -> "The default phone app can see and place your calls."
    else -> "This app now handles a sensitive default on your device."
}

// ---- caps and rollups ----

private fun capObservations(
    observations: List<Observation>,
    nowMs: Long,
    idFactory: () -> String,
): List<Observation> {
    if (observations.size <= MAX_HIGH_RISK_OBSERVATIONS) return observations
    val kept = observations.take(MAX_HIGH_RISK_OBSERVATIONS)
    val hidden = observations.size - kept.size
    return kept + Observation(
        id = idFactory(),
        categoryId = HIGH_RISK_CATEGORY,
        summary = "...and $hidden more security changes.",
        epochMs = nowMs,
        kind = ObservationKind.CHANGE,
    )
}

private fun capAlerts(
    alerts: List<GuardianAlert>,
    nowMs: Long,
    idFactory: () -> String,
): List<GuardianAlert> {
    if (alerts.size <= MAX_HIGH_RISK_ALERTS) return alerts
    val kept = alerts.take(MAX_HIGH_RISK_ALERTS)
    val hidden = alerts.size - kept.size
    return kept + GuardianAlert(
        id = idFactory(),
        level = AlertLevel.WARNING,
        title = "More security settings changed",
        body = "...and $hidden more security settings changed.",
        categoryId = HIGH_RISK_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}
