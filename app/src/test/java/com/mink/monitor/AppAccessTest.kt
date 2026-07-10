package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract tests for [PermCapability] and [AppAccessReport.from]. No
 * Android APIs are touched: records are built by hand so the deterministic
 * sorting and inversion rules can be asserted exactly.
 */
class AppAccessTest {

    private fun record(
        packageName: String,
        label: String = packageName,
        isSystem: Boolean = false,
        firstInstallMs: Long = 0L,
        lastUpdateMs: Long = 0L,
        granted: Set<PermCapability> = emptySet(),
        declaredNotGranted: Set<PermCapability> = emptySet(),
    ): AppRecord = AppRecord(
        packageName = packageName,
        label = label,
        isSystem = isSystem,
        firstInstallMs = firstInstallMs,
        lastUpdateMs = lastUpdateMs,
        granted = granted,
        declaredNotGranted = declaredNotGranted,
    )

    // ---- PermCapability.of ----

    @Test
    fun ofMapsKnownDangerousPermissionsToTheirCapability() {
        assertEquals(PermCapability.LOCATION, PermCapability.of("android.permission.ACCESS_FINE_LOCATION"))
        assertEquals(PermCapability.LOCATION, PermCapability.of("android.permission.ACCESS_BACKGROUND_LOCATION"))
        assertEquals(PermCapability.CAMERA, PermCapability.of("android.permission.CAMERA"))
        assertEquals(PermCapability.MICROPHONE, PermCapability.of("android.permission.RECORD_AUDIO"))
        assertEquals(PermCapability.CONTACTS, PermCapability.of("android.permission.READ_CONTACTS"))
        assertEquals(PermCapability.SMS, PermCapability.of("android.permission.RECEIVE_WAP_PUSH"))
        assertEquals(PermCapability.NEARBY_DEVICES, PermCapability.of("android.permission.BLUETOOTH_SCAN"))
        assertEquals(PermCapability.NOTIFICATIONS, PermCapability.of("android.permission.POST_NOTIFICATIONS"))
    }

    @Test
    fun ofReturnsNullForUncataloguedOrNormalPermission() {
        assertNull(PermCapability.of("android.permission.INTERNET"))
        assertNull(PermCapability.of("android.permission.ACCESS_NETWORK_STATE"))
        assertNull(PermCapability.of("not.a.real.permission"))
    }

    @Test
    fun everyPermissionStringBelongsToAtMostOneCapability() {
        val seen = mutableMapOf<String, PermCapability>()
        for (capability in PermCapability.entries) {
            for (permission in capability.permissions) {
                val prior = seen.put(permission, capability)
                assertNull(
                    "permission $permission is claimed by both $prior and $capability",
                    prior,
                )
            }
        }
    }

    // ---- AppAccessReport.from: apps ordering ----

    @Test
    fun appsAreSortedByLabelCaseInsensitiveThenPackageName() {
        // Case-sensitive ASCII would order "Zebra" (0x5A) before "apple" (0x61);
        // case-insensitive ordering puts "apple" first. That distinguishes the two.
        val records = listOf(
            record(packageName = "com.z", label = "Zebra"),
            record(packageName = "com.a", label = "apple"),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        assertEquals(listOf("apple", "Zebra"), report.apps.map { it.label })
    }

    @Test
    fun appsWithEqualLabelsAreBrokenByPackageName() {
        val records = listOf(
            record(packageName = "com.b.dup", label = "Same"),
            record(packageName = "com.a.dup", label = "Same"),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        assertEquals(listOf("com.a.dup", "com.b.dup"), report.apps.map { it.packageName })
    }

    // ---- AppAccessReport.from: byCapability inversion ----

    @Test
    fun byCapabilityExcludesZeroHolderCapabilities() {
        val records = listOf(
            record(packageName = "com.cam", granted = setOf(PermCapability.CAMERA)),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        assertEquals(listOf(PermCapability.CAMERA), report.byCapability.map { it.capability })
        assertTrue(report.byCapability.none { it.apps.isEmpty() })
    }

    @Test
    fun holdersWithinACapabilityAreNewestInstalledFirst() {
        val records = listOf(
            record(packageName = "com.old", label = "Old", firstInstallMs = 100L, granted = setOf(PermCapability.LOCATION)),
            record(packageName = "com.new", label = "New", firstInstallMs = 300L, granted = setOf(PermCapability.LOCATION)),
            record(packageName = "com.mid", label = "Mid", firstInstallMs = 200L, granted = setOf(PermCapability.LOCATION)),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        val location = report.byCapability.single { it.capability == PermCapability.LOCATION }
        assertEquals(listOf("com.new", "com.mid", "com.old"), location.apps.map { it.packageName })
    }

    @Test
    fun holdersWithEqualInstallTimeAreBrokenByLabel() {
        val records = listOf(
            record(packageName = "com.b", label = "Beta", firstInstallMs = 500L, granted = setOf(PermCapability.CAMERA)),
            record(packageName = "com.a", label = "Alpha", firstInstallMs = 500L, granted = setOf(PermCapability.CAMERA)),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        val camera = report.byCapability.single { it.capability == PermCapability.CAMERA }
        assertEquals(listOf("Alpha", "Beta"), camera.apps.map { it.label })
    }

    @Test
    fun byCapabilityIsOrderedBySensitivityNotHolderCount() {
        // The least sensitive capability (NOTIFICATIONS) is held by the most apps;
        // the most sensitive (LOCATION) by the fewest. Sensitivity must still win,
        // so the privacy view opens with what can locate you, not the longest list.
        val records = listOf(
            record(packageName = "com.a", label = "A", granted = setOf(PermCapability.NOTIFICATIONS)),
            record(packageName = "com.b", label = "B", granted = setOf(PermCapability.NOTIFICATIONS)),
            record(packageName = "com.c", label = "C", granted = setOf(PermCapability.NOTIFICATIONS, PermCapability.LOCATION)),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        assertEquals(
            listOf(PermCapability.LOCATION, PermCapability.NOTIFICATIONS),
            report.byCapability.map { it.capability },
        )
        // Order is by sensitivity even though NOTIFICATIONS has more holders.
        assertEquals(listOf(1, 3), report.byCapability.map { it.apps.size })
    }

    @Test
    fun holdersListUserAppsBeforeSystemAppsAndCountThemSeparately() {
        val records = listOf(
            record(packageName = "com.sys", label = "SystemCam", isSystem = true, firstInstallMs = 999L, granted = setOf(PermCapability.CAMERA)),
            record(packageName = "com.user", label = "UserCam", isSystem = false, firstInstallMs = 100L, granted = setOf(PermCapability.CAMERA)),
        )
        val report = AppAccessReport.from(records, nowMs = 1L)
        val camera = report.byCapability.single { it.capability == PermCapability.CAMERA }
        // User app first despite the system app being newer.
        assertEquals(listOf("com.user", "com.sys"), camera.apps.map { it.packageName })
        assertEquals(1, camera.userAppCount)
        assertEquals(1, camera.systemAppCount)
    }

    @Test
    fun emptyInputProducesEmptyReportAndPreservesNow() {
        val report = AppAccessReport.from(emptyList(), nowMs = 42L)
        assertTrue(report.apps.isEmpty())
        assertTrue(report.byCapability.isEmpty())
        assertEquals(42L, report.generatedAtMs)
    }

    @Test
    fun fromPreservesTheProvidedTimestamp() {
        val records = listOf(record(packageName = "com.cam", granted = setOf(PermCapability.CAMERA)))
        assertEquals(1234L, AppAccessReport.from(records, nowMs = 1234L).generatedAtMs)
    }

    // ---- granted vs declared-not-granted split ----

    @Test
    fun appLandsInBothSetsButOnlyGrantedCapabilityIndexesIt() {
        val app = record(
            packageName = "com.mixed",
            label = "Mixed",
            granted = setOf(PermCapability.CAMERA),
            declaredNotGranted = setOf(PermCapability.LOCATION),
        )
        val report = AppAccessReport.from(listOf(app), nowMs = 1L)

        // The record keeps the two capabilities in their respective sets.
        val stored = report.apps.single()
        assertTrue(PermCapability.CAMERA in stored.granted)
        assertTrue(PermCapability.LOCATION in stored.declaredNotGranted)

        // The inverted index lists the app under the granted capability only.
        val camera = report.byCapability.single { it.capability == PermCapability.CAMERA }
        assertEquals(listOf("com.mixed"), camera.apps.map { it.packageName })
        assertTrue(report.byCapability.none { it.capability == PermCapability.LOCATION })
    }
}
