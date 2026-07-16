package com.mink.monitor

import java.util.Locale

/** Current on-disk schema of the data-use cursor; a single scalar, so it never migrates. */
const val DATA_USE_SCHEMA_VERSION = 1

/**
 * Do not run the data-use check more often than this. Usage is a continuous
 * metric, not an event, so a shorter cadence would re-alert a chronically heavy
 * app on every sweep. Tunable in a future release by design — NOT a lane-5
 * immutable rule.
 */
const val DATA_USE_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

/**
 * A gap longer than this is too aggregated to attribute to one interval, so the
 * check reseeds its cursor without alerting (phone off for a day, access
 * re-granted, and the like). Tunable by design — NOT a lane-5 immutable rule.
 */
const val DATA_USE_MAX_WINDOW_MS = 26L * 60 * 60 * 1000 // 26 hours

/**
 * Background cellular over the data-use window that warrants a note. A
 * deterministic threshold, tunable in a future release by design — NOT a lane-5
 * immutable rule, and sized for a multi-hour window, not a whole day.
 */
const val BACKGROUND_MOBILE_ALERT_BYTES = 50L * 1024 * 1024 // 50 MB over the window

/**
 * Roaming data over the data-use window that warrants a note. A deterministic
 * threshold, tunable in a future release by design — NOT a lane-5 immutable
 * rule, and sized for a multi-hour window, not a whole day.
 */
const val ROAMING_ALERT_BYTES = 20L * 1024 * 1024 // 20 MB over the window

/** What the guard should do with the data-use cursor this sweep. Pure decision, no I/O. */
sealed interface DataUseDecision {
    /** Set the cursor to [cursorMs] and do not scan or alert (first run, clock jump, or long gap). */
    data class Seed(val cursorMs: Long) : DataUseDecision

    /** Too soon since the last check; leave the cursor untouched and do nothing. */
    data object Skip : DataUseDecision

    /** Scan [startMs, endMs], then advance the cursor to [endMs]. */
    data class Analyze(val startMs: Long, val endMs: Long) : DataUseDecision
}

/**
 * Decide the data-use window from the persisted cursor and now. A null cursor or
 * a backwards clock reseeds; a gap under [DATA_USE_MIN_INTERVAL_MS] skips; a gap
 * over [DATA_USE_MAX_WINDOW_MS] reseeds without alerting; otherwise analyze
 * [lastCheckMs, nowMs]. Pure and total.
 */
fun dataUseWindow(lastCheckMs: Long?, nowMs: Long): DataUseDecision = when {
    lastCheckMs == null -> DataUseDecision.Seed(nowMs)
    nowMs <= lastCheckMs -> DataUseDecision.Seed(nowMs)
    nowMs - lastCheckMs < DATA_USE_MIN_INTERVAL_MS -> DataUseDecision.Skip
    nowMs - lastCheckMs > DATA_USE_MAX_WINDOW_MS -> DataUseDecision.Seed(nowMs)
    else -> DataUseDecision.Analyze(lastCheckMs, nowMs)
}

/**
 * Format a byte count for display: whole bytes under 1 KB ("512 B"), else one
 * decimal in the largest unit that keeps the value below its ceiling ("1.5 KB",
 * "5.0 MB", "2.0 GB"). A value that rounds up to a unit's ceiling promotes to the
 * next unit, so 1_048_575 renders "1.0 MB", not "1024.0 KB". US formatting so the
 * decimal separator is stable. Pure.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (unit < units.lastIndex && roundedTenth(value) >= 1024.0) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

/** Round to one decimal place, matching how [formatBytes] renders the value. */
private fun roundedTenth(v: Double): Double = Math.round(v * 10.0) / 10.0

/**
 * One app's data volume over a window, split by network and condition. Volumes
 * only: this records how much an app sent and received, never to where — Android
 * does not expose destinations to a normal app, and neither does Mink.
 */
data class AppDataUsage(
    val uid: Int,
    val packageName: String?,
    val label: String,
    val isSystem: Boolean,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val roamingBytes: Long,
    val backgroundMobileBytes: Long,
) {
    /** WiFi plus cellular; roaming and background are subsets of cellular, not added again. */
    val totalBytes: Long get() = wifiBytes + mobileBytes
}

/** Per-app data volumes over one window, sorted by total descending then label. */
data class DataUsageReport(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val apps: List<AppDataUsage>,
    val deviceTotalBytes: Long,
)

/** The top [n] apps by total volume, for a compact summary. */
fun DataUsageReport.topConsumers(n: Int): List<AppDataUsage> = apps.take(n)

/** One notable thing the data-use watcher found over one sweep interval's usage. */
sealed interface DataUsageFinding {
    data class HeavyBackgroundMobile(val app: AppDataUsage) : DataUsageFinding
    data class HeavyRoaming(val app: AppDataUsage) : DataUsageFinding
}

/**
 * Find the notable data use in one interval's per-app volumes. Pure and total.
 * For each USER app (isSystem == false): background cellular at or above
 * [BACKGROUND_MOBILE_ALERT_BYTES] yields [DataUsageFinding.HeavyBackgroundMobile],
 * roaming at or above [ROAMING_ALERT_BYTES] yields [DataUsageFinding.HeavyRoaming],
 * and one app can produce both. System apps are ignored: their background and
 * roaming traffic is expected and not the owner's to act on.
 *
 * Deterministic order: findings sorted by (type rank: background, roaming), then
 * by the finding's own bytes descending, then uid.
 */
fun analyzeDataUsage(apps: List<AppDataUsage>): List<DataUsageFinding> {
    val findings = mutableListOf<DataUsageFinding>()
    for (app in apps) {
        if (app.isSystem) continue
        if (app.backgroundMobileBytes >= BACKGROUND_MOBILE_ALERT_BYTES) {
            findings += DataUsageFinding.HeavyBackgroundMobile(app)
        }
        if (app.roamingBytes >= ROAMING_ALERT_BYTES) {
            findings += DataUsageFinding.HeavyRoaming(app)
        }
    }
    return findings.sortedWith(
        compareBy<DataUsageFinding> { it.typeRank() }
            .thenByDescending { it.findingBytes() }
            .thenBy { it.uid() },
    )
}

private fun DataUsageFinding.typeRank(): Int = when (this) {
    is DataUsageFinding.HeavyBackgroundMobile -> 0
    is DataUsageFinding.HeavyRoaming -> 1
}

private fun DataUsageFinding.findingBytes(): Long = when (this) {
    is DataUsageFinding.HeavyBackgroundMobile -> app.backgroundMobileBytes
    is DataUsageFinding.HeavyRoaming -> app.roamingBytes
}

private fun DataUsageFinding.uid(): Int = when (this) {
    is DataUsageFinding.HeavyBackgroundMobile -> app.uid
    is DataUsageFinding.HeavyRoaming -> app.uid
}
