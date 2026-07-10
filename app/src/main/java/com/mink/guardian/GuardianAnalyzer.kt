package com.mink.guardian

import com.mink.core.model.Sensitivity
import com.mink.core.model.SignalCategory
import java.util.UUID

/**
 * Compares the previous sweep snapshot with the current one and reports what
 * changed: brand new exposures, values that drifted, and categories that keep
 * changing (a pattern). Emits both timeline [Observation]s and, for anything
 * worth surfacing, [GuardianAlert]s.
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
}
