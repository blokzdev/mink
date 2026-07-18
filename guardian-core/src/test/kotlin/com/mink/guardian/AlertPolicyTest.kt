package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [alertSource] and [NotificationGate]: the source mapping,
 * the alertness dial floor, per-source mutes, the immutable-rule override, and
 * the repeated-notification cooldown. The gate takes injected time, so every
 * branch is asserted deterministically.
 */
class AlertPolicyTest {

    private fun alert(
        id: String = "alert-1",
        level: AlertLevel = AlertLevel.WARNING,
        title: String = "Something changed",
        categoryId: String? = "network",
        fromImmutableRule: Boolean = false,
    ): GuardianAlert = GuardianAlert(
        id = id,
        level = level,
        title = title,
        body = "Body.",
        categoryId = categoryId,
        createdAtEpochMs = 0L,
        fromImmutableRule = fromImmutableRule,
    )

    // ---- alertSource mapping ----

    @Test
    fun ruleIdMapsToExposureInsights() {
        assertEquals(
            AlertSource.EXPOSURE_INSIGHTS,
            alertSource(alert(id = "rule.location.exposed", categoryId = "location")),
        )
    }

    @Test
    fun appAccessCategoryMapsToAccessChanges() {
        assertEquals(
            AlertSource.ACCESS_CHANGES,
            alertSource(alert(categoryId = APP_ACCESS_CATEGORY)),
        )
    }

    @Test
    fun sensorUseCategoryMapsToSensorUse() {
        assertEquals(
            AlertSource.SENSOR_USE,
            alertSource(alert(categoryId = SENSOR_USE_CATEGORY)),
        )
    }

    @Test
    fun highRiskCategoryMapsToSecurityChanges() {
        assertEquals(
            AlertSource.SECURITY_CHANGES,
            alertSource(alert(categoryId = HIGH_RISK_CATEGORY)),
        )
    }

    @Test
    fun dnsFlowCategoryMapsToDnsFlow() {
        assertEquals(
            AlertSource.DNS_FLOW,
            alertSource(alert(categoryId = DNS_FLOW_CATEGORY)),
        )
    }

    @Test
    fun signalCategoryIdMapsToSignalChanges() {
        assertEquals(AlertSource.SIGNAL_CHANGES, alertSource(alert(categoryId = "location")))
    }

    @Test
    fun nullCategoryWithNonRuleIdMapsToSignalChanges() {
        assertEquals(AlertSource.SIGNAL_CHANGES, alertSource(alert(id = "alert-7", categoryId = null)))
    }

    // ---- alertness dial ----

    @Test
    fun dialFloorMatrix() {
        // QUIET notifies CRITICAL only; STANDARD notifies WARNING and up;
        // PARANOID notifies SUGGESTION and up. INFO never notifies on any
        // setting: it is timeline material.
        val expected = mapOf(
            Alertness.QUIET to setOf(AlertLevel.CRITICAL),
            Alertness.STANDARD to setOf(AlertLevel.WARNING, AlertLevel.CRITICAL),
            Alertness.PARANOID to setOf(
                AlertLevel.SUGGESTION,
                AlertLevel.WARNING,
                AlertLevel.CRITICAL,
            ),
        )
        for (alertness in Alertness.entries) {
            for (level in AlertLevel.entries) {
                // A fresh gate per case so the cooldown never interferes.
                val gate = NotificationGate()
                val settings = GuardianSettings(alertness = alertness)
                assertEquals(
                    "alertness=$alertness level=$level",
                    level in expected.getValue(alertness),
                    gate.shouldNotify(alert(level = level), settings, elapsedMs = 0L),
                )
            }
        }
    }

    // ---- mutes ----

    @Test
    fun mutedAccessChangesSuppressesAppAccessWarning() {
        val gate = NotificationGate()
        val settings = GuardianSettings(mutedSources = setOf(AlertSource.ACCESS_CHANGES))
        assertFalse(
            gate.shouldNotify(alert(categoryId = APP_ACCESS_CATEGORY), settings, elapsedMs = 0L),
        )
    }

    @Test
    fun mutedCriticalWithoutImmutableFlagIsSuppressed() {
        // A mute outranks severity for everything except the immutable flag:
        // even CRITICAL stays quiet when its source is muted.
        val gate = NotificationGate()
        val settings = GuardianSettings(mutedSources = setOf(AlertSource.ACCESS_CHANGES))
        assertFalse(
            gate.shouldNotify(
                alert(level = AlertLevel.CRITICAL, categoryId = APP_ACCESS_CATEGORY),
                settings,
                elapsedMs = 0L,
            ),
        )
    }

    @Test
    fun mutingOneSourceLeavesOtherSourcesUnaffected() {
        val gate = NotificationGate()
        val settings = GuardianSettings(mutedSources = setOf(AlertSource.ACCESS_CHANGES))
        assertTrue(
            gate.shouldNotify(
                alert(categoryId = SENSOR_USE_CATEGORY, title = "Sensor warning"),
                settings,
                elapsedMs = 0L,
            ),
        )
        assertTrue(
            gate.shouldNotify(
                alert(categoryId = "network", title = "Signal warning"),
                settings,
                elapsedMs = 0L,
            ),
        )
        assertTrue(
            gate.shouldNotify(
                alert(id = "rule.x", categoryId = "location", title = "Insight warning"),
                settings,
                elapsedMs = 0L,
            ),
        )
    }

    // ---- immutable-rule override ----

    @Test
    fun immutableRuleOverridesDialMuteAndCooldown() {
        // Precedence rule 1: no configuration silences an immutable-rule
        // alert — not the QUIET dial, not a muted source, not the cooldown.
        // The alert is a WARNING, not CRITICAL, so each of those three would
        // suppress it on its own; the flag must beat all of them.
        val gate = NotificationGate()
        val settings = GuardianSettings(
            alertness = Alertness.QUIET,
            mutedSources = setOf(AlertSource.ACCESS_CHANGES),
        )
        val combo = alert(
            level = AlertLevel.WARNING,
            title = "New app can see, hear, and locate you",
            categoryId = APP_ACCESS_CATEGORY,
            fromImmutableRule = true,
        )
        assertTrue(gate.shouldNotify(combo, settings, elapsedMs = 0L))
        // A second identical alert well inside the cooldown window still notifies.
        assertTrue(gate.shouldNotify(combo, settings, elapsedMs = 60_000L))
    }

    // ---- cooldown ----

    @Test
    fun repeatedWarningIsCooledDownThenAllowedAgain() {
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val warning = alert(level = AlertLevel.WARNING, title = "Mic used with the screen off")
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 0L))
        assertFalse(gate.shouldNotify(warning, settings, elapsedMs = 29L * 60L * 1000L))
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 31L * 60L * 1000L))
    }

    @Test
    fun differentTitleWithinWindowStillNotifies() {
        val gate = NotificationGate()
        val settings = GuardianSettings()
        assertTrue(
            gate.shouldNotify(alert(level = AlertLevel.WARNING, title = "First warning"), settings, elapsedMs = 0L),
        )
        assertTrue(
            gate.shouldNotify(alert(level = AlertLevel.WARNING, title = "Second warning"), settings, elapsedMs = 60_000L),
        )
    }

    @Test
    fun criticalRepeatsWithinWindowStillNotify() {
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val critical = alert(level = AlertLevel.CRITICAL, title = "Critical finding")
        assertTrue(gate.shouldNotify(critical, settings, elapsedMs = 0L))
        assertTrue(gate.shouldNotify(critical, settings, elapsedMs = 60_000L))
    }

    @Test
    fun suppressedAlertRecordsNoCooldown() {
        // A pass blocked by a mute must not start the cooldown clock: once
        // the mute is lifted, the same alert notifies right away even though
        // the suppressed pass was well inside the window.
        val gate = NotificationGate()
        val muted = GuardianSettings(mutedSources = setOf(AlertSource.ACCESS_CHANGES))
        val warning = alert(level = AlertLevel.WARNING, categoryId = APP_ACCESS_CATEGORY)
        assertFalse(gate.shouldNotify(warning, muted, elapsedMs = 0L))
        assertTrue(gate.shouldNotify(warning, GuardianSettings(), elapsedMs = 60_000L))
    }

    @Test
    fun cooldownBoundaryIsExclusive() {
        // The comparison is strict (<): one millisecond inside the window is
        // suppressed, exactly NOTIFICATION_COOLDOWN_MS after the last allowed
        // pass notifies again.
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val warning = alert(level = AlertLevel.WARNING, title = "Boundary warning")
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 0L))
        assertFalse(gate.shouldNotify(warning, settings, elapsedMs = NOTIFICATION_COOLDOWN_MS - 1))
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = NOTIFICATION_COOLDOWN_MS))
    }

    // ---- settings defaults ----

    @Test
    fun defaultSettingsAreStandardWithNoMutes() {
        val settings = GuardianSettings()
        assertEquals(Alertness.STANDARD, settings.alertness)
        assertTrue(settings.mutedSources.isEmpty())
    }

    // ---- refiner cooldown multiplier (quieter-only) ----

    @Test
    fun defaultMultiplierReproducesTheFlatCooldown() {
        // The refiner's default (1) must behave identically to the pre-refiner gate.
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val warning = alert(level = AlertLevel.WARNING, title = "Repeat")
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 0L, cooldownMultiplier = 1))
        assertFalse(gate.shouldNotify(warning, settings, elapsedMs = NOTIFICATION_COOLDOWN_MS - 1, cooldownMultiplier = 1))
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = NOTIFICATION_COOLDOWN_MS, cooldownMultiplier = 1))
    }

    @Test
    fun multiplierThreeLengthensTheRepeatWindow() {
        // At multiplier 3 the window is 90 min: a repeat at 60 min is suppressed,
        // the same repeat at 90 min notifies.
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val warning = alert(level = AlertLevel.WARNING, title = "Noisy repeat")
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 0L, cooldownMultiplier = 3))
        assertFalse(gate.shouldNotify(warning, settings, elapsedMs = 2 * NOTIFICATION_COOLDOWN_MS, cooldownMultiplier = 3))
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 3 * NOTIFICATION_COOLDOWN_MS, cooldownMultiplier = 3))
    }

    @Test
    fun multiplierNeverAffectsCriticalOrImmutable() {
        // Quieter-only lives in the cooldown step, which CRITICAL and immutable
        // alerts bypass entirely: even multiplier 3 leaves them notifying on repeat.
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val critical = alert(level = AlertLevel.CRITICAL, title = "Critical repeat")
        assertTrue(gate.shouldNotify(critical, settings, elapsedMs = 0L, cooldownMultiplier = 3))
        assertTrue(gate.shouldNotify(critical, settings, elapsedMs = 60_000L, cooldownMultiplier = 3))

        val immutable = alert(level = AlertLevel.WARNING, title = "Immutable repeat", fromImmutableRule = true)
        assertTrue(gate.shouldNotify(immutable, settings, elapsedMs = 0L, cooldownMultiplier = 3))
        assertTrue(gate.shouldNotify(immutable, settings, elapsedMs = 60_000L, cooldownMultiplier = 3))
    }

    @Test
    fun multiplierBelowOneCannotShortenBelowDefault() {
        // Defensive clamp: a stray multiplier under 1 must never make the gate
        // louder than the flat default — the refiner is strictly quieter-only.
        val gate = NotificationGate()
        val settings = GuardianSettings()
        val warning = alert(level = AlertLevel.WARNING, title = "Clamp")
        assertTrue(gate.shouldNotify(warning, settings, elapsedMs = 0L, cooldownMultiplier = 0))
        assertFalse(gate.shouldNotify(warning, settings, elapsedMs = NOTIFICATION_COOLDOWN_MS - 1, cooldownMultiplier = 0))
    }
}
