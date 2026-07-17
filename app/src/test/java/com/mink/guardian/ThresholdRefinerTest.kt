package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the two-loop threshold refiner: the Wilson small-sample
 * bound, the alert-list projection, and the [refineThresholds] decision with its
 * disengagement guard, small-sample guard, escalation ramp, snap-down release,
 * hysteresis band, and in-function override reset. Everything is deterministic —
 * the decision takes an [EngagementSample] and a clock-free context.
 */
class ThresholdRefinerTest {

    private val cfg = RefinerConfig.DEFAULT

    private fun alert(
        level: AlertLevel = AlertLevel.WARNING,
        categoryId: String? = SENSOR_USE_CATEGORY,
        id: String = "a",
        createdAtEpochMs: Long = 0L,
        acknowledged: Boolean = false,
        fromImmutableRule: Boolean = false,
    ) = GuardianAlert(
        id = id,
        level = level,
        title = "t",
        body = "b",
        categoryId = categoryId,
        createdAtEpochMs = createdAtEpochMs,
        acknowledged = acknowledged,
        fromImmutableRule = fromImmutableRule,
    )

    private fun state(
        perSource: Map<AlertSource, SourceRefine> = emptyMap(),
        smoothedGlobal: Double = 0.0,
        globalSeeded: Boolean = false,
        lastAlertness: String = "STANDARD",
        lastMuted: Set<String> = emptySet(),
    ) = RefinerState(
        schemaVersion = REFINER_SCHEMA_VERSION,
        perSource = perSource.mapKeys { it.key.name },
        smoothedGlobalAckRate = smoothedGlobal,
        globalSeeded = globalSeeded,
        lastAlertnessName = lastAlertness,
        lastMutedSourceNames = lastMuted,
    )

    private fun sample(
        perSource: Map<AlertSource, SourceSample>,
        globalTotal: Int,
        globalAcked: Int,
    ) = EngagementSample(perSource, globalTotal, globalAcked)

    private val stdContext = RefinerContext(Alertness.STANDARD, emptySet())

    private fun levelOf(s: RefinerState, source: AlertSource) = s.perSource[source.name]?.level ?: 0

    // ---- wilson95Upper ----

    @Test
    fun wilsonZeroOfEightIsAroundOneThird() {
        val u = wilson95Upper(0, 8)
        assertTrue("expected ~0.32, was $u", u in 0.30..0.35)
    }

    @Test
    fun wilsonUpperShrinksAsSamplesGrow() {
        assertTrue(wilson95Upper(0, 25) < wilson95Upper(0, 8))
        assertTrue(wilson95Upper(0, 64) < wilson95Upper(0, 25))
    }

    @Test
    fun wilsonHandlesEmptyAndFullSamples() {
        assertEquals(1.0, wilson95Upper(0, 0), 0.0)   // no evidence -> optimistic
        assertTrue(wilson95Upper(8, 8) <= 1.0)         // never exceeds 1
        assertTrue(wilson95Upper(5, 10) <= 1.0)
    }

    // ---- engagementSampleOf ----

    @Test
    fun sampleExcludesImmutableCriticalBelowFloorFreshAndStale() {
        val now = 100L * 24 * 60 * 60 * 1000 // 100 days in
        val matured = now - 3L * 24 * 60 * 60 * 1000     // 3 days old: in window, matured
        val fresh = now - 1L * 60 * 60 * 1000            // 1 hour old: not matured
        val stale = now - 30L * 24 * 60 * 60 * 1000      // 30 days old: out of window
        val alerts = listOf(
            alert(level = AlertLevel.WARNING, createdAtEpochMs = matured, id = "keep", acknowledged = true),
            alert(level = AlertLevel.WARNING, createdAtEpochMs = matured, id = "immutable", fromImmutableRule = true),
            alert(level = AlertLevel.CRITICAL, createdAtEpochMs = matured, id = "critical"),
            alert(level = AlertLevel.INFO, createdAtEpochMs = matured, id = "info"),
            alert(level = AlertLevel.SUGGESTION, createdAtEpochMs = matured, id = "belowFloor"), // < WARNING at STANDARD
            alert(level = AlertLevel.WARNING, createdAtEpochMs = fresh, id = "fresh"),
            alert(level = AlertLevel.WARNING, createdAtEpochMs = stale, id = "stale"),
        )
        val s = engagementSampleOf(alerts, notifyFloor(Alertness.STANDARD), now, cfg)
        // Only the single matured, acknowledged WARNING survives.
        assertEquals(1, s.globalTotal)
        assertEquals(1, s.globalAcked)
        assertEquals(SourceSample(1, 1), s.perSource[AlertSource.SENSOR_USE])
    }

    @Test
    fun sampleFloorFollowsTheDial() {
        val now = 100L * 24 * 60 * 60 * 1000
        val matured = now - 3L * 24 * 60 * 60 * 1000
        val suggestion = listOf(alert(level = AlertLevel.SUGGESTION, createdAtEpochMs = matured))
        // PARANOID notifies SUGGESTION and up -> counted; STANDARD does not.
        assertEquals(1, engagementSampleOf(suggestion, notifyFloor(Alertness.PARANOID), now, cfg).globalTotal)
        assertEquals(0, engagementSampleOf(suggestion, notifyFloor(Alertness.STANDARD), now, cfg).globalTotal)
    }

    // ---- disengagement guard ----

    @Test
    fun globalDisengagementSnapsEveryLevelToZero() {
        // The user had been slipping (smoothed global already near the floor) and
        // this window confirms it: plenty of alerts, almost none acknowledged.
        val prior = state(
            perSource = mapOf(
                AlertSource.SENSOR_USE to SourceRefine(level = 2, smoothedAckRate = 0.0, seeded = true),
                AlertSource.DATA_USE to SourceRefine(level = 1, smoothedAckRate = 0.0, seeded = true),
            ),
            smoothedGlobal = 0.15, globalSeeded = true,
        )
        val s = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(20, 1)),
            globalTotal = 40, globalAcked = 2, // 5% -> smoothed to ~0.11, below the 0.20 floor
        )
        val next = refineThresholds(prior, s, stdContext, cfg)
        assertEquals(0, levelOf(next, AlertSource.SENSOR_USE))
        assertEquals(0, levelOf(next, AlertSource.DATA_USE))
    }

    @Test
    fun tooFewGlobalSamplesActsOnNothing() {
        val prior = state(
            perSource = mapOf(AlertSource.SENSOR_USE to SourceRefine(level = 2, seeded = true)),
        )
        val s = sample(mapOf(AlertSource.SENSOR_USE to SourceSample(8, 0)), globalTotal = 8, globalAcked = 4)
        val next = refineThresholds(prior, s, stdContext, cfg)
        assertEquals(0, levelOf(next, AlertSource.SENSOR_USE)) // snapped down, never raised
    }

    // ---- small-sample guard ----

    @Test
    fun belowMinSourceSamplesNeverRaises() {
        val prior = state(smoothedGlobal = 0.5, globalSeeded = true)
        val s = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(5, 0)), // ignored but only 5 samples
            globalTotal = 40, globalAcked = 20,
        )
        val next = refineThresholds(prior, s, stdContext, cfg)
        assertEquals(0, levelOf(next, AlertSource.SENSOR_USE))
    }

    @Test
    fun wilsonBlocksZeroOfEightAtModestGlobalThenAllowsAtSixteen() {
        // Global rate ~0.30: wilson95Upper(0,8)=~0.32 > 0.30 blocks; (0,16)=~0.19 < 0.30 allows.
        val prior = state(smoothedGlobal = 0.30, globalSeeded = true)
        val modestGlobal = { total: Int -> sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(total, 0)),
            globalTotal = 40, globalAcked = 12, // 0.30
        ) }
        val blocked = refineThresholds(prior, modestGlobal(8), stdContext, cfg)
        assertEquals(0, levelOf(blocked, AlertSource.SENSOR_USE))
        val allowed = refineThresholds(prior, modestGlobal(16), stdContext, cfg)
        assertEquals(1, levelOf(allowed, AlertSource.SENSOR_USE))
    }

    // ---- differential firing ----

    @Test
    fun sourceFarBelowGlobalRaises_atOwnRateDoesNot() {
        val prior = state(smoothedGlobal = 0.5, globalSeeded = true)
        val ignored = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(8, 0)),
            globalTotal = 40, globalAcked = 20, // 0.5
        )
        assertEquals(1, levelOf(refineThresholds(prior, ignored, stdContext, cfg), AlertSource.SENSOR_USE))

        val engaged = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(8, 4)), // 0.5, matches global
            globalTotal = 40, globalAcked = 20,
        )
        assertEquals(0, levelOf(refineThresholds(prior, engaged, stdContext, cfg), AlertSource.SENSOR_USE))
    }

    // ---- escalation ramp + cap ----

    @Test
    fun escalatesOneLevelPerTickToTheCap() {
        val prior = state(smoothedGlobal = 0.5, globalSeeded = true)
        val ignored = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(8, 0)),
            globalTotal = 40, globalAcked = 20,
        )
        val t1 = refineThresholds(prior, ignored, stdContext, cfg)
        assertEquals(1, levelOf(t1, AlertSource.SENSOR_USE))
        val t2 = refineThresholds(t1, ignored, stdContext, cfg)
        assertEquals(2, levelOf(t2, AlertSource.SENSOR_USE))
        val t3 = refineThresholds(t2, ignored, stdContext, cfg)
        assertEquals(2, levelOf(t3, AlertSource.SENSOR_USE)) // capped at levelMax
    }

    // ---- snap-down release ----

    @Test
    fun reEngagementReleasesToZeroInOneTick() {
        val prior = state(
            perSource = mapOf(AlertSource.SENSOR_USE to SourceRefine(level = 2, smoothedAckRate = 0.0, seeded = true)),
            smoothedGlobal = 0.5, globalSeeded = true,
        )
        val recovered = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(8, 8)), // now fully engaged
            globalTotal = 40, globalAcked = 20,
        )
        assertEquals(0, levelOf(refineThresholds(prior, recovered, stdContext, cfg), AlertSource.SENSOR_USE))
    }

    @Test
    fun evidenceGoneReleasesAbsentSourceToZero() {
        val prior = state(
            perSource = mapOf(AlertSource.SENSOR_USE to SourceRefine(level = 2, smoothedAckRate = 0.0, seeded = true)),
            smoothedGlobal = 0.5, globalSeeded = true,
        )
        // SENSOR_USE has no matured evidence this window; another source keeps global alive.
        val s = sample(
            mapOf(AlertSource.ACCESS_CHANGES to SourceSample(20, 10)),
            globalTotal = 20, globalAcked = 10,
        )
        assertEquals(0, levelOf(refineThresholds(prior, s, stdContext, cfg), AlertSource.SENSOR_USE))
    }

    // ---- hysteresis ----

    @Test
    fun sourceInsideTheBandHoldsItsLevel() {
        // Global 0.5: tighten<=0.25, recover>=0.40. A smoothed rate of ~0.36 is in the band.
        val prior = state(
            perSource = mapOf(AlertSource.SENSOR_USE to SourceRefine(level = 1, smoothedAckRate = 0.35, seeded = true)),
            smoothedGlobal = 0.5, globalSeeded = true,
        )
        val band = sample(
            mapOf(AlertSource.SENSOR_USE to SourceSample(8, 3)), // 0.375, smooths to ~0.36
            globalTotal = 40, globalAcked = 20,
        )
        assertEquals(1, levelOf(refineThresholds(prior, band, stdContext, cfg), AlertSource.SENSOR_USE))
    }

    // ---- override reset ----

    @Test
    fun dialChangeResetsAllLevels() {
        val prior = state(
            perSource = mapOf(
                AlertSource.SENSOR_USE to SourceRefine(level = 2, seeded = true),
                AlertSource.DATA_USE to SourceRefine(level = 1, seeded = true),
            ),
            smoothedGlobal = 0.5, globalSeeded = true, lastAlertness = "STANDARD",
        )
        val paranoidCtx = RefinerContext(Alertness.PARANOID, emptySet())
        val s = sample(mapOf(AlertSource.ACCESS_CHANGES to SourceSample(20, 10)), 20, 10)
        val next = refineThresholds(prior, s, paranoidCtx, cfg)
        assertEquals(0, levelOf(next, AlertSource.SENSOR_USE))
        assertEquals(0, levelOf(next, AlertSource.DATA_USE))
        assertEquals("PARANOID", next.lastAlertnessName)
    }

    @Test
    fun muteToggleResetsOnlyThatSource() {
        val prior = state(
            perSource = mapOf(
                AlertSource.SENSOR_USE to SourceRefine(level = 2, smoothedAckRate = 0.35, seeded = true),
                AlertSource.ACCESS_CHANGES to SourceRefine(level = 1, smoothedAckRate = 0.35, seeded = true),
            ),
            smoothedGlobal = 0.5, globalSeeded = true, lastMuted = emptySet(),
        )
        val muteSensor = RefinerContext(Alertness.STANDARD, setOf(AlertSource.SENSOR_USE))
        // Both sources in the keep band, so only the mute reset can move a level.
        val s = sample(
            mapOf(
                AlertSource.SENSOR_USE to SourceSample(8, 3),
                AlertSource.ACCESS_CHANGES to SourceSample(8, 3),
            ),
            globalTotal = 40, globalAcked = 20,
        )
        val next = refineThresholds(prior, s, muteSensor, cfg)
        assertEquals(0, levelOf(next, AlertSource.SENSOR_USE))       // reset by the mute toggle
        assertEquals(1, levelOf(next, AlertSource.ACCESS_CHANGES))   // untouched
        assertEquals(setOf("SENSOR_USE"), next.lastMutedSourceNames)
    }

    // ---- cooldownMultiplier ----

    @Test
    fun cooldownMultiplierMapsLevelPlusOneCoerced() {
        val s = state(
            perSource = mapOf(
                AlertSource.SENSOR_USE to SourceRefine(level = 0),
                AlertSource.DATA_USE to SourceRefine(level = 2),
                AlertSource.DNS_FLOW to SourceRefine(level = 99), // out of range -> coerced
            ),
        )
        assertEquals(1, s.cooldownMultiplier(AlertSource.SENSOR_USE))
        assertEquals(3, s.cooldownMultiplier(AlertSource.DATA_USE))
        assertEquals(3, s.cooldownMultiplier(AlertSource.DNS_FLOW))        // coerced to levelMax+1
        assertEquals(1, s.cooldownMultiplier(AlertSource.SECURITY_CHANGES)) // unknown -> 1
    }
}
