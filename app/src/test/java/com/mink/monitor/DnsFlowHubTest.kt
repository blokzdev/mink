package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-global DNS rollup hub and its persisted-entry round trip. These
 * stay app-side (unlike the pure packet-parser tests in :guardian-core's
 * DnsFlowTest) because the hub and DnsRollupEntry are app-module types.
 */
class DnsFlowHubTest {

    @Test
    fun hubRollsUpByUidAndHostAndCaps() {
        DnsFlowHub.clear()
        DnsFlowHub.record(10201, "com.mink", "Mink", false, "example.com", 100L)
        DnsFlowHub.record(10201, "com.mink", "Mink", false, "example.com", 200L)
        DnsFlowHub.record(10128, "com.google", "Google", true, "mtalk.google.com", 150L)

        val report = DnsFlowHub.report.value
        assertEquals(2, report.lookups.size)                        // two distinct (uid, host)
        val mink = report.lookups.first { it.host == "example.com" }
        assertEquals(2, mink.count)
        assertEquals(100L, mink.firstSeenMs)
        assertEquals(200L, mink.lastSeenMs)
        assertEquals(2, report.appCount)
        // Newest activity first.
        assertTrue(report.lookups.first().lastSeenMs >= report.lookups.last().lastSeenMs)
        DnsFlowHub.clear()
        assertEquals(0, DnsFlowHub.report.value.lookups.size)
    }

    @Test
    fun rollupEntryRoundTrips() {
        val lookup = DnsLookup(10201, "com.mink", "Mink", false, "example.com", 100L, 200L, 3)
        val back = lookup.toEntry().toLookup()
        assertEquals(lookup, back)
    }

    @Test
    fun hubEvictsOldestBeyondTheCap() {
        DnsFlowHub.clear()
        // Record more than the cap (500) distinct hosts with strictly increasing time.
        val total = 620
        for (i in 0 until total) {
            DnsFlowHub.record(10000, "com.app", "App", false, "host$i.example", i.toLong())
        }
        val report = DnsFlowHub.report.value
        assertEquals(500, report.lookups.size)                     // capped
        // The earliest-seen hosts are gone; the most recent survive.
        val survivingHosts = report.lookups.map { it.host }.toSet()
        assertFalse(survivingHosts.contains("host0.example"))       // evicted
        assertTrue(survivingHosts.contains("host${total - 1}.example")) // kept
        DnsFlowHub.clear()
    }
}
