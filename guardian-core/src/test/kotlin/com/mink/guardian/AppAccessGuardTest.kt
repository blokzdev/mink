package com.mink.guardian

import com.mink.monitor.AppAccessFinding
import com.mink.monitor.AppGrant
import com.mink.monitor.PermCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [appAccessFindingsToGuardian]: severity policy, the
 * camera+microphone+location immutable rule, revocation good-news wording, and
 * the observation/alert caps. Findings are built by hand and ids/time injected,
 * so every branch is asserted deterministically.
 */
class AppAccessGuardTest {

    /** Deterministic id source so ids can be traced back to the factory. */
    private fun sequentialIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun grant(
        packageName: String,
        label: String = packageName,
        isSystem: Boolean = false,
        granted: Set<PermCapability> = emptySet(),
    ): AppGrant = AppGrant(packageName, label, isSystem, granted)

    private fun map(
        findings: List<AppAccessFinding>,
        nowMs: Long = 7_000L,
    ): AppAccessGuardResult = appAccessFindingsToGuardian(findings, nowMs, sequentialIds())

    // ---- capability gained ----

    @Test
    fun userAppGainingLocationRaisesWarningAndObservation() {
        val app = grant("com.chrome", "Chrome", isSystem = false, granted = setOf(PermCapability.LOCATION))
        val result = map(listOf(AppAccessFinding.CapabilityGained(app, PermCapability.LOCATION)))

        assertEquals(1, result.observations.size)
        assertEquals(1, result.alerts.size)
        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("Chrome gained Location", alert.title)
        assertEquals(APP_ACCESS_CATEGORY_ID, alert.categoryId)
    }

    @Test
    fun systemAppGainingLocationIsObservationOnly() {
        val app = grant("com.android.sys", "System", isSystem = true, granted = setOf(PermCapability.LOCATION))
        val result = map(listOf(AppAccessFinding.CapabilityGained(app, PermCapability.LOCATION)))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun userAppGainingNonSensitiveCapabilityIsObservationOnly() {
        val app = grant("com.app", "App", isSystem = false, granted = setOf(PermCapability.NOTIFICATIONS))
        val result = map(listOf(AppAccessFinding.CapabilityGained(app, PermCapability.NOTIFICATIONS)))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    // ---- new app / immutable rule ----

    @Test
    fun newUserAppWithSurveillanceComboIsCritical() {
        val app = grant(
            "com.spy",
            "Spyware",
            isSystem = false,
            granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION),
        )
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.CRITICAL, alert.level)
        assertEquals("New app can see, hear, and locate you", alert.title)
        // The flag is what exempts the immutable rule from the alertness dial,
        // mutes, and cooldown (see AlertPolicy.kt).
        assertTrue(alert.fromImmutableRule)
    }

    @Test
    fun newSystemAppWithSurveillanceComboIsAlsoCritical() {
        val app = grant(
            "com.android.spy",
            "SystemSpy",
            isSystem = true,
            granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION),
        )
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.CRITICAL, alert.level)
    }

    @Test
    fun newUserAppWithSensitiveButNotComboIsWarning() {
        val app = grant("com.new", "NewApp", isSystem = false, granted = setOf(PermCapability.CAMERA, PermCapability.CONTACTS))
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("New app with access: NewApp", alert.title)
        // Only the surveillance combo carries the immutable-rule flag.
        assertFalse(alert.fromImmutableRule)
    }

    @Test
    fun newSystemAppWithoutComboIsObservationOnly() {
        val app = grant("com.android.cam", "SysCam", isSystem = true, granted = setOf(PermCapability.CAMERA))
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun newUserAppWithTwoOfThreeComboIsWarningNotCritical() {
        // The immutable rule requires ALL THREE of camera+mic+location. Two of the
        // three (here camera+mic, no location) must stay a WARNING, never CRITICAL —
        // this guards the negative boundary of the surveillance rule.
        val app = grant(
            "com.two",
            "TwoOfThree",
            isSystem = false,
            granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE),
        )
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        val alert = result.alerts.single()
        assertEquals(AlertLevel.WARNING, alert.level)
        assertEquals("New app with access: TwoOfThree", alert.title)
    }

    @Test
    fun newUserAppWithOnlyNonSensitiveGrantsIsObservationOnly() {
        // A new user app whose only grant is non-sensitive (notifications) still
        // produces a NewApp finding, but no alert — only the surveillance-worthy
        // arrivals should interrupt the user.
        val app = grant("com.quiet", "Quiet", isSystem = false, granted = setOf(PermCapability.NOTIFICATIONS))
        val result = map(listOf(AppAccessFinding.NewApp(app)))

        assertEquals(1, result.observations.size)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun surveillanceComboCriticalSurvivesAlertCap() {
        // Nine WARNING-worthy gains plus one combo new app: 10 alert-worthy findings.
        val warnings = (0 until 9).map { i ->
            AppAccessFinding.CapabilityGained(
                grant("com.user$i", "User$i", isSystem = false, granted = setOf(PermCapability.LOCATION)),
                PermCapability.LOCATION,
            )
        }
        val combo = AppAccessFinding.NewApp(
            grant(
                "com.combo",
                "Combo",
                isSystem = false,
                granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION),
            ),
        )
        val result = map(warnings + combo)

        // The combo CRITICAL is never dropped by the cap.
        assertTrue(
            result.alerts.any {
                it.level == AlertLevel.CRITICAL && it.title == "New app can see, hear, and locate you"
            },
        )
    }

    // ---- revocation ----

    @Test
    fun revocationIsObservationOnlyPhrasedAsGoodNews() {
        val app = grant("com.maps", "Maps", isSystem = false, granted = setOf(PermCapability.CAMERA))
        val result = map(listOf(AppAccessFinding.CapabilityRevoked(app, PermCapability.MICROPHONE)))

        assertTrue(result.alerts.isEmpty())
        val observation = result.observations.single()
        assertTrue(observation.summary.contains("no longer has"))
        assertTrue(observation.summary.contains("Microphone"))
    }

    // ---- caps and rollups ----

    @Test
    fun observationsCapAtTwelveWithRollup() {
        // Fifteen observation-only findings (removals) -> 12 kept + 1 rollup.
        val findings = (0 until 15).map { i ->
            AppAccessFinding.AppRemoved("com.gone$i", "Gone$i")
        }
        val result = map(findings)

        assertEquals(MAX_APP_ACCESS_OBSERVATIONS + 1, result.observations.size)
        val rollup = result.observations.last()
        assertEquals("...and 3 more app access changes.", rollup.summary)
        assertTrue(result.alerts.isEmpty())
    }

    @Test
    fun alertsCapAtSixWithRollupAndCriticalFirst() {
        // One combo CRITICAL plus seven WARNING gains: 8 alert-worthy findings.
        val combo = AppAccessFinding.NewApp(
            grant(
                "com.combo",
                "Combo",
                isSystem = false,
                granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION),
            ),
        )
        val warnings = (0 until 7).map { i ->
            AppAccessFinding.CapabilityGained(
                grant("com.user$i", "User$i", isSystem = false, granted = setOf(PermCapability.LOCATION)),
                PermCapability.LOCATION,
            )
        }
        val result = map(listOf(combo) + warnings)

        // 6 kept (combo + 5 warnings) + 1 rollup.
        assertEquals(MAX_APP_ACCESS_ALERTS + 1, result.alerts.size)
        assertEquals(AlertLevel.CRITICAL, result.alerts.first().level)
        val rollup = result.alerts.last()
        assertEquals(AlertLevel.WARNING, rollup.level)
        assertEquals("More apps changed access", rollup.title)
        assertTrue(rollup.body.contains("2 more"))
    }

    // ---- observation metadata ----

    @Test
    fun observationsCarryInjectedIdTimeKindAndCategory() {
        val now = 42_000L
        val app = grant("com.app", "App", isSystem = false, granted = setOf(PermCapability.LOCATION))
        val result = map(listOf(AppAccessFinding.CapabilityGained(app, PermCapability.LOCATION)), nowMs = now)

        val observation = result.observations.single()
        assertEquals(now, observation.epochMs)
        assertEquals(ObservationKind.CHANGE, observation.kind)
        assertEquals(APP_ACCESS_CATEGORY_ID, observation.categoryId)
        assertTrue(observation.id.startsWith("gen-"))

        val alert = result.alerts.single()
        assertEquals(now, alert.createdAtEpochMs)
        assertTrue(alert.id.startsWith("gen-"))
        // Ids are unique across observations and alerts.
        assertFalse(observation.id == alert.id)
    }

    @Test
    fun voiceIsCalmWithNoExclamationMarks() {
        val app = grant(
            "com.spy",
            "Spyware",
            isSystem = false,
            granted = setOf(PermCapability.CAMERA, PermCapability.MICROPHONE, PermCapability.LOCATION),
        )
        val result = map(listOf(AppAccessFinding.NewApp(app)))
        val alert = result.alerts.single()
        assertFalse(alert.title.contains("!"))
        assertFalse(alert.body.contains("!"))
    }

    private companion object {
        const val APP_ACCESS_CATEGORY_ID = "app_access"
    }
}
