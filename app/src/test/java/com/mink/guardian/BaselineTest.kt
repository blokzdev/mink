package com.mink.guardian

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class BaselineTest {

    private val utc = ZoneId.of("UTC")

    private fun sig(id: String, value: String, name: String = id): SignalSnap =
        SignalSnap(id = id, name = name, value = value)

    private fun snap(vararg signals: SignalSnap): GuardianSnapshot =
        GuardianSnapshot(epochMs = 0L, categories = signals.groupBy { categoryIdOf(it.id) })

    // ---- updated(): first sighting ----

    @Test
    fun firstSightingCreatesStats() {
        val b = GuardianBaseline.empty(0L).updated(snap(sig("cpu.model", "A")), 1_000L, utc)
        val s = b.signals.getValue("cpu.model")
        assertEquals(1, s.sweepsSeen)
        assertEquals(0, s.changeCount)
        assertEquals(listOf(hashValue("A")), s.knownValueHashes)
        assertEquals(hashValue("A"), s.currentValueHash)
        assertNull(s.lastChangeMs)
        assertEquals(1, b.sweepCount)
    }

    @Test
    fun unchangedValueIncrementsSweepsWithoutChange() {
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.model", "A")), 1_000L, utc)
        b = b.updated(snap(sig("cpu.model", "A")), 2_000L, utc)
        val s = b.signals.getValue("cpu.model")
        assertEquals(2, s.sweepsSeen)
        assertEquals(0, s.changeCount)
        assertNull(s.lastChangeMs)
        assertEquals(listOf(hashValue("A")), s.knownValueHashes)
    }

    @Test
    fun changedValueRecordsChange() {
        val hour13 = 13L * 60 * 60 * 1000 // 13:00 UTC on 1970-01-01
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.model", "A")), 1_000L, utc)
        b = b.updated(snap(sig("cpu.model", "B")), hour13, utc)
        val s = b.signals.getValue("cpu.model")
        assertEquals(2, s.sweepsSeen)
        assertEquals(1, s.changeCount)
        assertEquals(hour13, s.lastChangeMs)
        assertEquals(listOf(hour13), s.recentChangeEpochs)
        assertEquals(1, s.changeHourHistogram[13])
        // LRU order: most recent last.
        assertEquals(listOf(hashValue("A"), hashValue("B")), s.knownValueHashes)
        assertEquals(hashValue("B"), s.currentValueHash)
    }

    // ---- knownValueHashes LRU ----

    @Test
    fun knownValueHashesEvictBeyondCapAndMoveToEnd() {
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.k", "v0")), 1_000L, utc)
        for (i in 1..8) {
            b = b.updated(snap(sig("cpu.k", "v$i")), 1_000L + i, utc)
        }
        val s = b.signals.getValue("cpu.k")
        assertEquals(MAX_KNOWN_HASHES, s.knownValueHashes.size)
        assertEquals(hashValue("v8"), s.knownValueHashes.last())
        assertFalse(s.knownValueHashes.contains(hashValue("v0"))) // oldest evicted

        // Revisit an existing value: it moves to the end, de-duplicated, and the
        // oldest survivor (v1) is NOT evicted.
        b = b.updated(snap(sig("cpu.k", "v2")), 2_000L, utc)
        val s2 = b.signals.getValue("cpu.k")
        assertEquals(
            listOf("v1", "v3", "v4", "v5", "v6", "v7", "v8", "v2").map { hashValue(it) },
            s2.knownValueHashes,
        )
    }

    // ---- changesWithin window ----

    @Test
    fun changesWithinExcludesEpochsOlderThanWindow() {
        val now = 40L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val s = SignalStats(
            firstSeenMs = 0L,
            lastSeenMs = now,
            sweepsSeen = 30,
            changeCount = 5,
            currentValueHash = hashValue("x"),
            // Three flaps weeks ago, two inside the 7-day window.
            recentChangeEpochs = listOf(now - 22 * day, now - 21 * day, now - 20 * day, now - 3 * day, now - day),
            name = "Flappy",
        )
        assertEquals(2, s.changesWithin(FLAP_WINDOW_MS, now))
        // Entirely-stale history counts as zero recent changes.
        val stale = s.copy(recentChangeEpochs = listOf(now - 22 * day, now - 21 * day, now - 20 * day))
        assertEquals(0, stale.changesWithin(FLAP_WINDOW_MS, now))
    }

    @Test
    fun summaryDriftingExcludesSignalsWhoseFlapsAreOlderThanWindow() {
        val now = 40L * 24 * 60 * 60 * 1000
        val day = 24L * 60 * 60 * 1000
        val b = GuardianBaseline(
            createdMs = 0L,
            sweepCount = 10,
            lastSweepMs = now,
            signals = mapOf(
                "cpu.stale" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now,
                    sweepsSeen = 100,
                    changeCount = 3,
                    currentValueHash = hashValue("a"),
                    recentChangeEpochs = listOf(now - 22 * day, now - 21 * day, now - 20 * day),
                    name = "Stale",
                ),
                "cpu.fresh" to SignalStats(
                    firstSeenMs = 0L,
                    lastSeenMs = now,
                    sweepsSeen = 100,
                    changeCount = 2,
                    currentValueHash = hashValue("b"),
                    recentChangeEpochs = listOf(now - 2 * day, now - day),
                    name = "Fresh",
                ),
            ),
        )
        assertEquals(listOf("cpu.fresh"), b.summary(now).driftingSignals.map { it.signalId })
    }

    // ---- recentChangeEpochs ring ----

    @Test
    fun changeEpochRingCapsAndDropsOldest() {
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.k", "x0")), 100L, utc)
        for (i in 1..20) {
            b = b.updated(snap(sig("cpu.k", "x$i")), 1_000L + i, utc)
        }
        val s = b.signals.getValue("cpu.k")
        assertEquals(MAX_CHANGE_EPOCHS, s.recentChangeEpochs.size)
        assertEquals(1_020L, s.recentChangeEpochs.last())
        assertEquals(1_005L, s.recentChangeEpochs.first()) // 1001..1020 keep last 16
    }

    // ---- pruning + cap ----

    @Test
    fun prunesEntriesOlderThanPruneWindow() {
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.old", "A")), 1_000L, utc)
        val later = 1_000L + PRUNE_AFTER_MS + 1
        b = b.updated(snap(sig("gpu.new", "B")), later, utc)
        assertFalse(b.signals.containsKey("cpu.old"))
        assertTrue(b.signals.containsKey("gpu.new"))
    }

    @Test
    fun capsTrackedSignalsEvictingOldestLastSeen() {
        var b = GuardianBaseline.empty(0L)
        val first = (0 until 1_000).map { sig("cpu.a$it", "v") }
        b = b.updated(GuardianSnapshot(0L, first.groupBy { categoryIdOf(it.id) }), 1_000L, utc)
        val second = (0 until 300).map { sig("gpu.b$it", "v") }
        b = b.updated(GuardianSnapshot(0L, second.groupBy { categoryIdOf(it.id) }), 2_000L, utc)
        assertEquals(MAX_TRACKED_SIGNALS, b.signals.size)
        // Newest sweep's signals survive; older ties are the ones evicted.
        for (i in 0 until 300) assertTrue(b.signals.containsKey("gpu.b$i"))
    }

    @Test
    fun absentSignalIsNotTouched() {
        var b = GuardianBaseline.empty(0L)
        b = b.updated(snap(sig("cpu.k", "A")), 1_000L, utc)
        b = b.updated(snap(sig("gpu.k", "B")), 2_000L, utc)
        val s = b.signals.getValue("cpu.k")
        assertEquals(1_000L, s.lastSeenMs) // not bumped
        assertEquals(1, s.sweepsSeen) // not incremented
        assertEquals(0, s.changeCount) // not marked changed
    }

    // ---- hashValue ----

    @Test
    fun hashValueIsDeterministicSixteenCharLowercaseHex() {
        assertEquals(hashValue("abc"), hashValue("abc"))
        assertEquals(16, hashValue("abc").length)
        assertNotEquals(hashValue("abc"), hashValue("abd"))
        assertTrue(hashValue("abc").all { it in "0123456789abcdef" })
    }

    // ---- zone attribution ----

    @Test
    fun sweepHourHistogramRespectsZone() {
        val bUtc = GuardianBaseline.empty(0L).updated(snap(sig("cpu.k", "A")), 0L, ZoneId.of("UTC"))
        assertEquals(1, bUtc.sweepHourHistogram[0]) // 00:00 UTC
        val bNy = GuardianBaseline.empty(0L)
            .updated(snap(sig("cpu.k", "A")), 0L, ZoneId.of("America/New_York"))
        assertEquals(1, bNy.sweepHourHistogram[19]) // 19:00 the previous day, UTC-5
    }

    // ---- summary() ----

    @Test
    fun summaryCountsAnchorsVolatileAndDrifting() {
        val now = 40L * DAY
        val signals = mapOf(
            "cpu.stable" to SignalStats(
                firstSeenMs = now - 10 * DAY,
                lastSeenMs = now,
                sweepsSeen = STABLE_MIN_SWEEPS,
                changeCount = 0,
                currentValueHash = hashValue("s"),
                knownValueHashes = listOf(hashValue("s")),
                name = "Stable",
            ),
            "battery.level" to SignalStats(
                firstSeenMs = now - 10 * DAY,
                lastSeenMs = now,
                sweepsSeen = 10,
                changeCount = 6, // rate 0.6 -> volatile
                currentValueHash = hashValue("v"),
                recentChangeEpochs = listOf(now - DAY, now - 2 * DAY),
                name = "Battery",
            ),
            "cpu.d1" to SignalStats(
                firstSeenMs = now - 10 * DAY,
                lastSeenMs = now,
                sweepsSeen = 10,
                changeCount = 3,
                currentValueHash = hashValue("d"),
                recentChangeEpochs = listOf(now - DAY, now - 2 * DAY, now - 3 * DAY),
                name = "Drift One",
            ),
            "cpu.d2" to SignalStats(
                firstSeenMs = now - 10 * DAY,
                lastSeenMs = now,
                sweepsSeen = 10,
                changeCount = 1,
                currentValueHash = hashValue("e"),
                recentChangeEpochs = listOf(now - DAY),
                name = "Drift Two",
            ),
        )
        val b = GuardianBaseline(createdMs = now - 20 * DAY, sweepCount = 8, lastSweepMs = now, signals = signals)
        val sum = b.summary(now)
        assertEquals(4, sum.trackedSignals)
        assertEquals(1, sum.stableAnchors)
        assertEquals(1, sum.expectedVolatile)
        // Drifting excludes volatile + stable, sorted by recent changes desc.
        assertEquals(listOf("cpu.d1", "cpu.d2"), sum.driftingSignals.map { it.signalId })
        assertEquals(3, sum.driftingSignals[0].recentChanges)
        assertEquals("cpu", sum.driftingSignals[0].categoryId)
        assertTrue(sum.isMature)
    }

    @Test
    fun summaryDriftingCapsAtFive() {
        val now = 40L * DAY
        val signals = (0 until 8).associate { i ->
            "cpu.d$i" to SignalStats(
                firstSeenMs = now - 10 * DAY,
                lastSeenMs = now,
                // Enough sweeps that even 8 changes stays below VOLATILE_RATE.
                sweepsSeen = 100,
                changeCount = i + 1,
                currentValueHash = hashValue("v$i"),
                recentChangeEpochs = List(i + 1) { now - DAY },
                name = "Drift $i",
            )
        }
        val b = GuardianBaseline(createdMs = 0L, sweepCount = 10, lastSweepMs = now, signals = signals)
        val sum = b.summary(now)
        assertEquals(5, sum.driftingSignals.size)
        // Highest recentChanges first.
        assertEquals("cpu.d7", sum.driftingSignals.first().signalId)
    }

    @Test
    fun summaryIsImmatureBelowThreshold() {
        val b = GuardianBaseline(createdMs = 0L, sweepCount = MIN_SWEEPS_FOR_LEARNING - 1)
        assertFalse(b.summary(1_000L).isMature)
    }

    // ---- JSON round trip ----

    @Test
    fun jsonRoundTripPreservesBaseline() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        var b = GuardianBaseline.empty(1_000L)
        b = b.updated(snap(sig("cpu.model", "A"), sig("battery.level", "50")), 2_000L, utc)
        b = b.updated(snap(sig("cpu.model", "A"), sig("battery.level", "49")), 3_000L, utc)
        val encoded = json.encodeToString(GuardianBaseline.serializer(), b)
        val decoded = json.decodeFromString(GuardianBaseline.serializer(), encoded)
        assertEquals(b, decoded)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
