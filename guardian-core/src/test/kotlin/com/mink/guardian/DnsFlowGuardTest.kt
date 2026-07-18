package com.mink.guardian

import com.mink.monitor.DnsLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the DNS-flow guard: [analyzeDnsFlows] grouping/threshold and
 * [dnsFlowFindingsToGuardian] mapping. No Android APIs; the tracker classifier is
 * injected as a simple set membership.
 */
class DnsFlowGuardTest {

    private val trackerHosts = setOf("doubleclick.net", "app-measurement.com", "adjust.com", "appsflyer.com")
    private val isTracker: (String) -> Boolean = { it in trackerHosts }

    private fun lookup(
        uid: Int,
        host: String,
        label: String = "App$uid",
        isSystem: Boolean = false,
    ) = DnsLookup(uid, "pkg$uid", label, isSystem, host, 0L, 0L, 1)

    private fun sequentialIds(): () -> String {
        var n = 0
        return { "id-${n++}" }
    }

    @Test
    fun firesForAUserAppOverTheTrackerThreshold() {
        val lookups = listOf(
            lookup(10001, "doubleclick.net", label = "Flashlight"),
            lookup(10001, "app-measurement.com", label = "Flashlight"),
            lookup(10001, "adjust.com", label = "Flashlight"),
            lookup(10001, "example.com", label = "Flashlight"),   // not a tracker
        )
        val findings = analyzeDnsFlows(lookups, isTracker, emptySet())
        assertEquals(1, findings.size)
        val contact = (findings.first() as DnsFlowFinding.ChattyTrackers).contact
        assertEquals(10001, contact.uid)
        assertEquals(3, contact.trackerHosts.size)               // the non-tracker is excluded
        assertTrue(contact.trackerHosts.contains("doubleclick.net"))
    }

    @Test
    fun ignoresAppsBelowThreshold() {
        val lookups = listOf(
            lookup(10002, "doubleclick.net"),
            lookup(10002, "adjust.com"),                          // only 2 distinct trackers
        )
        assertTrue(analyzeDnsFlows(lookups, isTracker, emptySet()).isEmpty())
    }

    @Test
    fun skipsSystemApps() {
        val lookups = listOf(
            lookup(1000, "doubleclick.net", isSystem = true),
            lookup(1000, "app-measurement.com", isSystem = true),
            lookup(1000, "adjust.com", isSystem = true),
        )
        assertTrue(analyzeDnsFlows(lookups, isTracker, emptySet()).isEmpty())
    }

    @Test
    fun skipsAlreadyReportedUids() {
        val lookups = listOf(
            lookup(10003, "doubleclick.net"),
            lookup(10003, "app-measurement.com"),
            lookup(10003, "adjust.com"),
        )
        assertTrue(analyzeDnsFlows(lookups, isTracker, setOf(10003)).isEmpty())
    }

    @Test
    fun countsDistinctTrackersNotRepeats() {
        val lookups = listOf(
            lookup(10004, "doubleclick.net"),
            lookup(10004, "doubleclick.net"),                     // duplicate host
            lookup(10004, "adjust.com"),
        )
        // Only 2 distinct trackers -> below threshold.
        assertTrue(analyzeDnsFlows(lookups, isTracker, emptySet()).isEmpty())
    }

    @Test
    fun ordersByTrackerCountDescending() {
        val lookups = listOf(
            lookup(10005, "doubleclick.net"),
            lookup(10005, "adjust.com"),
            lookup(10005, "appsflyer.com"),
            lookup(10006, "doubleclick.net"),
            lookup(10006, "adjust.com"),
            lookup(10006, "appsflyer.com"),
            lookup(10006, "app-measurement.com"),                 // 4 trackers
        )
        val findings = analyzeDnsFlows(lookups, isTracker, emptySet())
        assertEquals(2, findings.size)
        assertEquals(10006, (findings[0] as DnsFlowFinding.ChattyTrackers).contact.uid)  // most first
    }

    @Test
    fun mapsToSuggestionAlertsAndObservationsWithReportedUids() {
        val findings = analyzeDnsFlows(
            listOf(
                lookup(10007, "doubleclick.net", label = "Weather"),
                lookup(10007, "adjust.com", label = "Weather"),
                lookup(10007, "appsflyer.com", label = "Weather"),
            ),
            isTracker, emptySet(),
        )
        val result = dnsFlowFindingsToGuardian(findings, nowMs = 123L, idFactory = sequentialIds())

        assertEquals(1, result.observations.size)
        assertEquals(1, result.alerts.size)
        assertEquals(setOf(10007), result.reportedUids)
        val alert = result.alerts.first()
        assertEquals(AlertLevel.SUGGESTION, alert.level)          // quiet, never CRITICAL
        assertEquals(DNS_FLOW_CATEGORY, alert.categoryId)
        assertEquals(123L, alert.createdAtEpochMs)
        assertTrue(!alert.fromImmutableRule)                      // no lane-5 immutable
        assertTrue(alert.title.contains("Weather"))
        assertTrue(!alert.body.contains("!"))                    // calm copy
    }

    @Test
    fun capsAlertsAndObservationsWithARollup() {
        val lookups = (1..10).flatMap { i ->
            listOf(
                lookup(10100 + i, "doubleclick.net", label = "App$i"),
                lookup(10100 + i, "adjust.com", label = "App$i"),
                lookup(10100 + i, "appsflyer.com", label = "App$i"),
            )
        }
        val findings = analyzeDnsFlows(lookups, isTracker, emptySet())
        assertEquals(10, findings.size)
        val result = dnsFlowFindingsToGuardian(findings, nowMs = 1L, idFactory = sequentialIds())
        assertEquals(MAX_DNS_FLOW_ALERTS + 1, result.alerts.size)             // kept + one rollup
        assertEquals(MAX_DNS_FLOW_OBSERVATIONS + 1, result.observations.size)
        assertTrue(result.alerts.last().title.contains("More apps"))
        // Only apps with their own observation are marked reported; the two folded
        // into the rollup stay eligible to surface individually next sweep.
        assertEquals(MAX_DNS_FLOW_OBSERVATIONS, result.reportedUids.size)
    }
}
