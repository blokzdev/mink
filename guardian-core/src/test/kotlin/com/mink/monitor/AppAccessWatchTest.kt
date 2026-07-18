package com.mink.monitor

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the app-access watcher: [AppAccessReport.toSnapshot], the
 * cross-sweep [diffAppAccess], and JSON round-tripping of [AppAccessSnapshot]. No
 * Android APIs are touched — records and snapshots are built by hand so the diff
 * and ordering rules can be asserted exactly.
 */
class AppAccessWatchTest {

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

    private fun grant(
        packageName: String,
        label: String = packageName,
        isSystem: Boolean = false,
        granted: Set<PermCapability> = emptySet(),
    ): AppGrant = AppGrant(packageName, label, isSystem, granted)

    private fun snapshot(vararg apps: AppGrant): AppAccessSnapshot =
        AppAccessSnapshot(
            schemaVersion = APP_ACCESS_SCHEMA_VERSION,
            generatedAtMs = 0L,
            apps = apps.toList(),
        )

    // ---- toSnapshot ----

    @Test
    fun toSnapshotStampsVersionAndTimeSortsByPackageAndKeepsGrantedOnly() {
        val report = AppAccessReport(
            apps = listOf(
                record(
                    packageName = "com.b",
                    label = "Bravo",
                    granted = setOf(PermCapability.CAMERA),
                    declaredNotGranted = setOf(PermCapability.LOCATION),
                ),
                record(
                    packageName = "com.a",
                    label = "Alpha",
                    isSystem = true,
                    granted = setOf(PermCapability.LOCATION, PermCapability.MICROPHONE),
                ),
            ),
            byCapability = emptyList(),
            generatedAtMs = 5L,
        )

        val snap = report.toSnapshot(nowMs = 999L)

        assertEquals(APP_ACCESS_SCHEMA_VERSION, snap.schemaVersion)
        assertEquals(999L, snap.generatedAtMs)
        // Sorted by package name for stable JSON, regardless of input order.
        assertEquals(listOf("com.a", "com.b"), snap.apps.map { it.packageName })

        val alpha = snap.apps.single { it.packageName == "com.a" }
        assertEquals("Alpha", alpha.label)
        assertTrue(alpha.isSystem)
        assertEquals(setOf(PermCapability.LOCATION, PermCapability.MICROPHONE), alpha.granted)

        // declaredNotGranted is deliberately dropped: only granted survives.
        val bravo = snap.apps.single { it.packageName == "com.b" }
        assertEquals(setOf(PermCapability.CAMERA), bravo.granted)
    }

    // ---- diffAppAccess: guards ----

    @Test
    fun diffWithNullPreviousIsEmpty() {
        val current = snapshot(grant("com.a", granted = setOf(PermCapability.CAMERA)))
        assertTrue(diffAppAccess(previous = null, current = current).isEmpty())
    }

    @Test
    fun diffOfIdenticalSnapshotsIsEmpty() {
        val snap = snapshot(
            grant("com.a", granted = setOf(PermCapability.CAMERA, PermCapability.LOCATION)),
            grant("com.b", granted = setOf(PermCapability.MICROPHONE)),
        )
        assertTrue(diffAppAccess(previous = snap, current = snap).isEmpty())
    }

    @Test
    fun emptyCurrentAgainstNonEmptyPreviousIsEmpty() {
        // A failed scan (empty) must never read as "every app was uninstalled".
        val previous = snapshot(grant("com.a", granted = setOf(PermCapability.CAMERA)))
        val current = snapshot()
        assertTrue(diffAppAccess(previous, current).isEmpty())
    }

    // ---- diffAppAccess: per-package changes ----

    @Test
    fun gainedCapabilityEmittedWithCurrentApp() {
        val previous = snapshot(grant("com.a", label = "Alpha", granted = setOf(PermCapability.CAMERA)))
        val current = snapshot(
            grant("com.a", label = "Alpha", granted = setOf(PermCapability.CAMERA, PermCapability.LOCATION)),
        )
        val findings = diffAppAccess(previous, current)
        val gained = findings.single() as AppAccessFinding.CapabilityGained
        assertEquals(PermCapability.LOCATION, gained.capability)
        assertEquals("com.a", gained.app.packageName)
        assertEquals(setOf(PermCapability.CAMERA, PermCapability.LOCATION), gained.app.granted)
    }

    @Test
    fun revokedCapabilityEmittedAsFinding() {
        val previous = snapshot(
            grant("com.a", granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE)),
        )
        val current = snapshot(grant("com.a", granted = setOf(PermCapability.CAMERA)))
        val findings = diffAppAccess(previous, current)
        val revoked = findings.single() as AppAccessFinding.CapabilityRevoked
        assertEquals(PermCapability.MICROPHONE, revoked.capability)
        assertEquals("com.a", revoked.app.packageName)
    }

    @Test
    fun newAppWithGrantsEmitsNewApp() {
        val previous = snapshot(grant("com.old", granted = setOf(PermCapability.CAMERA)))
        val current = snapshot(
            grant("com.old", granted = setOf(PermCapability.CAMERA)),
            grant("com.new", label = "New", granted = setOf(PermCapability.LOCATION)),
        )
        val findings = diffAppAccess(previous, current)
        val newApp = findings.single() as AppAccessFinding.NewApp
        assertEquals("com.new", newApp.app.packageName)
    }

    @Test
    fun newAppWithNoGrantsEmitsNothing() {
        val previous = snapshot(grant("com.old", granted = setOf(PermCapability.CAMERA)))
        val current = snapshot(
            grant("com.old", granted = setOf(PermCapability.CAMERA)),
            grant("com.new", label = "New", granted = emptySet()),
        )
        assertTrue(diffAppAccess(previous, current).isEmpty())
    }

    @Test
    fun removedAppEmitsAppRemovedWithPreviousLabel() {
        val previous = snapshot(
            grant("com.stay", granted = setOf(PermCapability.CAMERA)),
            grant("com.gone", label = "Gone", granted = setOf(PermCapability.LOCATION)),
        )
        val current = snapshot(grant("com.stay", granted = setOf(PermCapability.CAMERA)))
        val findings = diffAppAccess(previous, current)
        val removed = findings.single() as AppAccessFinding.AppRemoved
        assertEquals("com.gone", removed.packageName)
        assertEquals("Gone", removed.label)
    }

    // ---- diffAppAccess: deterministic ordering ----

    @Test
    fun findingsSortedByTypeThenPackageThenCapabilityOrdinal() {
        val previous = snapshot(
            grant("com.gain", granted = setOf(PermCapability.SMS)),
            grant("com.revoke", granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE)),
            grant("com.gone", label = "Gone", granted = setOf(PermCapability.LOCATION)),
        )
        val current = snapshot(
            // Two new apps, out of package order, to prove the package tiebreak.
            grant("com.newZ", label = "Zed", granted = setOf(PermCapability.CAMERA)),
            grant("com.newA", label = "Ann", granted = setOf(PermCapability.CAMERA)),
            // Gains two sensitive caps: LOCATION (ordinal 0) before CAMERA (ordinal 1).
            grant("com.gain", granted = setOf(PermCapability.SMS, PermCapability.LOCATION, PermCapability.CAMERA)),
            grant("com.revoke", granted = setOf(PermCapability.CAMERA)),
        )

        val findings = diffAppAccess(previous, current)

        // NewApp (0) < CapabilityGained (1) < CapabilityRevoked (2) < AppRemoved (3),
        // then package name, then capability ordinal.
        assertEquals(
            listOf(
                "NewApp:com.newA",
                "NewApp:com.newZ",
                "Gained:com.gain:LOCATION",
                "Gained:com.gain:CAMERA",
                "Revoked:com.revoke:MICROPHONE",
                "Removed:com.gone",
            ),
            findings.map { it.describe() },
        )
    }

    private fun AppAccessFinding.describe(): String = when (this) {
        is AppAccessFinding.NewApp -> "NewApp:${app.packageName}"
        is AppAccessFinding.CapabilityGained -> "Gained:${app.packageName}:${capability.name}"
        is AppAccessFinding.CapabilityRevoked -> "Revoked:${app.packageName}:${capability.name}"
        is AppAccessFinding.AppRemoved -> "Removed:$packageName"
    }

    // ---- JSON round trip ----

    @Test
    fun jsonRoundTripPreservesSnapshot() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val snap = AppAccessSnapshot(
            schemaVersion = APP_ACCESS_SCHEMA_VERSION,
            generatedAtMs = 1_234L,
            apps = listOf(
                grant("com.a", "Alpha", isSystem = false, granted = setOf(PermCapability.CAMERA, PermCapability.LOCATION)),
                grant("com.sys", "Sys", isSystem = true, granted = emptySet()),
            ),
        )
        val encoded = json.encodeToString(AppAccessSnapshot.serializer(), snap)
        val decoded = json.decodeFromString(AppAccessSnapshot.serializer(), encoded)
        assertEquals(snap, decoded)
    }
}
