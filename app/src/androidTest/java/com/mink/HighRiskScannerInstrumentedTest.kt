package com.mink

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.monitor.HIGH_RISK_SCHEMA_VERSION
import com.mink.monitor.HighRiskCert
import com.mink.monitor.HighRiskScanner
import com.mink.monitor.HighRiskSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [HighRiskScanner] against the real platform services on a
 * device/emulator. Hermetic: it grants no permissions, changes no security
 * setting, and touches no network, so it asserts only structural invariants
 * that hold regardless of how the OS is actually configured — the schema stamp,
 * the passed-in clock, self-consistency across two reads, and that a successful
 * empty read replaces a carried-forward value rather than preserving it.
 */
@RunWith(AndroidJUnit4::class)
class HighRiskScannerInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun scan(
        nowMs: Long = System.currentTimeMillis(),
        previous: HighRiskSnapshot? = null,
    ): HighRiskSnapshot = runBlocking { HighRiskScanner(context).scan(nowMs, previous) }

    @Test
    fun scanStampsTheCurrentSchemaVersionWithoutThrowing() {
        assertEquals(HIGH_RISK_SCHEMA_VERSION, scan().schemaVersion)
    }

    @Test
    fun generatedAtMsIsTheValuePassedIn() {
        val stamp = 1_234_567_890L
        assertEquals(stamp, scan(nowMs = stamp).generatedAtMs)
    }

    @Test
    fun scanningTwiceWithTheSameClockIsSelfConsistent() {
        // Two immediate reads of the same stable device state must agree on every
        // stable surface. The scanner sorts each surface, so equality is
        // order-independent. vpnActive is deliberately excluded: a device-wide VPN
        // can flap between the two reads on a CI emulator, which is not a scanner bug.
        val stamp = 1_000L
        val first = scan(nowMs = stamp)
        val second = scan(nowMs = stamp)
        assertEquals(first.accessibilityServices, second.accessibilityServices)
        assertEquals(first.notificationListeners, second.notificationListeners)
        assertEquals(first.deviceAdmins, second.deviceAdmins)
        assertEquals(first.userCertificates, second.userCertificates)
        assertEquals(first.defaultApps, second.defaultApps)
    }

    @Test
    fun aSuccessfulEmptyReadReplacesACarriedForwardValue() {
        // The fabricated previous snapshot carries a certificate the device does
        // not have. Because the AndroidCAStore read succeeds (and lacks this
        // alias), the fake cert is dropped, not carried forward — carry-forward
        // only happens when a read throws.
        val fabricated = HighRiskSnapshot(
            schemaVersion = HIGH_RISK_SCHEMA_VERSION,
            generatedAtMs = 1L,
            userCertificates = listOf(HighRiskCert(id = "user:mink-fabricated", label = "Fabricated CA")),
        )
        val snapshot = scan(previous = fabricated)
        assertEquals(HIGH_RISK_SCHEMA_VERSION, snapshot.schemaVersion)
        assertTrue(
            "a successful empty read must replace the carried-forward cert",
            snapshot.userCertificates.none { it.id == "user:mink-fabricated" },
        )
    }
}
