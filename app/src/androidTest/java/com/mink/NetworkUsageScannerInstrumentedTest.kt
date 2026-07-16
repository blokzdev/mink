package com.mink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.DataUsageReport
import com.mink.monitor.NetworkUsageScanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [NetworkUsageScanner] against the real [android.app.usage.NetworkStatsManager]
 * on a device/emulator. Hermetic: it grants no permissions and moves no data, so it
 * asserts only structural invariants that hold regardless of whether usage access is
 * granted or what traffic the machine happens to have recorded — the scan never throws
 * and returns a non-null report whose window echoes the bounds passed in, and the
 * usage-access check is exception-free and stable across calls.
 */
@RunWith(AndroidJUnit4::class)
class NetworkUsageScannerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun scan(startMs: Long, endMs: Long): DataUsageReport =
        runBlocking { NetworkUsageScanner(context).scan(startMs, endMs) }

    @Test
    fun scanOverThePastHourReturnsANonNullReportWithoutThrowing() {
        val now = System.currentTimeMillis()
        val start = now - 3_600_000L
        val report = scan(startMs = start, endMs = now)
        // Contents vary by emulator (usage access may be ungranted, traffic may be
        // zero), so only the report's existence and window bounds are asserted — the
        // same bounds are echoed on both the success and empty-on-failure paths.
        assertNotNull("scan should never return null", report)
        assertEquals(start, report.windowStartMs)
        assertEquals(now, report.windowEndMs)
    }

    @Test
    fun hasUsageAccessReturnsWithoutThrowingAndAgreesWithItself() {
        // Whether usage access is granted varies by emulator state, so the value
        // itself is not asserted — only that the check is exception-free and stable
        // across calls.
        val first = NetworkUsageScanner.hasUsageAccess(context)
        val second = NetworkUsageScanner.hasUsageAccess(context)
        assertEquals(first, second)
    }
}
