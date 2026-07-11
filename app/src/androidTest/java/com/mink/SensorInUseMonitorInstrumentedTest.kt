package com.mink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.SensorInUseMonitor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [SensorInUseMonitor]'s lifecycle against the real platform
 * services on a device/emulator. Hermetic: it opens no camera, records no
 * audio, and grants no permissions — it only registers and unregisters the
 * availability and recording callbacks, so it asserts lifecycle safety, not
 * sensor activity (nothing here ever makes a sensor go busy).
 */
@RunWith(AndroidJUnit4::class)
class SensorInUseMonitorInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun newMonitor(): SensorInUseMonitor =
        SensorInUseMonitor(context) { /* no sessions are expected in this test */ }

    @Test
    fun constructionAloneIsInert() {
        // Constructing without start() must touch nothing and crash nothing.
        newMonitor()
    }

    @Test
    fun startAndStopSurviveBeingCalledTwice() {
        val monitor = newMonitor()
        monitor.start()
        monitor.start()
        monitor.stop()
        monitor.stop()
    }

    @Test
    fun stopBeforeStartIsANoOp() {
        newMonitor().stop()
    }

    @Test
    fun monitorCanBeRestartedAfterStop() {
        // The guardian re-enables after a disable; the monitor must come back up.
        val monitor = newMonitor()
        repeat(2) {
            monitor.start()
            monitor.stop()
        }
    }

    @Test
    fun hasUsageAccessReturnsWithoutThrowingAndAgreesWithItself() {
        // Whether usage access is granted varies by emulator state, so the
        // value itself is not asserted — only that the check is exception-free
        // and stable across calls.
        val first = SensorInUseMonitor.hasUsageAccess(context)
        val second = SensorInUseMonitor.hasUsageAccess(context)
        assertEquals(first, second)
    }
}
