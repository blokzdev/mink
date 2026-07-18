package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepTimeTest {

    private fun time(wallMs: Long, elapsedMs: Long, tzOffsetSeconds: Int = 0): SweepTime =
        SweepTime(wallMs = wallMs, elapsedRealtimeMs = elapsedMs, tzOffsetSeconds = tzOffsetSeconds)

    // ---- first sweep ----

    @Test
    fun firstSweepIsTrustedWithNoFlags() {
        val a = assessSweep(previous = null, current = time(wallMs = 1_000L, elapsedMs = 5_000L))
        assertEquals(SweepTrust.TRUSTED, a.trust)
        assertFalse(a.rebooted)
        assertFalse(a.longGap)
        assertFalse(a.timezoneChanged)
    }

    // ---- normal cadence ----

    @Test
    fun normalCadenceIsTrustedWithNoFlags() {
        val halfHour = 30L * 60 * 1000
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L)
        val cur = time(wallMs = 1_000_000L + halfHour, elapsedMs = 2_000_000L + halfHour)
        val a = assessSweep(prev, cur)
        assertEquals(SweepTrust.TRUSTED, a.trust)
        assertFalse(a.rebooted)
        assertFalse(a.longGap)
        assertFalse(a.timezoneChanged)
    }

    // ---- wall clock jumps ----

    @Test
    fun wallClockJumpForwardIsSuspect() {
        val halfHour = 30L * 60 * 1000
        val twoHours = 2L * 60 * 60 * 1000
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L)
        // Monotonic advanced 30 min, but wall clock leapt 2h ahead.
        val cur = time(wallMs = 1_000_000L + twoHours, elapsedMs = 2_000_000L + halfHour)
        val a = assessSweep(prev, cur)
        assertEquals(SweepTrust.CLOCK_SUSPECT, a.trust)
        assertFalse(a.rebooted)
    }

    @Test
    fun wallClockJumpBackwardIsSuspect() {
        val halfHour = 30L * 60 * 1000
        val prev = time(wallMs = 10_000_000L, elapsedMs = 2_000_000L)
        // Monotonic advanced 30 min, but wall clock moved backwards.
        val cur = time(wallMs = 10_000_000L - halfHour, elapsedMs = 2_000_000L + halfHour)
        val a = assessSweep(prev, cur)
        assertEquals(SweepTrust.CLOCK_SUSPECT, a.trust)
        assertFalse(a.rebooted)
    }

    // ---- skew boundary (inclusive) ----

    @Test
    fun skewExactlyAtToleranceIsTrusted() {
        val halfHour = 30L * 60 * 1000
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L)
        // |wallDelta - monoDelta| == CLOCK_SKEW_TOLERANCE_MS, not strictly greater.
        val cur = time(
            wallMs = 1_000_000L + halfHour + CLOCK_SKEW_TOLERANCE_MS,
            elapsedMs = 2_000_000L + halfHour,
        )
        assertEquals(SweepTrust.TRUSTED, assessSweep(prev, cur).trust)
    }

    @Test
    fun skewOneMsBeyondToleranceIsSuspect() {
        val halfHour = 30L * 60 * 1000
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L)
        val cur = time(
            wallMs = 1_000_000L + halfHour + CLOCK_SKEW_TOLERANCE_MS + 1,
            elapsedMs = 2_000_000L + halfHour,
        )
        assertEquals(SweepTrust.CLOCK_SUSPECT, assessSweep(prev, cur).trust)
    }

    // ---- reboot ----

    @Test
    fun negativeMonoDeltaIsRebootedButStillTrusted() {
        // Monotonic clock reset to a small value after a reboot.
        val prev = time(wallMs = 10_000_000L, elapsedMs = 50_000_000L)
        val cur = time(wallMs = 10_060_000L, elapsedMs = 60_000L)
        val a = assessSweep(prev, cur)
        assertTrue(a.rebooted)
        assertEquals(SweepTrust.TRUSTED, a.trust)
        assertFalse(a.longGap) // wallDelta (60s) is well below LONG_GAP_MS
    }

    @Test
    fun rebootWithLargeWallGapFlagsLongGap() {
        val prev = time(wallMs = 10_000_000L, elapsedMs = 50_000_000L)
        val cur = time(wallMs = 10_000_000L + LONG_GAP_MS, elapsedMs = 60_000L)
        val a = assessSweep(prev, cur)
        assertTrue(a.rebooted)
        assertTrue(a.longGap)
        assertEquals(SweepTrust.TRUSTED, a.trust)
    }

    // ---- long gap ----

    @Test
    fun longMonotonicGapFlagsLongGapButStaysTrusted() {
        // Wall and monotonic both advance by LONG_GAP_MS in lockstep (Doze/idle).
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L)
        val cur = time(wallMs = 1_000_000L + LONG_GAP_MS, elapsedMs = 2_000_000L + LONG_GAP_MS)
        val a = assessSweep(prev, cur)
        assertTrue(a.longGap)
        assertEquals(SweepTrust.TRUSTED, a.trust)
        assertFalse(a.rebooted)
    }

    // ---- timezone ----

    @Test
    fun timezoneOffsetChangeFlagsTimezoneChangedWithoutSuspicion() {
        val halfHour = 30L * 60 * 1000
        val prev = time(wallMs = 1_000_000L, elapsedMs = 2_000_000L, tzOffsetSeconds = 0)
        val cur = time(
            wallMs = 1_000_000L + halfHour,
            elapsedMs = 2_000_000L + halfHour,
            tzOffsetSeconds = 3600,
        )
        val a = assessSweep(prev, cur)
        assertTrue(a.timezoneChanged)
        // A tz change alone (clocks otherwise consistent) does not make the sweep suspect.
        assertEquals(SweepTrust.TRUSTED, a.trust)
    }
}
