package com.mink

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.DnsFlowHub
import com.mink.monitor.DnsFlowMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hermetic instrumented checks for the DNS-flow monitor. Construction must be
 * inert (no VPN starts, no permission needed) and the hub must roll up and clear
 * correctly on a real device. The VPN itself is not started here — that needs
 * interactive consent and is exercised by hand on device.
 */
@RunWith(AndroidJUnit4::class)
class DnsFlowMonitorInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun constructionIsInertAndReportsInitialState() {
        DnsFlowHub.clear()                                          // reset the process-global before asserting
        val monitor = DnsFlowMonitor(context)
        // No VPN is running just because we constructed the handle.
        assertFalse(monitor.running.value)
        assertTrue(monitor.report.value.lookups.isEmpty())
    }

    @Test
    fun isSupportedMatchesPlatformVersion() {
        val monitor = DnsFlowMonitor(context)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q, monitor.isSupported)
    }

    @Test
    fun hubRecordsAndClearsOnDevice() {
        DnsFlowHub.clear()
        DnsFlowHub.record(1000, "android", "System", true, "connectivitycheck.gstatic.com", 1L)
        assertEquals(1, DnsFlowHub.report.value.lookups.size)
        DnsFlowHub.clear()
        assertTrue(DnsFlowHub.report.value.lookups.isEmpty())
    }

    @Test
    fun clearOnMonitorIsSafeWhenEmpty() {
        val monitor = DnsFlowMonitor(context)
        monitor.clear()
        assertTrue(monitor.report.value.lookups.isEmpty())
    }
}
