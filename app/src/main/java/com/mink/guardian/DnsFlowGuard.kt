package com.mink.guardian

import com.mink.monitor.DnsLookup

/** The timeline category every DNS-flow observation and alert is filed under. */
const val DNS_FLOW_CATEGORY = "dns_flow"

/** An app that contacts at least this many distinct known trackers is worth a quiet note. */
const val TRACKER_CONTACT_THRESHOLD = 3

/** Caps to keep one evaluation readable when many apps are chatty. */
const val MAX_DNS_FLOW_OBSERVATIONS = 8
const val MAX_DNS_FLOW_ALERTS = 4

/** How many example hosts to name in the copy. */
private const val EXAMPLE_HOSTS = 3

/** One user app seen contacting several known trackers. */
data class AppTrackerContact(
    val uid: Int,
    val label: String,
    val trackerHosts: List<String>,
)

/** A DNS-flow finding worth surfacing. Currently only the tracker-contact insight. */
sealed interface DnsFlowFinding {
    data class ChattyTrackers(val contact: AppTrackerContact) : DnsFlowFinding
}

/** Observations and alerts produced from one DNS-flow evaluation. */
class DnsFlowGuardResult(
    val observations: List<Observation>,
    val alerts: List<GuardianAlert>,
    /** The uids reported this round, so the caller can avoid repeating them. */
    val reportedUids: Set<Int>,
)

/**
 * Pure evaluation of the current DNS rollup. Groups a user app's lookups, counts
 * the distinct known trackers among them (via the injected [isTracker]), and
 * yields a finding for each user app over [TRACKER_CONTACT_THRESHOLD] that has
 * not already been reported ([alreadyReported]). System apps are skipped — their
 * telemetry is expected — matching the data-use guard.
 *
 * Deterministic and sorted (most trackers first, then uid). No Android APIs.
 */
fun analyzeDnsFlows(
    lookups: List<DnsLookup>,
    isTracker: (String) -> Boolean,
    alreadyReported: Set<Int>,
): List<DnsFlowFinding> {
    return lookups.asSequence()
        .filter { !it.isSystem && it.uid >= 0 }
        .groupBy { it.uid }
        .mapNotNull { (uid, appLookups) ->
            if (uid in alreadyReported) return@mapNotNull null
            val trackers = appLookups.map { it.host }.filter(isTracker).distinct()
            if (trackers.size < TRACKER_CONTACT_THRESHOLD) return@mapNotNull null
            val label = appLookups.first().label
            AppTrackerContact(uid, label, trackers.sorted())
        }
        .sortedWith(compareByDescending<AppTrackerContact> { it.trackerHosts.size }.thenBy { it.uid })
        .map { DnsFlowFinding.ChattyTrackers(it) }
        .toList()
}

/**
 * Map DNS-flow findings to guardian observations/alerts. Pure; id/time injected.
 *
 * Every finding becomes one observation ([ObservationKind.CHANGE], categoryId
 * [DNS_FLOW_CATEGORY]) and one SUGGESTION alert — an app talking to trackers is
 * common and rarely urgent, so this is an insight, never a CRITICAL and never
 * [GuardianAlert.fromImmutableRule]. The threshold is deterministic and tunable
 * (lane 5: this introduces NO immutable rule). The copy is names-only and calm.
 */
fun dnsFlowFindingsToGuardian(
    findings: List<DnsFlowFinding>,
    nowMs: Long,
    idFactory: () -> String,
): DnsFlowGuardResult {
    val observations = mutableListOf<Observation>()
    val alerts = mutableListOf<GuardianAlert>()
    val reported = mutableSetOf<Int>()

    findings.forEachIndexed { index, finding ->
        when (finding) {
            is DnsFlowFinding.ChattyTrackers -> {
                // Only mark an app reported if it gets its OWN observation (survives the
                // cap). Apps folded into the rollup stay eligible to surface next sweep.
                if (index < MAX_DNS_FLOW_OBSERVATIONS) reported += finding.contact.uid
                observations += Observation(
                    id = idFactory(),
                    categoryId = DNS_FLOW_CATEGORY,
                    summary = finding.observationSummary(),
                    epochMs = nowMs,
                    kind = ObservationKind.CHANGE,
                )
                alerts += finding.alert(nowMs, idFactory)
            }
        }
    }

    return DnsFlowGuardResult(
        observations = capObservations(observations, nowMs, idFactory),
        alerts = capAlerts(alerts, nowMs, idFactory),
        reportedUids = reported,
    )
}

// ---- per-finding wording ----

private fun DnsFlowFinding.ChattyTrackers.observationSummary(): String {
    val n = contact.trackerHosts.size
    return "${contact.label} looked up $n known tracker or ad ${servers(n)}."
}

private fun DnsFlowFinding.ChattyTrackers.alert(nowMs: Long, idFactory: () -> String): GuardianAlert {
    val n = contact.trackerHosts.size
    val examples = contact.trackerHosts.take(EXAMPLE_HOSTS).joinToString(", ")
    val more = if (n > EXAMPLE_HOSTS) ", and others" else ""
    return GuardianAlert(
        id = idFactory(),
        level = AlertLevel.SUGGESTION,
        title = "${contact.label} contacted trackers",
        body = "${contact.label} looked up $n known tracker or ad ${servers(n)} — $examples$more. " +
            "Many apps do, so this is just what Mink noticed, not a certain problem. Mink sees the " +
            "names looked up, nothing more.",
        categoryId = DNS_FLOW_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}

private fun servers(n: Int): String = if (n == 1) "server" else "servers"

// ---- caps and rollups ----

private fun capObservations(
    observations: List<Observation>,
    nowMs: Long,
    idFactory: () -> String,
): List<Observation> {
    if (observations.size <= MAX_DNS_FLOW_OBSERVATIONS) return observations
    val kept = observations.take(MAX_DNS_FLOW_OBSERVATIONS)
    val hidden = observations.size - kept.size
    return kept + Observation(
        id = idFactory(),
        categoryId = DNS_FLOW_CATEGORY,
        summary = "...and $hidden more apps contacted trackers.",
        epochMs = nowMs,
        kind = ObservationKind.CHANGE,
    )
}

private fun capAlerts(
    alerts: List<GuardianAlert>,
    nowMs: Long,
    idFactory: () -> String,
): List<GuardianAlert> {
    if (alerts.size <= MAX_DNS_FLOW_ALERTS) return alerts
    val kept = alerts.take(MAX_DNS_FLOW_ALERTS)
    val hidden = alerts.size - kept.size
    return kept + GuardianAlert(
        id = idFactory(),
        level = AlertLevel.SUGGESTION,
        title = "More apps contacted trackers",
        body = "...and $hidden more apps looked up known trackers.",
        categoryId = DNS_FLOW_CATEGORY,
        createdAtEpochMs = nowMs,
    )
}
