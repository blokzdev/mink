package com.mink.signals

import java.net.InetAddress
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the group B passive providers (audio, locale,
 * accessibility, clipboard, sensors, network). Only helpers that avoid the
 * Android framework are exercised, so these run on the plain JVM under
 * testDebugUnitTest.
 */
class PassiveGroupBFormatTest {

    @Test
    fun formatUtcOffset_positiveHalfHour() {
        val offset = (5 * 60 + 30) * 60_000 // +05:30
        assertEquals("UTC+05:30", LocaleProvider.formatUtcOffset(offset))
    }

    @Test
    fun formatUtcOffset_negative() {
        val offset = -(8 * 60) * 60_000 // -08:00
        assertEquals("UTC-08:00", LocaleProvider.formatUtcOffset(offset))
    }

    @Test
    fun formatUtcOffset_zero() {
        assertEquals("UTC+00:00", LocaleProvider.formatUtcOffset(0))
    }

    @Test
    fun dayName_knownDays() {
        assertEquals("Monday", LocaleProvider.dayName(Calendar.MONDAY))
        assertEquals("Sunday", LocaleProvider.dayName(Calendar.SUNDAY))
    }

    @Test
    fun formatKbps_scalesUnits() {
        assertEquals("unknown", NetworkProvider.formatKbps(0))
        assertEquals("unknown", NetworkProvider.formatKbps(-1))
        assertEquals("500 kbps", NetworkProvider.formatKbps(500))
        assertEquals("1.5 Mbps", NetworkProvider.formatKbps(1_500))
        assertEquals("2.0 Gbps", NetworkProvider.formatKbps(2_000_000))
    }

    @Test
    fun classifyAddress_loopbackIpv4() {
        val addr = InetAddress.getByName("127.0.0.1")
        assertEquals("IPv4 loopback", NetworkProvider.classifyAddress(addr))
    }

    @Test
    fun classifyAddress_privateIpv4() {
        val addr = InetAddress.getByName("192.168.1.10")
        assertEquals("IPv4 private", NetworkProvider.classifyAddress(addr))
    }

    @Test
    fun classifyAddress_globalIpv4() {
        val addr = InetAddress.getByName("8.8.8.8")
        assertEquals("IPv4 global", NetworkProvider.classifyAddress(addr))
    }

    @Test
    fun classifyAddress_linkLocalIpv6() {
        val addr = InetAddress.getByName("fe80::1")
        assertEquals("IPv6 link-local", NetworkProvider.classifyAddress(addr))
    }
}
