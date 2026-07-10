package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class GuardianAnalyzerTest {

    private val utc = ZoneId.of("UTC")

    // Constant ids keep observations/alerts comparable across runs.
    private val analyzer = GuardianAnalyzer(idFactory = { "x" })

    private fun sig(id: String, value: String, name: String = id): SignalSnap =
        SignalSnap(id = id, name = name, value = value)

    private fun snapshot(vararg cats: Pair<String, List<SignalSnap>>): GuardianSnapshot =
        GuardianSnapshot(epochMs = 0L, categories = cats.toMap())

    private fun matureBaseline(signals: Map<String, SignalStats>): GuardianBaseline =
        GuardianBaseline(createdMs = 0L, sweepCount = 10, lastSweepMs = 0L, signals = signals)

    // ---- legacy path (baseline == null) ----

    @Test
    fun legacyNewExposureEmitsChangeObservationAndAlert() {
        val cur = snapshot("location" to listOf(sig("location.coords", "1,2")))
        val r = analyzer.analyze(null, cur, 1_000L)
        assertTrue(r.observations.any { it.kind == ObservationKind.CHANGE && it.categoryId == "location" })
        assertTrue(r.observations.any { it.kind == ObservationKind.SNAPSHOT && it.categoryId == "sweep" })
        val alert = r.alerts.first { it.categoryId == "location" }
        assertEquals(AlertLevel.WARNING, alert.level) // defaultLevelFor(LOCATION)
    }

    @Test
    fun legacyDriftOnNonPassiveWarns() {
        val prev = snapshot("location" to listOf(sig("location.coords", "1,2")))
        val cur = snapshot("location" to listOf(sig("location.coords", "3,4")))
        val r = analyzer.analyze(prev, cur, 2_000L)
        assertTrue(r.observations.any { it.kind == ObservationKind.ANOMALY && it.categoryId == "location" })
        assertEquals(AlertLevel.WARNING, r.alerts.first { it.categoryId == "location" }.level)
    }

    @Test
    fun legacyDriftOnPassiveIsObservationOnly() {
        val prev = snapshot("battery" to listOf(sig("battery.level", "50")))
        val cur = snapshot("battery" to listOf(sig("battery.level", "49")))
        val r = analyzer.analyze(prev, cur, 2_000L)
        assertTrue(r.observations.any { it.kind == ObservationKind.ANOMALY })
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun legacyNoPreviousMeansNoDriftObservation() {
        val cur = snapshot("battery" to listOf(sig("battery.level", "50")))
        val r = analyzer.analyze(null, cur, 1_000L)
        assertFalse(r.observations.any { it.kind == ObservationKind.ANOMALY })
        assertTrue(r.observations.any { it.kind == ObservationKind.SNAPSHOT })
    }

    @Test
    fun immatureBaselineBehavesExactlyLikeNull() {
        val immature = GuardianBaseline(createdMs = 0L, sweepCount = MIN_SWEEPS_FOR_LEARNING - 1)
        val prev = snapshot("battery" to listOf(sig("battery.level", "50")))
        val cur = snapshot("battery" to listOf(sig("battery.level", "49")))
        val rNull = analyzer.analyze(prev, cur, 2_000L, null, utc)
        val rImm = analyzer.analyze(prev, cur, 2_000L, immature, utc)
        assertEquals(
            rNull.observations.map { Triple(it.categoryId, it.kind, it.summary) },
            rImm.observations.map { Triple(it.categoryId, it.kind, it.summary) },
        )
        assertEquals(
            rNull.alerts.map { Triple(it.categoryId, it.level, it.summaryPair()) },
            rImm.alerts.map { Triple(it.categoryId, it.level, it.summaryPair()) },
        )
    }

    private fun GuardianAlert.summaryPair() = title to body

    // ---- learning-aware path ----

    @Test
    fun expectedVolatileDriftIsSuppressedAndCounted() {
        val baseline = matureBaseline(
            mapOf(
                "battery.level" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = 1_000L,
                    sweepsSeen = 10,
                    changeCount = 6, // rate 0.6 -> volatile
                    currentValueHash = hashValue("50"),
                    knownValueHashes = listOf(hashValue("50")),
                    recentChangeEpochs = listOf(1L, 2L, 3L),
                    name = "Battery level",
                ),
            ),
        )
        val prev = snapshot("battery" to listOf(sig("battery.level", "50", "Battery level")))
        val cur = snapshot("battery" to listOf(sig("battery.level", "49", "Battery level")))
        val r = analyzer.analyze(prev, cur, 2_000L, baseline, utc)
        assertFalse(r.observations.any { it.kind == ObservationKind.ANOMALY })
        assertFalse(r.observations.any { it.kind == ObservationKind.PATTERN })
        val summary = r.observations.first { it.kind == ObservationKind.SNAPSHOT }
        assertTrue(summary.summary.contains("1 naturally-changing readings tracked"))
    }

    @Test
    fun stableAnchorDriftWarnsEvenOnPassive() {
        val now = STABLE_MIN_AGE_MS + 1_000L
        val baseline = matureBaseline(
            mapOf(
                "battery.level" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now - 1_000L,
                    sweepsSeen = STABLE_MIN_SWEEPS,
                    changeCount = 0,
                    currentValueHash = hashValue("50"),
                    knownValueHashes = listOf(hashValue("50")),
                    name = "Battery level",
                ),
            ),
        )
        val prev = snapshot("battery" to listOf(sig("battery.level", "50", "Battery level")))
        val cur = snapshot("battery" to listOf(sig("battery.level", "49", "Battery level")))
        val r = analyzer.analyze(prev, cur, now, baseline, utc)
        val obs = r.observations.first { it.kind == ObservationKind.ANOMALY }
        // firstSeenMs = 0L in UTC formats as "Jan 1" (MMM d, Locale.US).
        assertTrue(obs.summary.contains("first time since Jan 1"))
        assertTrue(obs.summary.contains("${STABLE_MIN_SWEEPS} sweeps"))
        val alert = r.alerts.first { it.categoryId == "battery" }
        assertEquals(AlertLevel.WARNING, alert.level) // PASSIVE elevated
    }

    @Test
    fun revertToKnownValueIsObservationWithNoAlert() {
        val baseline = matureBaseline(
            mapOf(
                "location.coords" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = 1_000L,
                    sweepsSeen = 8,
                    changeCount = 2,
                    currentValueHash = hashValue("3,4"),
                    knownValueHashes = listOf(hashValue("1,2"), hashValue("3,4")),
                    name = "Coordinates",
                ),
            ),
        )
        val prev = snapshot("location" to listOf(sig("location.coords", "3,4", "Coordinates")))
        val cur = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val r = analyzer.analyze(prev, cur, 2_000L, baseline, utc)
        val obs = r.observations.first { it.kind == ObservationKind.ANOMALY }
        assertTrue(obs.summary.contains("returned to a value seen before"))
        // No alert even though LOCATION is non-PASSIVE.
        assertTrue(r.alerts.isEmpty())
    }

    @Test
    fun patternEmittedAtFlapThresholdOnlyOnChange() {
        val now = 10L * DAY
        val flapping = SignalStats(
            firstSeenMs = 0L,
            lastSeenMs = now,
            sweepsSeen = 8,
            changeCount = 2, // rate 0.25 -> not volatile
            currentValueHash = hashValue("1,2"),
            knownValueHashes = listOf(hashValue("1,2")),
            recentChangeEpochs = listOf(now - DAY, now - 2 * DAY),
            name = "Coordinates",
        )
        val baseline = matureBaseline(mapOf("location.coords" to flapping))

        // A change this sweep: 2 recent + this one = 3 == FLAP_MIN_CHANGES.
        val prev = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val cur = snapshot("location" to listOf(sig("location.coords", "9,9", "Coordinates")))
        val changed = analyzer.analyze(prev, cur, now, baseline, utc)
        val pattern = changed.observations.first { it.kind == ObservationKind.PATTERN }
        assertTrue(pattern.summary.contains("keeps changing"))
        assertTrue(pattern.summary.contains("3 times in the last 7 days"))

        // No change this sweep: PATTERN must not fire even though history flaps.
        val steady = analyzer.analyze(prev, prev, now, baseline, utc)
        assertFalse(steady.observations.any { it.kind == ObservationKind.PATTERN })
    }

    @Test
    fun patternDoesNotFireBelowThreshold() {
        val now = 10L * DAY
        val baseline = matureBaseline(
            mapOf(
                "location.coords" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now,
                    sweepsSeen = 8,
                    changeCount = 1,
                    currentValueHash = hashValue("1,2"),
                    knownValueHashes = listOf(hashValue("1,2")),
                    recentChangeEpochs = listOf(now - DAY), // only 1 + this = 2 < 3
                    name = "Coordinates",
                ),
            ),
        )
        val prev = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val cur = snapshot("location" to listOf(sig("location.coords", "9,9", "Coordinates")))
        val r = analyzer.analyze(prev, cur, now, baseline, utc)
        assertFalse(r.observations.any { it.kind == ObservationKind.PATTERN })
    }

    @Test
    fun patternIgnoresFlapsOlderThanWindow() {
        val now = 40L * DAY
        val baseline = matureBaseline(
            mapOf(
                "location.coords" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now,
                    sweepsSeen = 30,
                    changeCount = 3, // rate 0.1 -> not volatile
                    currentValueHash = hashValue("1,2"),
                    knownValueHashes = listOf(hashValue("1,2")),
                    // Three flaps, but all weeks outside FLAP_WINDOW_MS.
                    recentChangeEpochs = listOf(now - 22 * DAY, now - 21 * DAY, now - 20 * DAY),
                    name = "Coordinates",
                ),
            ),
        )
        val prev = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val cur = snapshot("location" to listOf(sig("location.coords", "9,9", "Coordinates")))
        // Stale flaps + this change = 1 recent < FLAP_MIN_CHANGES: no PATTERN.
        val r = analyzer.analyze(prev, cur, now, baseline, utc)
        assertFalse(r.observations.any { it.kind == ObservationKind.PATTERN })
    }

    @Test
    fun unusualHourSuffixAppearsAndVanishesByHistogram() {
        val histogramAt12 = List(24) { if (it == 12) UNUSUAL_HOUR_MIN_SAMPLES else 0 }
        fun statsAt() = SignalStats(
            firstSeenMs = 0L,
            lastSeenMs = 0L,
            sweepsSeen = 20,
            changeCount = 8, // rate 0.4 -> not volatile
            currentValueHash = hashValue("1,2"),
            knownValueHashes = listOf(hashValue("1,2")),
            changeHourHistogram = histogramAt12,
            name = "Coordinates",
        )
        val baseline = matureBaseline(mapOf("location.coords" to statsAt()))
        val prev = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val cur = snapshot("location" to listOf(sig("location.coords", "3,4", "Coordinates")))

        val hour3 = 3L * 60 * 60 * 1000 // 03:00 UTC, adjacent hours also empty
        val unusual = analyzer.analyze(prev, cur, hour3, baseline, utc)
        assertTrue(unusual.observations.first { it.kind == ObservationKind.ANOMALY }
            .summary.contains("unusual time for this device"))

        val hour12 = 12L * 60 * 60 * 1000 // 12:00 UTC, where changes usually happen
        val usual = analyzer.analyze(prev, cur, hour12, baseline, utc)
        assertFalse(usual.observations.first { it.kind == ObservationKind.ANOMALY }
            .summary.contains("unusual time for this device"))

        // Adjacent to a habitual hour: 13:00 has no counts itself, but hour 12
        // does, so the +/-1 adjacency rule suppresses the annotation.
        val hour13 = 13L * 60 * 60 * 1000
        val adjacent = analyzer.analyze(prev, cur, hour13, baseline, utc)
        assertFalse(adjacent.observations.first { it.kind == ObservationKind.ANOMALY }
            .summary.contains("unusual time for this device"))
    }

    @Test
    fun reExposedAfterAbsenceUsesReExposedWording() {
        val now = 30L * DAY
        val baseline = matureBaseline(
            mapOf(
                "location.coords" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now - 20 * DAY, // last seen 20 days ago (> 14)
                    sweepsSeen = 5,
                    changeCount = 1,
                    currentValueHash = hashValue("1,2"),
                    knownValueHashes = listOf(hashValue("1,2")),
                    name = "Coordinates",
                ),
            ),
        )
        val prev = snapshot() // location absent last sweep
        val cur = snapshot("location" to listOf(sig("location.coords", "1,2", "Coordinates")))
        val r = analyzer.analyze(prev, cur, now, baseline, utc)
        val obs = r.observations.first { it.categoryId == "location" && it.kind == ObservationKind.CHANGE }
        assertTrue(obs.summary.contains("re-exposed after"))
        assertTrue(obs.summary.contains("20 days"))
        assertEquals(AlertLevel.WARNING, r.alerts.first { it.categoryId == "location" }.level)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
