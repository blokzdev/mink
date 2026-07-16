package com.mink.guardian

import com.mink.monitor.AppDataUsage
import com.mink.monitor.DataUsageFinding
import com.mink.monitor.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [dataUsageFindingsToGuardian]: WARNING severity, the
 * data-use category, the volumes-only honesty of the copy, calm wording, injected
 * ids, and the observation/alert caps. Findings are built by hand and ids/time
 * injected, so every branch is asserted deterministically.
 */
class NetworkUsageGuardTest {

    /** Deterministic id source so ids can be traced back to the factory. */
    private fun sequentialIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun app(
        uid: Int = 10001,
        label: String = "App",
        backgroundMobileBytes: Long = 0L,
        roamingBytes: Long = 0L,
    ): AppDataUsage = AppDataUsage(
        uid = uid,
        packageName = "pkg.$uid",
        label = label,
        isSystem = false,
        wifiBytes = 0L,
        mobileBytes = 0L,
        roamingBytes = roamingBytes,
        backgroundMobileBytes = backgroundMobileBytes,
    )

    private fun map(
        findings: List<DataUsageFinding>,
        nowMs: Long = 7_000L,
    ): NetworkUsageGuardResult = dataUsageFindingsToGuardian(findings, nowMs, sequentialIds())

    // ---- heavy background cellular ----

    @Test
    fun heavyBackgroundMobileRaisesWarningAndObservation() {
        val bytes = 25L * 1024 * 1024
        val finding = DataUsageFinding.HeavyBackgroundMobile(app(label = "Photos", backgroundMobileBytes = bytes))
        val result = map(listOf(finding))

        assertEquals(1, result.observations.size)
        assertEquals(1, result.alerts.size)
        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Photos used background cellular data", alert.title)
        assertEquals(DATA_USE_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertTrue(alert.body.contains(formatBytes(bytes)))
        assertTrue(alert.body.contains("in the background"))
    }

    // ---- heavy roaming ----

    @Test
    fun heavyRoamingRaisesWarningMentioningRoaming() {
        val bytes = 15L * 1024 * 1024
        val finding = DataUsageFinding.HeavyRoaming(app(label = "Maps", roamingBytes = bytes))
        val result = map(listOf(finding))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Maps used data while roaming", alert.title)
        assertEquals(DATA_USE_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertTrue(alert.body.contains(formatBytes(bytes)))
        assertTrue(alert.body.contains("roaming"))
    }

    // ---- honesty: volumes, never destinations ----

    @Test
    fun copyStaysHonestAboutVolumesNotDestinations() {
        val bg = DataUsageFinding.HeavyBackgroundMobile(
            app(uid = 10001, label = "Photos", backgroundMobileBytes = 25L * 1024 * 1024),
        )
        val roam = DataUsageFinding.HeavyRoaming(app(uid = 10002, label = "Maps", roamingBytes = 15L * 1024 * 1024))
        val result = map(listOf(bg, roam))

        for (alert in result.alerts) {
            // Nothing may imply Mink can name a concrete endpoint.
            assertFalse(alert.title.contains("server"))
            assertFalse(alert.body.contains("server"))
        }
        // Each body explicitly disclaims that the destination is knowable.
        val bgBody = result.alerts.first { it.title.contains("background") }.body
        val roamBody = result.alerts.first { it.title.contains("roaming") }.body
        assertTrue(bgBody.contains("does not reveal where it went"))
        assertTrue(roamBody.contains("not the destination"))
    }

    @Test
    fun voiceIsCalmWithNoExclamationMarks() {
        val bg = DataUsageFinding.HeavyBackgroundMobile(app(backgroundMobileBytes = 25L * 1024 * 1024))
        val roam = DataUsageFinding.HeavyRoaming(app(roamingBytes = 15L * 1024 * 1024))
        val result = map(listOf(bg, roam))

        for (alert in result.alerts) {
            assertFalse(alert.title.contains("!"))
            assertFalse(alert.body.contains("!"))
        }
        for (observation in result.observations) {
            assertFalse(observation.summary.contains("!"))
        }
    }

    // ---- observation metadata ----

    @Test
    fun observationCarriesChangeKindCategoryTimeAndBytes() {
        val now = 42_000L
        val bytes = 25L * 1024 * 1024
        val finding = DataUsageFinding.HeavyBackgroundMobile(app(label = "Photos", backgroundMobileBytes = bytes))
        val result = map(listOf(finding), nowMs = now)

        val observation = result.observations.single()
        assertEquals(now, observation.epochMs)
        assertEquals(ObservationKind.CHANGE, observation.kind)
        assertEquals(DATA_USE_CATEGORY, observation.categoryId)
        assertTrue(observation.summary.contains(formatBytes(bytes)))
        assertEquals(now, result.alerts.single().createdAtEpochMs)
    }

    @Test
    fun observationsAndAlertsCarryFactoryIds() {
        val finding = DataUsageFinding.HeavyBackgroundMobile(app(backgroundMobileBytes = 25L * 1024 * 1024))
        val result = map(listOf(finding))

        val observation = result.observations.single()
        val alert = result.alerts.single()
        assertTrue(observation.id.startsWith("gen-"))
        assertTrue(alert.id.startsWith("gen-"))
        // Ids are unique across observations and alerts.
        assertFalse(observation.id == alert.id)
    }

    // ---- one app, both findings ----

    @Test
    fun oneAppTrippingBothProducesTwoAlertsAndTwoObservations() {
        val a = app(label = "Sync", backgroundMobileBytes = 25L * 1024 * 1024, roamingBytes = 15L * 1024 * 1024)
        val result = map(listOf(DataUsageFinding.HeavyBackgroundMobile(a), DataUsageFinding.HeavyRoaming(a)))

        assertEquals(2, result.alerts.size)
        assertEquals(2, result.observations.size)
    }

    // ---- alert cap and rollup ----

    @Test
    fun alertsAtCapHaveNoRollup() {
        val findings = (0 until MAX_DATA_USE_ALERTS).map { i ->
            DataUsageFinding.HeavyRoaming(app(uid = 10000 + i, label = "App$i", roamingBytes = 15L * 1024 * 1024))
        }
        val result = map(findings)

        assertEquals(MAX_DATA_USE_ALERTS, result.alerts.size)
        assertFalse(result.alerts.any { it.title == "More apps used notable data" })
    }

    @Test
    fun alertsOverCapKeepCapPlusWarningRollup() {
        val findings = (0 until MAX_DATA_USE_ALERTS + 1).map { i ->
            DataUsageFinding.HeavyRoaming(app(uid = 10000 + i, label = "App$i", roamingBytes = 15L * 1024 * 1024))
        }
        val result = map(findings)

        // Cap kept plus one rollup.
        assertEquals(MAX_DATA_USE_ALERTS + 1, result.alerts.size)
        val rollup = result.alerts.last()
        assertEquals(AlertLevel.WARNING, rollup.level)
        assertEquals("More apps used notable data", rollup.title)
        assertEquals(DATA_USE_CATEGORY, rollup.categoryId)
        assertTrue(rollup.body.contains("1 more"))
    }

    // ---- observation cap and rollup ----

    @Test
    fun observationsAtCapHaveNoRollup() {
        val findings = (0 until MAX_DATA_USE_OBSERVATIONS).map { i ->
            DataUsageFinding.HeavyRoaming(app(uid = 10000 + i, label = "App$i", roamingBytes = 15L * 1024 * 1024))
        }
        val result = map(findings)

        assertEquals(MAX_DATA_USE_OBSERVATIONS, result.observations.size)
        assertFalse(result.observations.any { it.summary.startsWith("...and") })
    }

    @Test
    fun observationsOverCapKeepCapPlusRollup() {
        val findings = (0 until MAX_DATA_USE_OBSERVATIONS + 1).map { i ->
            DataUsageFinding.HeavyRoaming(app(uid = 10000 + i, label = "App$i", roamingBytes = 15L * 1024 * 1024))
        }
        val result = map(findings)

        // Cap kept plus one rollup.
        assertEquals(MAX_DATA_USE_OBSERVATIONS + 1, result.observations.size)
        val rollup = result.observations.last()
        assertEquals("...and 1 more data-use notes.", rollup.summary)
        assertEquals(DATA_USE_CATEGORY, rollup.categoryId)
        assertEquals(ObservationKind.CHANGE, rollup.kind)
    }
}
