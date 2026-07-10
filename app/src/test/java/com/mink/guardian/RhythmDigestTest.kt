package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmDigestTest {

    @Test
    fun immatureSummaryProducesEmptyDigest() {
        val summary = BaselineSummary(
            learningSinceMs = 0L,
            sweepCount = 3,
            trackedSignals = 10,
            stableAnchors = 0,
            expectedVolatile = 0,
            driftingSignals = emptyList(),
            isMature = false,
        )
        assertEquals("", rhythmDigest(summary, nowMs = 1_000L))
    }

    @Test
    fun matureDigestReportsCountsAndDays() {
        val summary = BaselineSummary(
            learningSinceMs = 0L,
            sweepCount = 10,
            trackedSignals = 50,
            stableAnchors = 5,
            expectedVolatile = 8,
            driftingSignals = listOf(DriftingSignal("cpu.d", "Cores", "cpu", 2)),
            isMature = true,
        )
        val digest = rhythmDigest(summary, nowMs = 10L * 24 * 60 * 60 * 1000)
        assertTrue(digest.contains("watching 50 signals for 10 days across 10 sweeps"))
        assertTrue(digest.contains("5 stable identity anchors, 8 naturally-changing readings"))
        assertTrue(digest.contains("Cores (2× this week)"))
    }

    @Test
    fun digestStaysUnderFiveHundredCharsWithLongNames() {
        val longName = "x".repeat(200)
        val drifting = (1..5).map { DriftingSignal("cpu.d$it", longName, "cpu", it) }
        val summary = BaselineSummary(
            learningSinceMs = 0L,
            sweepCount = 30,
            trackedSignals = 500,
            stableAnchors = 40,
            expectedVolatile = 12,
            driftingSignals = drifting,
            isMature = true,
        )
        val digest = rhythmDigest(summary, nowMs = 10L * 24 * 60 * 60 * 1000)
        assertTrue("digest length ${digest.length}", digest.length < 500)
        assertTrue(digest.contains("…")) // long names truncated with an ellipsis
    }
}
