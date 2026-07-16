package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the data-use analyzer's [formatBytes], [analyzeDataUsage],
 * [dataUseWindow], and the [DataUsageReport.topConsumers] helper. No Android APIs
 * are touched — per-app volumes are built by hand so the threshold, system-app,
 * both-findings, window-decision, and ordering rules can be asserted exactly.
 */
class NetworkUsageTest {

    private fun usage(
        uid: Int = 10001,
        label: String = "App",
        isSystem: Boolean = false,
        wifiBytes: Long = 0L,
        mobileBytes: Long = 0L,
        roamingBytes: Long = 0L,
        backgroundMobileBytes: Long = 0L,
    ): AppDataUsage = AppDataUsage(
        uid = uid,
        packageName = "pkg.$uid",
        label = label,
        isSystem = isSystem,
        wifiBytes = wifiBytes,
        mobileBytes = mobileBytes,
        roamingBytes = roamingBytes,
        backgroundMobileBytes = backgroundMobileBytes,
    )

    // ---- formatBytes ----

    @Test
    fun formatBytesCoversEachUnit() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
        assertEquals("1.5 KB", formatBytes(1536L))
        assertEquals("5.0 MB", formatBytes(5L * 1024 * 1024))
        assertEquals("2.0 GB", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun formatBytesPromotesValuesJustUnderAUnitCeiling() {
        // A value that rounds up to 1024.0 of its unit must promote, not render
        // "1024.0 KB" / "1024.0 MB".
        assertEquals("1.0 MB", formatBytes(1_048_575L))
        assertEquals("1.0 GB", formatBytes(1_073_741_823L))
    }

    // ---- totalBytes ----

    @Test
    fun totalBytesSumsWifiAndMobileOnly() {
        // Roaming and background cellular are subsets of the mobile total, so they
        // must not be counted again.
        val app = usage(wifiBytes = 100L, mobileBytes = 40L, roamingBytes = 30L, backgroundMobileBytes = 20L)
        assertEquals(140L, app.totalBytes)
    }

    // ---- background-cellular threshold ----

    @Test
    fun backgroundMobileAtThresholdFires() {
        val findings = analyzeDataUsage(listOf(usage(backgroundMobileBytes = BACKGROUND_MOBILE_ALERT_BYTES)))
        assertEquals(1, findings.size)
        assertTrue(findings.single() is DataUsageFinding.HeavyBackgroundMobile)
    }

    @Test
    fun backgroundMobileJustUnderThresholdIsSilent() {
        val app = usage(backgroundMobileBytes = BACKGROUND_MOBILE_ALERT_BYTES - 1)
        assertTrue(analyzeDataUsage(listOf(app)).isEmpty())
    }

    // ---- roaming threshold ----

    @Test
    fun roamingAtThresholdFires() {
        val findings = analyzeDataUsage(listOf(usage(roamingBytes = ROAMING_ALERT_BYTES)))
        assertEquals(1, findings.size)
        assertTrue(findings.single() is DataUsageFinding.HeavyRoaming)
    }

    @Test
    fun roamingJustUnderThresholdIsSilent() {
        val app = usage(roamingBytes = ROAMING_ALERT_BYTES - 1)
        assertTrue(analyzeDataUsage(listOf(app)).isEmpty())
    }

    // ---- system apps are ignored ----

    @Test
    fun systemAppOverBothThresholdsIsIgnored() {
        val app = usage(
            isSystem = true,
            backgroundMobileBytes = BACKGROUND_MOBILE_ALERT_BYTES * 2,
            roamingBytes = ROAMING_ALERT_BYTES * 2,
        )
        assertTrue(analyzeDataUsage(listOf(app)).isEmpty())
    }

    // ---- one app, both findings ----

    @Test
    fun userAppOverBothThresholdsProducesTwoFindings() {
        val app = usage(
            backgroundMobileBytes = BACKGROUND_MOBILE_ALERT_BYTES,
            roamingBytes = ROAMING_ALERT_BYTES,
        )
        val findings = analyzeDataUsage(listOf(app))
        assertEquals(2, findings.size)
        // Background sorts ahead of roaming for the same app.
        assertTrue(findings[0] is DataUsageFinding.HeavyBackgroundMobile)
        assertTrue(findings[1] is DataUsageFinding.HeavyRoaming)
    }

    // ---- empty ----

    @Test
    fun emptyInputProducesNoFindings() {
        assertTrue(analyzeDataUsage(emptyList()).isEmpty())
    }

    // ---- deterministic ordering ----

    @Test
    fun findingsSortedByTypeThenBytesDescendingThenUid() {
        val a = usage(uid = 10001, backgroundMobileBytes = 90L * 1024 * 1024)
        val b = usage(uid = 10002, backgroundMobileBytes = 70L * 1024 * 1024, roamingBytes = 40L * 1024 * 1024)
        val c = usage(uid = 10003, roamingBytes = 30L * 1024 * 1024)

        // Input order deliberately differs from the output order.
        val findings = analyzeDataUsage(listOf(c, a, b))

        // Background rank (0) first, descending bytes; then roaming rank (1),
        // descending bytes.
        assertEquals(
            listOf("bg:10001", "bg:10002", "roam:10002", "roam:10003"),
            findings.map { it.describe() },
        )
    }

    @Test
    fun equalFindingBytesBreakTiesByUidAscending() {
        val higherUid = usage(uid = 10004, backgroundMobileBytes = 60L * 1024 * 1024)
        val lowerUid = usage(uid = 10002, backgroundMobileBytes = 60L * 1024 * 1024)
        val findings = analyzeDataUsage(listOf(higherUid, lowerUid))
        assertEquals(listOf("bg:10002", "bg:10004"), findings.map { it.describe() })
    }

    // ---- topConsumers ----

    @Test
    fun topConsumersTakesFirstNInReportOrder() {
        val a = usage(uid = 10001, label = "A", wifiBytes = 100L)
        val b = usage(uid = 10002, label = "B", wifiBytes = 50L)
        val c = usage(uid = 10003, label = "C", wifiBytes = 10L)
        val report = DataUsageReport(windowStartMs = 0L, windowEndMs = 1L, apps = listOf(a, b, c), deviceTotalBytes = 160L)
        assertEquals(listOf("A", "B"), report.topConsumers(2).map { it.label })
    }

    @Test
    fun topConsumersBeyondSizeReturnsEveryApp() {
        val a = usage(uid = 10001, label = "A", wifiBytes = 100L)
        val b = usage(uid = 10002, label = "B", wifiBytes = 50L)
        val report = DataUsageReport(windowStartMs = 0L, windowEndMs = 1L, apps = listOf(a, b), deviceTotalBytes = 150L)
        assertEquals(listOf("A", "B"), report.topConsumers(10).map { it.label })
    }

    // ---- dataUseWindow ----

    private val now = 1_000_000_000_000L

    @Test
    fun dataUseWindowSeedsWhenNoCursor() {
        assertEquals(DataUseDecision.Seed(now), dataUseWindow(null, now))
    }

    @Test
    fun dataUseWindowSeedsWhenNowEqualsCursor() {
        assertEquals(DataUseDecision.Seed(now), dataUseWindow(now, now))
    }

    @Test
    fun dataUseWindowSeedsWhenClockRanBackwards() {
        assertEquals(DataUseDecision.Seed(now), dataUseWindow(now + 1L, now))
    }

    @Test
    fun dataUseWindowSkipsWhenGapUnderMinInterval() {
        val last = now - 1L * 60 * 60 * 1000 // 1 hour, under the 6h minimum
        assertEquals(DataUseDecision.Skip, dataUseWindow(last, now))
    }

    @Test
    fun dataUseWindowAnalyzesAtExactlyMinInterval() {
        val last = now - DATA_USE_MIN_INTERVAL_MS
        assertEquals(DataUseDecision.Analyze(last, now), dataUseWindow(last, now))
    }

    @Test
    fun dataUseWindowAnalyzesWithinTheWindow() {
        val last = now - 8L * 60 * 60 * 1000 // 8 hours, between 6h and 26h
        assertEquals(DataUseDecision.Analyze(last, now), dataUseWindow(last, now))
    }

    @Test
    fun dataUseWindowAnalyzesAtExactlyMaxWindow() {
        val last = now - DATA_USE_MAX_WINDOW_MS
        assertEquals(DataUseDecision.Analyze(last, now), dataUseWindow(last, now))
    }

    @Test
    fun dataUseWindowReseedsWhenGapExceedsMaxWindow() {
        val last = now - 30L * 60 * 60 * 1000 // 30 hours, over the 26h clamp
        assertEquals(DataUseDecision.Seed(now), dataUseWindow(last, now))
    }

    private fun DataUsageFinding.describe(): String = when (this) {
        is DataUsageFinding.HeavyBackgroundMobile -> "bg:${app.uid}"
        is DataUsageFinding.HeavyRoaming -> "roam:${app.uid}"
    }
}
