package com.mink.guardian

import com.mink.monitor.HighRiskAdmin
import com.mink.monitor.HighRiskCert
import com.mink.monitor.HighRiskComponent
import com.mink.monitor.HighRiskFinding
import com.mink.monitor.defaultRoleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [highRiskFindingsToGuardian]: the all-WARNING severity
 * policy, the device-owner vs plain-admin titling, the observation-only cases
 * (a VPN turning on, every disable/remove, a default role losing its app), and
 * the observation/alert caps. Findings are built by hand and ids/time injected,
 * so every branch is asserted deterministically. Iteration F introduces no
 * immutable rule, so no alert may carry [GuardianAlert.fromImmutableRule].
 */
class HighRiskGuardTest {

    /** Deterministic id source so ids can be traced back to the factory. */
    private fun sequentialIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun map(
        findings: List<HighRiskFinding>,
        nowMs: Long = 7_000L,
    ): HighRiskGuardResult = highRiskFindingsToGuardian(findings, nowMs, sequentialIds())

    private fun component(id: String, label: String = id): HighRiskComponent = HighRiskComponent(id, label)

    private fun admin(
        packageName: String,
        label: String = packageName,
        isDeviceOwner: Boolean = false,
        isProfileOwner: Boolean = false,
    ): HighRiskAdmin = HighRiskAdmin(packageName, label, isDeviceOwner, isProfileOwner)

    private fun cert(id: String, label: String = id): HighRiskCert = HighRiskCert(id, label)

    // ---- *Enabled / *Added raise a WARNING with the right title ----

    @Test
    fun accessibilityEnabledRaisesWarning() {
        val result = map(listOf(HighRiskFinding.AccessibilityEnabled(component("com.a11y/Svc", "Reader"))))

        assertEquals(1, result.observations.size)
        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("New accessibility service: Reader", alert.title)
        assertEquals(HIGH_RISK_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    @Test
    fun notificationListenerEnabledRaisesWarning() {
        val result = map(listOf(HighRiskFinding.NotificationListenerEnabled(component("com.notif/L", "Peeker"))))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Peeker can now read your notifications", alert.title)
        assertEquals(HIGH_RISK_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    @Test
    fun deviceOwnerAddedUsesDeviceOwnerTitle() {
        val result = map(listOf(HighRiskFinding.DeviceAdminAdded(admin("com.mdm", "Work", isDeviceOwner = true))))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("A device owner was added: Work", alert.title)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    @Test
    fun plainDeviceAdminAddedUsesPlainTitle() {
        val result = map(listOf(HighRiskFinding.DeviceAdminAdded(admin("com.sketch", "Sketchy"))))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Sketchy became a device admin", alert.title)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    @Test
    fun userCertificateAddedRaisesWarning() {
        val result = map(listOf(HighRiskFinding.UserCertificateAdded(cert("user:1", "Corp Proxy"))))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("New certificate authority: Corp Proxy", alert.title)
        assertEquals(HIGH_RISK_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    @Test
    fun defaultAppChangedWithNewPackageRaisesWarning() {
        val result = map(listOf(HighRiskFinding.DefaultAppChanged("ime", "com.a", "com.b", "Alpha", "Beta")))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Your ${defaultRoleLabel("ime")} changed", alert.title)
        assertEquals(HIGH_RISK_CATEGORY, alert.categoryId)
        assertFalse(alert.fromImmutableRule)
        assertNoBang(alert)
    }

    // ---- observation-only cases ----

    @Test
    fun defaultAppLostIsObservationOnly() {
        // A role losing its default (toPackage == null) is an observation, not an alert.
        val result = map(listOf(HighRiskFinding.DefaultAppChanged("sms", "com.a", null, "Alpha", null)))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun vpnActivatedIsObservationOnly() {
        val result = map(listOf(HighRiskFinding.VpnActivated()))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun disablesAndRemovesAreObservationOnly() {
        val findings = listOf(
            HighRiskFinding.AccessibilityDisabled(component("com.a11y/Svc")),
            HighRiskFinding.NotificationListenerDisabled(component("com.notif/L")),
            HighRiskFinding.DeviceAdminRemoved(admin("com.mdm")),
            HighRiskFinding.UserCertificateRemoved(cert("user:1")),
            HighRiskFinding.VpnDeactivated(),
        )
        val result = map(findings)

        assertEquals(findings.size, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    // ---- ids, time, category ----

    @Test
    fun observationsAndAlertsCarryInjectedIdTimeAndCategory() {
        val now = 42_000L
        val result = map(
            listOf(HighRiskFinding.AccessibilityEnabled(component("com.a11y/Svc", "Reader"))),
            nowMs = now,
        )

        val observation = result.observations.single()
        assertEquals(now, observation.epochMs)
        assertEquals(ObservationKind.CHANGE, observation.kind)
        assertEquals(HIGH_RISK_CATEGORY, observation.categoryId)
        assertTrue(observation.id.startsWith("gen-"))

        val alert = result.alerts.single()
        assertEquals(now, alert.createdAtEpochMs)
        assertTrue(alert.id.startsWith("gen-"))
        // Ids are unique across the observation and its alert.
        assertFalse(observation.id == alert.id)
    }

    // ---- no immutable rule in F ----

    @Test
    fun noFindingSetsFromImmutableRule() {
        val findings = listOf(
            HighRiskFinding.AccessibilityEnabled(component("com.a11y/Svc")),
            HighRiskFinding.NotificationListenerEnabled(component("com.notif/L")),
            HighRiskFinding.DeviceAdminAdded(admin("com.mdm", isDeviceOwner = true)),
            HighRiskFinding.DeviceAdminAdded(admin("com.sketch")),
            HighRiskFinding.UserCertificateAdded(cert("user:1")),
            HighRiskFinding.DefaultAppChanged("ime", "com.a", "com.b", "Alpha", "Beta"),
        )
        val result = map(findings)

        assertTrue(result.alerts.isNotEmpty())
        assertTrue(result.alerts.none { it.fromImmutableRule })
    }

    // ---- caps and rollups ----

    @Test
    fun observationsCapAtTwelveWithRollup() {
        // Fifteen observation-only findings (cert removals) -> 12 kept + 1 rollup, no alerts.
        val findings = (0 until 15).map { i ->
            HighRiskFinding.UserCertificateRemoved(cert("user:$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_OBSERVATIONS + 1, result.observations.size)
        assertEquals("...and 3 more security changes.", result.observations.last().summary)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun alertsCapAtSixWithWarningRollup() {
        // Eight WARNING-raising findings -> 6 kept + 1 rollup.
        val findings = (0 until 8).map { i ->
            HighRiskFinding.AccessibilityEnabled(component("com.a11y$i/Svc", "Svc$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_ALERTS + 1, result.alerts.size)
        val rollup = result.alerts.last()
        assertEquals(AlertLevel.WARNING, rollup.level)
        assertEquals("More security settings changed", rollup.title)
        assertTrue(rollup.body.contains("2 more"))
        // Eight observations is under the observation cap, so no observation rollup.
        assertEquals(8, result.observations.size)
    }

    @Test
    fun observationsExactlyAtCapHaveNoRollup() {
        val findings = (0 until MAX_HIGH_RISK_OBSERVATIONS).map { i ->
            HighRiskFinding.UserCertificateRemoved(cert("user:$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_OBSERVATIONS, result.observations.size)
        assertFalse(result.observations.last().summary.startsWith("...and"))
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun observationsOneOverCapAddASingleMoreRollup() {
        val findings = (0..MAX_HIGH_RISK_OBSERVATIONS).map { i ->
            HighRiskFinding.UserCertificateRemoved(cert("user:$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_OBSERVATIONS + 1, result.observations.size)
        assertEquals("...and 1 more security changes.", result.observations.last().summary)
    }

    @Test
    fun alertsExactlyAtCapHaveNoRollup() {
        val findings = (0 until MAX_HIGH_RISK_ALERTS).map { i ->
            HighRiskFinding.AccessibilityEnabled(component("com.a11y$i/Svc", "Svc$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_ALERTS, result.alerts.size)
        assertTrue(result.alerts.none { it.title == "More security settings changed" })
    }

    @Test
    fun alertsOneOverCapAddARollupWithOneMore() {
        val findings = (0..MAX_HIGH_RISK_ALERTS).map { i ->
            HighRiskFinding.AccessibilityEnabled(component("com.a11y$i/Svc", "Svc$i"))
        }
        val result = map(findings)

        assertEquals(MAX_HIGH_RISK_ALERTS + 1, result.alerts.size)
        val rollup = result.alerts.last()
        assertEquals("More security settings changed", rollup.title)
        assertTrue(rollup.body.contains("1 more"))
    }

    // ---- VPN honesty rail ----

    @Test
    fun vpnActivatedObservationIsHonestAndPackageFree() {
        val result = map(listOf(HighRiskFinding.VpnActivated()))

        val summary = result.observations.single().summary
        assertTrue(summary.contains("cannot tell which app"))
        // No package-like token (a letter-dot-letter run such as com.foo).
        assertFalse(summary.contains(Regex("[A-Za-z]\\.[A-Za-z]")))
        assertTrue(result.alerts.isEmpty())
    }

    // ---- device-owner vs plain-admin observation phrasing ----

    @Test
    fun deviceOwnerObservationSaysDeviceOwner() {
        val result = map(listOf(HighRiskFinding.DeviceAdminAdded(admin("com.mdm", "Work", isDeviceOwner = true))))

        assertTrue(result.observations.single().summary.contains("device owner"))
    }

    @Test
    fun plainDeviceAdminObservationSaysDeviceAdminNotOwner() {
        val result = map(listOf(HighRiskFinding.DeviceAdminAdded(admin("com.sketch", "Sketchy"))))

        val summary = result.observations.single().summary
        assertTrue(summary.contains("device admin"))
        assertFalse(summary.contains("device owner"))
    }

    private fun assertNoBang(alert: GuardianAlert) {
        assertFalse(alert.title.contains("!"))
        assertFalse(alert.body.contains("!"))
    }
}
