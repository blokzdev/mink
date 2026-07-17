package com.mink

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.DnsFlowHub
import com.mink.monitor.DnsFlowMonitor
import com.mink.monitor.DnsFlowStore
import com.mink.monitor.TrackerList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun constructionIsInertForTheVpn() {
        // Constructing the handle never starts the VPN (it may restore persisted
        // history into the hub, which is expected — that is not "running").
        val monitor = DnsFlowMonitor(context, scope)
        assertFalse(monitor.running.value)
    }

    @Test
    fun isSupportedMatchesPlatformVersion() {
        val monitor = DnsFlowMonitor(context, scope)
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
        val monitor = DnsFlowMonitor(context, scope)
        monitor.clear()
        assertTrue(monitor.report.value.lookups.isEmpty())
    }

    @Test
    fun enabledFlagRoundTrips() = runBlocking {
        val store = DnsFlowStore(context)
        store.saveEnabled(true)
        assertTrue(store.loadEnabled())
        store.saveEnabled(false)
        assertFalse(store.loadEnabled())
    }

    @Test
    fun bundledTrackerListLoadsAndMatches() {
        val list = TrackerList.load(context)
        assertTrue(list.size > 100)                                 // the real asset is sizeable
        assertTrue(list.isTracker("doubleclick.net"))
        assertTrue(list.isTracker("ads.g.doubleclick.net"))         // subdomain
        assertTrue(list.isTracker("adnxs.com"))                     // a PR-3 addition
        assertTrue(list.isTracker("clarity.ms"))                    // a PR-3 addition
        assertFalse(list.isTracker("example.com"))
        assertFalse(list.isTracker("unity3d.com"))                  // the over-broad entry we removed
    }
}
