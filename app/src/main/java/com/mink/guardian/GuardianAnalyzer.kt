package com.mink.guardian

import com.mink.core.model.Sensitivity
import com.mink.core.model.SignalCategory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Compares the previous sweep snapshot with the current one and reports what
 * changed: brand new exposures, values that drifted, and categories that keep
 * changing (a pattern). Emits both timeline [Observation]s and, for anything
 * worth surfacing, [GuardianAlert]s.
 *
 * When a mature [GuardianBaseline] is supplied it becomes learning-aware:
 * naturally-volatile readings are folded into the sweep summary instead of
 * alerting, a change to a long-stable anchor is elevated, reverts to previously
 * seen values are downgraded, flapping signals emit [ObservationKind.PATTERN],
 * and changes at an unusual hour are annotated. Below the maturity threshold (or
 * with no baseline) behaviour is byte-identical to the original analyzer.
 *
 * Pure with respect to its inputs apart from id/time generation, which are
 * injected so it stays testable.
 */
class GuardianAnalyzer(
    private val rules: RulesEngine = RulesEngine(),
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {

    data class Result(
        val observations: List<Observation>,
        val alerts: List<GuardianAlert>,
    )

    fun analyze(
        previous: GuardianSnapshot?,
        current: GuardianSnapshot,
        nowMs: Long,
        baseline: GuardianBaseline? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val mature = baseline != null && baseline.sweepCount >= MIN_SWEEPS_FOR_LEARNING
        return if (mature) {
            analyzeLearned(previous, current, nowMs, baseline!!, zone)
        } else {
            analyzeLegacy(previous, current, nowMs)
        }
    }

    // ---- legacy path (unchanged behaviour, kept byte-identical) ----

    private fun analyzeLegacy(
        previous: GuardianSnapshot?,
        current: GuardianSnapshot,
        nowMs: Long,
    ): Result {
        val observations = mutableListOf<Observation>()
        val alerts = mutableListOf<GuardianAlert>()

        val prevCats = previous?.categories.orEmpty()

        for ((categoryId, curSignals) in current.categories) {
            if (curSignals.isEmpty()) continue
            val category = SignalCategory.fromId(categoryId)
            val prevSignals = prevCats[categoryId].orEmpty()

            // New exposure: nothing before, something now.
            if (prevSignals.isEmpty()) {
                val title = category?.title ?: categoryId
                observations += Observation(
                    id = idFactory(),
                    categoryId = categoryId,
                    summary = "$title became readable with ${curSignals.size} signals.",
                    epochMs = nowMs,
                    kind = ObservationKind.CHANGE,
                )
                if (category != null) {
                    alerts += GuardianAlert(
                        id = idFactory(),
                        level = rules.defaultLevelFor(category),
                        title = "New exposure: ${category.title}",
                        body = "${category.title} is now readable on this device. " +
                            category.subtitle + ".",
                        categoryId = categoryId,
                        createdAtEpochMs = nowMs,
                    )
                }
                continue
            }

            // Changed values on a category already seen.
            val prevById = prevSignals.associateBy({ it.id }, { it.value })
            val changed = curSignals.filter { cur ->
                val old = prevById[cur.id]
                old != null && old != cur.value
            }
            if (changed.isNotEmpty()) {
                val title = category?.title ?: categoryId
                observations += Observation(
                    id = idFactory(),
                    categoryId = categoryId,
                    summary = "$title changed: ${changed.size} value(s) drifted since the last sweep.",
                    epochMs = nowMs,
                    kind = ObservationKind.ANOMALY,
                )
                // Only alert on drift in sensitive categories to avoid noise
                // from naturally live values like battery or uptime.
                if (category != null && category.sensitivity != Sensitivity.PASSIVE) {
                    alerts += GuardianAlert(
                        id = idFactory(),
                        level = AlertLevel.WARNING,
                        title = "${category.title} changed",
                        body = "A value under ${category.title} changed since I last looked. " +
                            "If you did not expect that, it is worth a glance.",
                        categoryId = categoryId,
                        createdAtEpochMs = nowMs,
                    )
                }
            }
        }

        // A single summary observation per sweep for the timeline.
        val exposedCount = current.categories.count { it.value.isNotEmpty() }
        observations += Observation(
            id = idFactory(),
            categoryId = "sweep",
            summary = "Swept the device: $exposedCount surfaces readable right now.",
            epochMs = nowMs,
            kind = ObservationKind.SNAPSHOT,
        )

        return Result(observations = observations, alerts = alerts)
    }

    // ---- learning-aware path ----

    private fun analyzeLearned(
        previous: GuardianSnapshot?,
        current: GuardianSnapshot,
        nowMs: Long,
        baseline: GuardianBaseline,
        zone: ZoneId,
    ): Result {
        val observations = mutableListOf<Observation>()
        val alerts = mutableListOf<GuardianAlert>()
        val prevCats = previous?.categories.orEmpty()
        var volatileTracked = 0

        for ((categoryId, curSignals) in current.categories) {
            if (curSignals.isEmpty()) continue
            val category = SignalCategory.fromId(categoryId)
            val title = category?.title ?: categoryId
            val prevSignals = prevCats[categoryId].orEmpty()

            // New exposure, possibly a re-exposure after a long absence.
            if (prevSignals.isEmpty()) {
                val lastSeen = baseline.signals
                    .filterKeys { categoryIdOf(it) == categoryId }
                    .values
                    .maxOfOrNull { it.lastSeenMs }
                val reExposed = lastSeen != null && nowMs - lastSeen >= REEXPOSED_AFTER_MS
                val summary = if (reExposed) {
                    val days = (nowMs - lastSeen!!) / DAY_MS
                    "$title re-exposed after $days days with ${curSignals.size} signals."
                } else {
                    "$title became readable with ${curSignals.size} signals."
                }
                observations += Observation(
                    id = idFactory(),
                    categoryId = categoryId,
                    summary = summary,
                    epochMs = nowMs,
                    kind = ObservationKind.CHANGE,
                )
                if (category != null) {
                    alerts += GuardianAlert(
                        id = idFactory(),
                        level = rules.defaultLevelFor(category),
                        title = "New exposure: ${category.title}",
                        body = "${category.title} is now readable on this device. " +
                            category.subtitle + ".",
                        categoryId = categoryId,
                        createdAtEpochMs = nowMs,
                    )
                }
                continue
            }

            val prevById = prevSignals.associateBy({ it.id }, { it.value })
            for (cur in curSignals) {
                val old = prevById[cur.id] ?: continue
                if (old == cur.value) continue

                val stats = baseline.signals[cur.id]
                val name = cur.name

                // 1. Naturally volatile: no per-signal noise, folded into the summary.
                if (stats != null && stats.isExpectedVolatile()) {
                    volatileTracked++
                    continue
                }

                val hourSuffix = if (stats != null && stats.isUnusualHour(nowMs, zone)) {
                    " — unusual time for this device"
                } else {
                    ""
                }

                when {
                    // 2. First change to a long-stable anchor: elevated even for PASSIVE.
                    stats != null && stats.isStableAnchor(nowMs) -> {
                        val since = formatDate(stats.firstSeenMs, zone)
                        observations += Observation(
                            id = idFactory(),
                            categoryId = categoryId,
                            summary = "$name changed for the first time since $since " +
                                "(${stats.sweepsSeen} sweeps)$hourSuffix",
                            epochMs = nowMs,
                            kind = ObservationKind.ANOMALY,
                        )
                        alerts += GuardianAlert(
                            id = idFactory(),
                            level = AlertLevel.WARNING,
                            title = "$title changed",
                            body = "$name changed for the first time since $since — it had held " +
                                "steady for ${stats.sweepsSeen} sweeps. If you did not expect " +
                                "that, it is worth a glance.",
                            categoryId = categoryId,
                            createdAtEpochMs = nowMs,
                        )
                    }

                    // 3. Reverting to a value seen before: observation only, no alert.
                    stats != null && hashValue(cur.value) in stats.knownValueHashes -> {
                        observations += Observation(
                            id = idFactory(),
                            categoryId = categoryId,
                            summary = "$name returned to a value seen before$hourSuffix",
                            epochMs = nowMs,
                            kind = ObservationKind.ANOMALY,
                        )
                    }

                    // 4. Ordinary drift: today's behaviour, alert only for non-PASSIVE.
                    else -> {
                        observations += Observation(
                            id = idFactory(),
                            categoryId = categoryId,
                            summary = "$name changed since the last sweep.$hourSuffix",
                            epochMs = nowMs,
                            kind = ObservationKind.ANOMALY,
                        )
                        if (category != null && category.sensitivity != Sensitivity.PASSIVE) {
                            alerts += GuardianAlert(
                                id = idFactory(),
                                level = AlertLevel.WARNING,
                                title = "$title changed",
                                body = "$name changed since I last looked. If you did not " +
                                    "expect that, it is worth a glance.",
                                categoryId = categoryId,
                                createdAtEpochMs = nowMs,
                            )
                        }
                    }
                }

                // 6. Pattern: this signal keeps flapping. Only ever on change-sweeps.
                if (stats != null && !stats.isExpectedVolatile()) {
                    val recent = stats.changesWithin(FLAP_WINDOW_MS, nowMs) + 1
                    if (recent >= FLAP_MIN_CHANGES) {
                        observations += Observation(
                            id = idFactory(),
                            categoryId = categoryId,
                            summary = "$name keeps changing — $recent times in the last 7 days.",
                            epochMs = nowMs,
                            kind = ObservationKind.PATTERN,
                        )
                    }
                }
            }
        }

        val exposedCount = current.categories.count { it.value.isNotEmpty() }
        val summary = buildString {
            append("Swept the device: $exposedCount surfaces readable right now")
            if (volatileTracked > 0) append(", $volatileTracked naturally-changing readings tracked")
            append(".")
        }
        observations += Observation(
            id = idFactory(),
            categoryId = "sweep",
            summary = summary,
            epochMs = nowMs,
            kind = ObservationKind.SNAPSHOT,
        )

        return Result(observations = observations, alerts = alerts)
    }

    private fun formatDate(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(DATE_FORMAT)

    private companion object {
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val REEXPOSED_AFTER_MS = 14L * 24 * 60 * 60 * 1000
    }
}
