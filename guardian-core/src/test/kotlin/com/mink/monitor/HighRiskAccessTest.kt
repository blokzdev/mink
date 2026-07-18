package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the high-risk watcher's cross-sweep [diffHighRisk]. No
 * Android APIs are touched — snapshots are built by hand so the set-difference,
 * default-app, VPN, and ordering rules can be asserted exactly. Carry-forward on
 * read failure is a scanner concern and lives in the instrumented tests; here the
 * pure invariant is only that an unchanged snapshot diffs to nothing.
 */
class HighRiskAccessTest {

    private fun snapshot(
        accessibility: List<HighRiskComponent> = emptyList(),
        listeners: List<HighRiskComponent> = emptyList(),
        admins: List<HighRiskAdmin> = emptyList(),
        certs: List<HighRiskCert> = emptyList(),
        defaults: Map<String, HighRiskDefaultApp> = emptyMap(),
        vpn: Boolean = false,
    ): HighRiskSnapshot = HighRiskSnapshot(
        schemaVersion = HIGH_RISK_SCHEMA_VERSION,
        generatedAtMs = 0L,
        accessibilityServices = accessibility,
        notificationListeners = listeners,
        deviceAdmins = admins,
        userCertificates = certs,
        defaultApps = defaults,
        vpnActive = vpn,
    )

    private fun comp(id: String, label: String = id): HighRiskComponent = HighRiskComponent(id, label)

    private fun admin(
        packageName: String,
        label: String = packageName,
        isDeviceOwner: Boolean = false,
        isProfileOwner: Boolean = false,
    ): HighRiskAdmin = HighRiskAdmin(packageName, label, isDeviceOwner, isProfileOwner)

    private fun cert(id: String, label: String = id): HighRiskCert = HighRiskCert(id, label)

    private fun defaultApp(packageName: String, label: String = packageName): HighRiskDefaultApp =
        HighRiskDefaultApp(packageName, label)

    // ---- null / unchanged guards ----

    @Test
    fun diffWithNullPreviousIsEmpty() {
        val current = snapshot(accessibility = listOf(comp("com.a11y/Svc")))
        assertTrue(diffHighRisk(previous = null, current = current).isEmpty())
    }

    @Test
    fun diffOfIdenticalSnapshotIsEmpty() {
        val snap = snapshot(
            accessibility = listOf(comp("com.a11y/Svc")),
            listeners = listOf(comp("com.notif/Listener")),
            admins = listOf(admin("com.mdm")),
            certs = listOf(cert("user:1")),
            defaults = mapOf("sms" to defaultApp("com.messages")),
            vpn = true,
        )
        assertTrue(diffHighRisk(previous = snap, current = snap).isEmpty())
    }

    // ---- accessibility services ----

    @Test
    fun accessibilityAddedEmitsEnabledById() {
        val previous = snapshot()
        val current = snapshot(accessibility = listOf(comp("com.a11y/Svc", "Reader")))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.AccessibilityEnabled
        assertEquals("com.a11y/Svc", finding.component.id)
        assertEquals("Reader", finding.component.label)
    }

    @Test
    fun accessibilityRemovedEmitsDisabledById() {
        val previous = snapshot(accessibility = listOf(comp("com.a11y/Svc", "Reader")))
        val current = snapshot()
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.AccessibilityDisabled
        assertEquals("com.a11y/Svc", finding.component.id)
    }

    // ---- notification listeners ----

    @Test
    fun notificationListenerAddedEmitsEnabledById() {
        val previous = snapshot()
        val current = snapshot(listeners = listOf(comp("com.notif/Listener", "Peeker")))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.NotificationListenerEnabled
        assertEquals("com.notif/Listener", finding.component.id)
    }

    @Test
    fun notificationListenerRemovedEmitsDisabledById() {
        val previous = snapshot(listeners = listOf(comp("com.notif/Listener")))
        val current = snapshot()
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.NotificationListenerDisabled
        assertEquals("com.notif/Listener", finding.component.id)
    }

    // ---- device admins ----

    @Test
    fun deviceAdminAddedEmitsAddedByPackageWithOwnerFlag() {
        val previous = snapshot()
        val current = snapshot(admins = listOf(admin("com.mdm", "Work", isDeviceOwner = true)))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.DeviceAdminAdded
        assertEquals("com.mdm", finding.admin.packageName)
        assertTrue(finding.admin.isDeviceOwner)
    }

    @Test
    fun deviceAdminRemovedEmitsRemovedByPackage() {
        val previous = snapshot(admins = listOf(admin("com.mdm", "Work")))
        val current = snapshot()
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.DeviceAdminRemoved
        assertEquals("com.mdm", finding.admin.packageName)
    }

    // ---- user certificates ----

    @Test
    fun userCertificateAddedEmitsAddedById() {
        val previous = snapshot()
        val current = snapshot(certs = listOf(cert("user:1", "Corp Proxy")))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.UserCertificateAdded
        assertEquals("user:1", finding.cert.id)
        assertEquals("Corp Proxy", finding.cert.label)
    }

    @Test
    fun userCertificateRemovedEmitsRemovedById() {
        val previous = snapshot(certs = listOf(cert("user:1")))
        val current = snapshot()
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.UserCertificateRemoved
        assertEquals("user:1", finding.cert.id)
    }

    // ---- default apps ----

    @Test
    fun defaultAppChangedEmitsBothPackagesAndLabels() {
        val previous = snapshot(defaults = mapOf("sms" to defaultApp("com.a", "Alpha")))
        val current = snapshot(defaults = mapOf("sms" to defaultApp("com.b", "Beta")))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.DefaultAppChanged
        assertEquals("sms", finding.role)
        assertEquals("com.a", finding.fromPackage)
        assertEquals("com.b", finding.toPackage)
        assertEquals("Alpha", finding.fromLabel)
        assertEquals("Beta", finding.toLabel)
    }

    @Test
    fun defaultAppGainedEmitsNullFromPackage() {
        val previous = snapshot()
        val current = snapshot(defaults = mapOf("browser" to defaultApp("com.browser", "Browser")))
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.DefaultAppChanged
        assertEquals("browser", finding.role)
        assertNull(finding.fromPackage)
        assertNull(finding.fromLabel)
        assertEquals("com.browser", finding.toPackage)
        assertEquals("Browser", finding.toLabel)
    }

    @Test
    fun defaultAppLostEmitsNullToPackage() {
        val previous = snapshot(defaults = mapOf("dialer" to defaultApp("com.dialer", "Dialer")))
        val current = snapshot()
        val finding = diffHighRisk(previous, current).single() as HighRiskFinding.DefaultAppChanged
        assertEquals("dialer", finding.role)
        assertEquals("com.dialer", finding.fromPackage)
        assertEquals("Dialer", finding.fromLabel)
        assertNull(finding.toPackage)
        assertNull(finding.toLabel)
    }

    @Test
    fun defaultAppUnchangedEmitsNothing() {
        val snap = snapshot(defaults = mapOf("ime" to defaultApp("com.keyboard")))
        assertTrue(diffHighRisk(snap, snap).isEmpty())
    }

    @Test
    fun defaultAppLabelChangeWithSamePackageEmitsNothing() {
        // A label-only change on the same package is not a change, consistent
        // with the id-based diffs.
        val previous = snapshot(defaults = mapOf("ime" to defaultApp("com.keyboard", "Old Keyboard")))
        val current = snapshot(defaults = mapOf("ime" to defaultApp("com.keyboard", "New Keyboard")))
        assertTrue(diffHighRisk(previous, current).isEmpty())
    }

    // ---- VPN ----

    @Test
    fun vpnActivatedWhenFlagTurnsOn() {
        val previous = snapshot(vpn = false)
        val current = snapshot(vpn = true)
        assertTrue(diffHighRisk(previous, current).single() is HighRiskFinding.VpnActivated)
    }

    @Test
    fun vpnDeactivatedWhenFlagTurnsOff() {
        val previous = snapshot(vpn = true)
        val current = snapshot(vpn = false)
        assertTrue(diffHighRisk(previous, current).single() is HighRiskFinding.VpnDeactivated)
    }

    @Test
    fun vpnUnchangedEmitsNothing() {
        val on = snapshot(vpn = true)
        val off = snapshot(vpn = false)
        assertTrue(diffHighRisk(on, on).isEmpty())
        assertTrue(diffHighRisk(off, off).isEmpty())
    }

    // ---- deterministic ordering ----

    @Test
    fun findingsSortedByTypeRankThenIdAcrossMixedSet() {
        val previous = snapshot()
        val current = snapshot(
            // Two accessibility additions, out of id order, to prove the id tiebreak.
            accessibility = listOf(comp("com.z/Svc"), comp("com.a/Svc")),
            listeners = listOf(comp("com.n/Listener")),
            admins = listOf(admin("com.admin")),
            certs = listOf(cert("user:xyz")),
            defaults = mapOf("ime" to defaultApp("com.keyboard")),
            vpn = true,
        )

        val findings = diffHighRisk(previous, current)

        // Accessibility (0) < notif (1) < admin (2) < cert (3) < default (4) < vpn (5),
        // then id/role within a rank.
        assertEquals(
            listOf(
                "AccessibilityEnabled:com.a/Svc",
                "AccessibilityEnabled:com.z/Svc",
                "NotificationListenerEnabled:com.n/Listener",
                "DeviceAdminAdded:com.admin",
                "UserCertificateAdded:user:xyz",
                "DefaultAppChanged:ime",
                "VpnActivated",
            ),
            findings.map { it.describe() },
        )
    }

    // ---- label-only changes on the same id are not changes ----

    @Test
    fun accessibilityLabelChangeWithSameIdEmitsNothing() {
        val previous = snapshot(accessibility = listOf(comp("com.a11y/Svc", "Old")))
        val current = snapshot(accessibility = listOf(comp("com.a11y/Svc", "New")))
        assertTrue(diffHighRisk(previous, current).isEmpty())
    }

    @Test
    fun deviceAdminOwnershipFlipWithSamePackageEmitsNothing() {
        val previous = snapshot(admins = listOf(admin("com.mdm", isProfileOwner = false)))
        val current = snapshot(admins = listOf(admin("com.mdm", isProfileOwner = true)))
        assertTrue(diffHighRisk(previous, current).isEmpty())
    }

    @Test
    fun certLabelChangeWithSameIdEmitsNothing() {
        val previous = snapshot(certs = listOf(cert("user:1", "Old CA")))
        val current = snapshot(certs = listOf(cert("user:1", "New CA")))
        assertTrue(diffHighRisk(previous, current).isEmpty())
    }

    // ---- order independence ----

    @Test
    fun shuffledInputOrderProducesIdenticalDiff() {
        // Same state change, but every surface list is in a different order. The
        // diff associates by id and sorts its output, so both must agree exactly.
        val previous = snapshot(
            accessibility = listOf(comp("com.a/Svc"), comp("com.z/Svc")),
            certs = listOf(cert("user:1"), cert("user:2")),
        )
        val current = snapshot(
            accessibility = listOf(comp("com.z/Svc"), comp("com.new/Svc")),
            certs = listOf(cert("user:2"), cert("user:3")),
        )

        val previousShuffled = snapshot(
            accessibility = listOf(comp("com.z/Svc"), comp("com.a/Svc")),
            certs = listOf(cert("user:2"), cert("user:1")),
        )
        val currentShuffled = snapshot(
            accessibility = listOf(comp("com.new/Svc"), comp("com.z/Svc")),
            certs = listOf(cert("user:3"), cert("user:2")),
        )

        assertEquals(
            diffHighRisk(previous, current),
            diffHighRisk(previousShuffled, currentShuffled),
        )
    }

    // ---- commonNameFromDn ----

    @Test
    fun commonNameFromDnExtractsPlainCn() {
        assertEquals("Acme Root", commonNameFromDn("CN=Acme Root,O=Acme"))
    }

    @Test
    fun commonNameFromDnIgnoresNonCnAttributeInMultiValuedRdn() {
        assertEquals("Corp Proxy", commonNameFromDn("CN=Corp Proxy+OU=Sec,O=Corp"))
    }

    @Test
    fun commonNameFromDnPreservesEscapedComma() {
        assertEquals("Acme, Inc.", commonNameFromDn("CN=Acme\\, Inc.,O=Acme"))
    }

    @Test
    fun commonNameFromDnWithoutCnIsNull() {
        assertNull(commonNameFromDn("O=Acme"))
    }

    @Test
    fun commonNameFromDnOfEmptyIsNull() {
        assertNull(commonNameFromDn(""))
    }

    // ---- carrySurface ----

    @Test
    fun carrySurfaceCarriesPreviousValueOnThrow() {
        assertEquals("prev", carrySurface(previousValue = "prev", empty = "empty") { error("boom") })
    }

    @Test
    fun carrySurfaceUsesEmptyOnThrowWithNullPrevious() {
        assertEquals("empty", carrySurface(previousValue = null, empty = "empty") { error("boom") })
    }

    @Test
    fun carrySurfaceReplacesPreviousWithSuccessfulEmptyRead() {
        assertEquals("", carrySurface(previousValue = "prev", empty = "empty") { "" })
    }

    @Test
    fun carrySurfaceReturnsSuccessfulNonEmptyRead() {
        assertEquals("fresh", carrySurface(previousValue = "prev", empty = "empty") { "fresh" })
    }

    private fun HighRiskFinding.describe(): String = when (this) {
        is HighRiskFinding.AccessibilityEnabled -> "AccessibilityEnabled:${component.id}"
        is HighRiskFinding.AccessibilityDisabled -> "AccessibilityDisabled:${component.id}"
        is HighRiskFinding.NotificationListenerEnabled -> "NotificationListenerEnabled:${component.id}"
        is HighRiskFinding.NotificationListenerDisabled -> "NotificationListenerDisabled:${component.id}"
        is HighRiskFinding.DeviceAdminAdded -> "DeviceAdminAdded:${admin.packageName}"
        is HighRiskFinding.DeviceAdminRemoved -> "DeviceAdminRemoved:${admin.packageName}"
        is HighRiskFinding.UserCertificateAdded -> "UserCertificateAdded:${cert.id}"
        is HighRiskFinding.UserCertificateRemoved -> "UserCertificateRemoved:${cert.id}"
        is HighRiskFinding.DefaultAppChanged -> "DefaultAppChanged:$role"
        is HighRiskFinding.VpnActivated -> "VpnActivated"
        is HighRiskFinding.VpnDeactivated -> "VpnDeactivated"
    }
}
