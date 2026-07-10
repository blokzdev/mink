package com.mink.signals

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the passive provider formatting helpers. No Android
 * framework is touched, so these run on the plain JVM under testDebugUnitTest.
 */
class PassiveFormatTest {

    @Test
    fun formatDuration_minutesOnly() {
        assertEquals("0m", PassiveFormat.formatDuration(0L))
        assertEquals("5m", PassiveFormat.formatDuration(5 * 60_000L))
        assertEquals("59m", PassiveFormat.formatDuration(59 * 60_000L))
    }

    @Test
    fun formatDuration_hoursDropDays() {
        assertEquals("1h 0m", PassiveFormat.formatDuration(60 * 60_000L))
        assertEquals("2h 30m", PassiveFormat.formatDuration((2 * 60 + 30) * 60_000L))
    }

    @Test
    fun formatDuration_days() {
        val threeDaysFourHoursFiveMin = ((3 * 24 + 4) * 60L + 5) * 60_000L
        assertEquals("3d 4h 5m", PassiveFormat.formatDuration(threeDaysFourHoursFiveMin))
    }

    @Test
    fun formatDuration_negativeClampsToZero() {
        assertEquals("0m", PassiveFormat.formatDuration(-1_000L))
    }

    @Test
    fun batteryPercent_computesWholeNumber() {
        assertEquals("50%", PassiveFormat.batteryPercent(50, 100))
        assertEquals("100%", PassiveFormat.batteryPercent(100, 100))
        assertEquals("0%", PassiveFormat.batteryPercent(0, 100))
    }

    @Test
    fun batteryPercent_handlesNonHundredScale() {
        assertEquals("50%", PassiveFormat.batteryPercent(128, 256))
    }

    @Test
    fun batteryPercent_unknownWhenInvalid() {
        assertEquals("unknown", PassiveFormat.batteryPercent(-1, 100))
        assertEquals("unknown", PassiveFormat.batteryPercent(50, 0))
        assertEquals("unknown", PassiveFormat.batteryPercent(50, -5))
    }

    @Test
    fun hexDigest_formatsUpperCaseColonSeparated() {
        val bytes = byteArrayOf(0x00, 0x0F.toByte(), 0xA9.toByte(), 0xFF.toByte())
        assertEquals("00:0F:A9:FF", PassiveFormat.hexDigest(bytes))
    }
}
