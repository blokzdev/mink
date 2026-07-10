package com.mink.ui.export

import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    private fun signal(category: SignalCategory, key: String, value: String) =
        FingerprintSignal.make(
            key = key,
            category = category,
            name = "Name-$key",
            value = value,
            rationale = "why",
            displayHint = DisplayHint.PLAIN,
        )

    @Test
    fun buildReportSkipsEmptyCategoriesAndCounts() {
        val snapshot = mapOf(
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Pixel 8"),
                signal(SignalCategory.DEVICE_IDENTITY, "brand", "Google"),
            ),
            SignalCategory.LOCALE to emptyList(),
        )
        val report = ReportBuilder.buildReport(snapshot, generatedAtEpochMs = 1000L)
        assertEquals(1, report.categoryCount)
        assertEquals(2, report.signalCount)
        assertEquals(1000L, report.generatedAtEpochMs)
        assertFalse(report.categories.any { it.id == SignalCategory.LOCALE.id })
    }

    @Test
    fun jsonAndTextAreProducedAndRoundTrip() {
        val snapshot = mapOf(
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Pixel 8"),
            ),
        )
        val report = ReportBuilder.buildReport(snapshot, 42L)
        val json = ReportBuilder.toJson(report)
        val text = ReportBuilder.toText(report)
        assertTrue(json.contains("Pixel 8"))
        assertTrue(json.contains("\"app\""))
        assertTrue(text.contains("Device Identity"))
        assertTrue(text.contains("Pixel 8"))
    }

    @Test
    fun categoriesFollowEnumOrder() {
        val snapshot = mapOf(
            SignalCategory.LOCALE to listOf(signal(SignalCategory.LOCALE, "region", "CA")),
            SignalCategory.DEVICE_IDENTITY to listOf(signal(SignalCategory.DEVICE_IDENTITY, "model", "X")),
        )
        val report = ReportBuilder.buildReport(snapshot, 1L)
        // DEVICE_IDENTITY is declared before LOCALE in the enum.
        assertEquals(SignalCategory.DEVICE_IDENTITY.id, report.categories.first().id)
    }
}
