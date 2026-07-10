package com.mink.monitor

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [AppAccessScanner] against the real [android.content.pm.PackageManager]
 * on a device/emulator. Hermetic: it grants no permissions and touches no network,
 * so it asserts only structural invariants that hold regardless of what the OS has
 * actually granted the apps present on the machine.
 */
@RunWith(AndroidJUnit4::class)
class AppAccessScannerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun scan(nowMs: Long = System.currentTimeMillis()): AppAccessReport =
        runBlocking { AppAccessScanner(context).scan(nowMs) }

    @Test
    fun scanReturnsANonEmptyAppListOnADevice() {
        val report = scan()
        // At minimum the app under test plus a raft of system apps are visible.
        assertTrue("expected a non-empty app list", report.apps.isNotEmpty())
    }

    @Test
    fun reportIncludesThisAppsOwnPackage() {
        val report = scan()
        assertTrue(
            "scan should surface the app under test (${context.packageName})",
            report.apps.any { it.packageName == context.packageName },
        )
    }

    @Test
    fun grantedAndDeclaredNotGrantedAreDisjointForEveryApp() {
        val report = scan()
        for (app in report.apps) {
            val overlap = app.granted intersect app.declaredNotGranted
            assertTrue(
                "granted/declaredNotGranted overlap for ${app.packageName}: $overlap",
                overlap.isEmpty(),
            )
        }
    }

    @Test
    fun byCapabilityCountsMatchTheNumberOfGrantingApps() {
        val report = scan()
        for (holders in report.byCapability) {
            val expected = report.apps.count { holders.capability in it.granted }
            assertEquals(
                "holder count mismatch for ${holders.capability}",
                expected,
                holders.apps.size,
            )
            // A capability with zero holders must never appear in the index.
            assertTrue("empty capability leaked into the index", holders.apps.isNotEmpty())
        }
    }

    @Test
    fun generatedAtMsIsTheValuePassedIn() {
        val stamp = 1_234_567_890L
        assertEquals(stamp, scan(nowMs = stamp).generatedAtMs)
    }

    @Test
    fun scanningTwiceYieldsTheSameSetOfPackageNames() {
        val first = scan().apps.map { it.packageName }.toSet()
        val second = scan().apps.map { it.packageName }.toSet()
        assertEquals(first, second)
    }
}
