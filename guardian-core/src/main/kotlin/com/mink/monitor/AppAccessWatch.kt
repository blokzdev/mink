package com.mink.monitor

import kotlinx.serialization.Serializable

/** Current on-disk schema of [AppAccessSnapshot]; discard on mismatch (see GuardianStore). */
const val APP_ACCESS_SCHEMA_VERSION = 1

/** Capabilities whose GAIN by a user app warrants an alert, not just an observation. */
val WATCH_SENSITIVE: Set<PermCapability> = setOf(
    PermCapability.LOCATION, PermCapability.CAMERA, PermCapability.MICROPHONE,
    PermCapability.CONTACTS, PermCapability.PHONE, PermCapability.SMS,
    PermCapability.BODY_SENSORS,
)

/**
 * IMMUTABLE RULE (see docs/memory-architecture.md, lane 5): an app holding
 * camera, microphone, and location together can see, hear, and locate the
 * owner. A NEW app arriving with all three always raises a CRITICAL alert.
 *
 * This constant and the rule that consumes it are lane 5's first immutable rule.
 * Like every lane 5 rule they are never runtime-writable: no learned state, no
 * user feedback, and no future refiner may ever tune, weaken, or disable this
 * threshold. See docs/memory-architecture.md ("Immutable rules ... never
 * runtime-writable") for why the deepest guard cannot be taught away.
 */
val SURVEILLANCE_COMBO: Set<PermCapability> = setOf(
    PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION,
)

/**
 * One app and the capabilities it currently holds granted, reduced to the
 * minimal fields a cross-sweep diff needs. Persisted inside [AppAccessSnapshot].
 */
@Serializable
data class AppGrant(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val granted: Set<PermCapability> = emptySet(),
)

/**
 * The persisted per-sweep picture of who holds what. Persisted through
 * `GuardianStore` (encrypted at rest with a Keystore AES-GCM key, excluded from
 * backup and device transfer); keeps only the minimal fields a diff needs —
 * package, label, system flag, and the granted capability enum names. No
 * versions, no install times, and the raw values are never logged.
 */
@Serializable
data class AppAccessSnapshot(
    val schemaVersion: Int = 0,          // 0 = legacy/unversioned -> discarded on load
    val generatedAtMs: Long = 0L,
    val apps: List<AppGrant> = emptyList(),
)

/**
 * Build the minimal snapshot from a full scan report. Stamps the current schema
 * version, sorts apps by package name for stable JSON, and keeps only granted
 * capabilities ([AppRecord.declaredNotGranted] is deliberately dropped — the
 * watcher cares about what an app can reach, not what it merely asks for).
 */
fun AppAccessReport.toSnapshot(nowMs: Long): AppAccessSnapshot =
    AppAccessSnapshot(
        schemaVersion = APP_ACCESS_SCHEMA_VERSION,
        generatedAtMs = nowMs,
        apps = apps
            .sortedBy { it.packageName }
            .map {
                AppGrant(
                    packageName = it.packageName,
                    label = it.label,
                    isSystem = it.isSystem,
                    granted = it.granted,
                )
            },
    )

/** One change the app-access watcher noticed between two sweeps. */
sealed interface AppAccessFinding {
    data class CapabilityGained(val app: AppGrant, val capability: PermCapability) : AppAccessFinding
    data class CapabilityRevoked(val app: AppGrant, val capability: PermCapability) : AppAccessFinding
    data class NewApp(val app: AppGrant) : AppAccessFinding                  // only if granted not empty
    data class AppRemoved(val packageName: String, val label: String) : AppAccessFinding
}

/**
 * Diff two snapshots. Pure and total.
 * - previous == null -> emptyList (first watch sweep just records the state).
 * - current.apps empty && previous.apps not empty -> emptyList: an empty scan
 *   is a failed scan, and it must never read as "every app was uninstalled".
 * - Otherwise: per package present in both, emit CapabilityGained/Revoked per
 *   set difference; packages only in current with granted.isNotEmpty() -> NewApp;
 *   packages only in previous -> AppRemoved.
 * - Deterministic order: findings sorted by (finding type rank: NewApp,
 *   CapabilityGained, CapabilityRevoked, AppRemoved), then package name, then
 *   capability ordinal.
 */
fun diffAppAccess(previous: AppAccessSnapshot?, current: AppAccessSnapshot): List<AppAccessFinding> {
    if (previous == null) return emptyList()
    if (current.apps.isEmpty() && previous.apps.isNotEmpty()) return emptyList()

    val prevByPackage = previous.apps.associateBy { it.packageName }
    val curByPackage = current.apps.associateBy { it.packageName }

    val findings = mutableListOf<AppAccessFinding>()

    for (cur in current.apps) {
        val prev = prevByPackage[cur.packageName]
        if (prev == null) {
            if (cur.granted.isNotEmpty()) findings += AppAccessFinding.NewApp(cur)
            continue
        }
        for (capability in cur.granted - prev.granted) {
            findings += AppAccessFinding.CapabilityGained(cur, capability)
        }
        for (capability in prev.granted - cur.granted) {
            findings += AppAccessFinding.CapabilityRevoked(cur, capability)
        }
    }

    for (prev in previous.apps) {
        if (prev.packageName !in curByPackage) {
            findings += AppAccessFinding.AppRemoved(prev.packageName, prev.label)
        }
    }

    return findings.sortedWith(
        compareBy({ it.typeRank() }, { it.packageName() }, { it.capabilityOrdinal() }),
    )
}

private fun AppAccessFinding.typeRank(): Int = when (this) {
    is AppAccessFinding.NewApp -> 0
    is AppAccessFinding.CapabilityGained -> 1
    is AppAccessFinding.CapabilityRevoked -> 2
    is AppAccessFinding.AppRemoved -> 3
}

private fun AppAccessFinding.packageName(): String = when (this) {
    is AppAccessFinding.NewApp -> app.packageName
    is AppAccessFinding.CapabilityGained -> app.packageName
    is AppAccessFinding.CapabilityRevoked -> app.packageName
    is AppAccessFinding.AppRemoved -> packageName
}

private fun AppAccessFinding.capabilityOrdinal(): Int = when (this) {
    is AppAccessFinding.CapabilityGained -> capability.ordinal
    is AppAccessFinding.CapabilityRevoked -> capability.ordinal
    else -> -1
}
