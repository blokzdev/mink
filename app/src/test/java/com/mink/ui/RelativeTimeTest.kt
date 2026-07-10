package com.mink.ui

import com.mink.ui.screens.relativeTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [relativeTime], the Watched apps screen's compact
 * "how long ago" label. Time is injected (epoch vs now), so every branch and
 * boundary is asserted deterministically. Strings are singular/plural-agnostic
 * ("1m ago"), matching the existing rhythmDigest voice.
 */
class RelativeTimeTest {

    /** Fixed reference "now" so each case only varies the observation timestamp. */
    private val now = 1_700_000_000_000L

    // ---- under a minute → "just now" ----

    @Test
    fun underOneMinuteIsJustNow() {
        assertEquals("just now", relativeTime(now - 30_000L, now))
    }

    @Test
    fun justUnderOneMinuteIsJustNow() {
        assertEquals("just now", relativeTime(now - 59_999L, now))
    }

    @Test
    fun zeroDeltaIsJustNow() {
        assertEquals("just now", relativeTime(now, now))
    }

    @Test
    fun futureTimestampIsJustNow() {
        // A negative delta (timestamp ahead of now) stays calm rather than
        // producing a nonsensical negative age.
        assertEquals("just now", relativeTime(now + 10_000L, now))
    }

    // ---- minutes ----

    @Test
    fun exactlyOneMinuteBoundaryIsOneMinute() {
        assertEquals("1m ago", relativeTime(now - 60_000L, now))
    }

    @Test
    fun fiveMinutes() {
        assertEquals("5m ago", relativeTime(now - 5L * 60_000L, now))
    }

    @Test
    fun fiftyNineMinutesStillReadsInMinutes() {
        assertEquals("59m ago", relativeTime(now - 59L * 60_000L, now))
    }

    // ---- hours ----

    @Test
    fun exactlyOneHourBoundaryIsOneHour() {
        assertEquals("1h ago", relativeTime(now - 3_600_000L, now))
    }

    @Test
    fun threeHours() {
        assertEquals("3h ago", relativeTime(now - 3L * 3_600_000L, now))
    }

    @Test
    fun twentyThreeHoursStillReadsInHours() {
        assertEquals("23h ago", relativeTime(now - 23L * 3_600_000L, now))
    }

    // ---- days ----

    @Test
    fun exactlyOneDayBoundaryIsOneDay() {
        assertEquals("1d ago", relativeTime(now - 86_400_000L, now))
    }

    @Test
    fun twoDays() {
        assertEquals("2d ago", relativeTime(now - 2L * 86_400_000L, now))
    }
}
