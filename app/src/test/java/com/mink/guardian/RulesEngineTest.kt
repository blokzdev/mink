package com.mink.guardian

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesEngineTest {

    private val rules = RulesEngine()

    private fun signal(category: SignalCategory, key: String, value: String): FingerprintSignal =
        FingerprintSignal.make(
            key = key,
            category = category,
            name = key,
            value = value,
            rationale = "Read from test.",
        )

    @Test
    fun emptySnapshotProducesNoFindings() {
        assertTrue(rules.evaluate(emptyMap()).isEmpty())
    }

    @Test
    fun exposedLocationRaisesWarning() {
        val snapshot = mapOf(
            SignalCategory.LOCATION to listOf(signal(SignalCategory.LOCATION, "coords", "37.7, -122.4")),
        )
        val findings = rules.evaluate(snapshot)
        val location = findings.first { it.categoryId == SignalCategory.LOCATION.id }
        assertEquals(AlertLevel.WARNING, location.level)
    }

    @Test
    fun installedAppsFindingIncludesCount() {
        val snapshot = mapOf(
            SignalCategory.INSTALLED_APPS to listOf(
                signal(SignalCategory.INSTALLED_APPS, "total", "212"),
            ),
        )
        val findings = rules.evaluate(snapshot)
        val apps = findings.first { it.categoryId == SignalCategory.INSTALLED_APPS.id }
        assertTrue(apps.body.contains("212"))
        assertEquals(AlertLevel.SUGGESTION, apps.level)
    }

    @Test
    fun findingsSortedBySeverityDescending() {
        val snapshot = mapOf(
            SignalCategory.LOCATION to listOf(signal(SignalCategory.LOCATION, "coords", "1,2")),
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Pixel"),
            ),
        )
        val findings = rules.evaluate(snapshot)
        // WARNING (location) must come before INFO (device identity).
        val levels = findings.map { it.level }
        val warnIdx = levels.indexOf(AlertLevel.WARNING)
        val infoIdx = levels.indexOf(AlertLevel.INFO)
        assertTrue(warnIdx in 0 until infoIdx)
    }

    @Test
    fun clipboardWithContentSuggests() {
        val snapshot = mapOf(
            SignalCategory.CLIPBOARD to listOf(
                signal(SignalCategory.CLIPBOARD, "hasClip", "true"),
            ),
        )
        val findings = rules.evaluate(snapshot)
        assertTrue(findings.any { it.categoryId == SignalCategory.CLIPBOARD.id })
    }

    @Test
    fun emptyClipboardSignalDoesNotSuggest() {
        val snapshot = mapOf(
            SignalCategory.CLIPBOARD to listOf(
                signal(SignalCategory.CLIPBOARD, "hasClip", "empty"),
            ),
        )
        val findings = rules.evaluate(snapshot)
        assertFalse(findings.any { it.categoryId == SignalCategory.CLIPBOARD.id })
    }

    @Test
    fun answerFallsBackToOverviewForUnknownQuestion() {
        val snapshot = mapOf(
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Pixel"),
            ),
        )
        val reply = rules.answer("hello there", snapshot)
        assertTrue(reply.isNotBlank())
        // Voice check: calm, no exclamation marks.
        assertFalse(reply.contains("!"))
    }

    @Test
    fun answerAboutLocationReflectsExposure() {
        val exposed = mapOf(
            SignalCategory.LOCATION to listOf(signal(SignalCategory.LOCATION, "coords", "1,2")),
        )
        val reply = rules.answer("Is my location safe?", exposed)
        assertTrue(reply.lowercase().contains("exposed"))
    }

    @Test
    fun defaultLevelForMapsSensitivity() {
        assertEquals(AlertLevel.WARNING, rules.defaultLevelFor(SignalCategory.LOCATION))
        assertEquals(AlertLevel.SUGGESTION, rules.defaultLevelFor(SignalCategory.INSTALLED_APPS))
        assertEquals(AlertLevel.INFO, rules.defaultLevelFor(SignalCategory.DEVICE_IDENTITY))
    }
}
