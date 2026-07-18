package com.mink.narrative

import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintNarrativeTest {

    private fun signal(category: SignalCategory, key: String, name: String, value: String) =
        FingerprintSignal.make(
            key = key,
            category = category,
            name = name,
            value = value,
            rationale = "test rationale",
            displayHint = DisplayHint.PLAIN,
        )

    @Test
    fun emptySnapshotYieldsZeroScoreAndNoCards() {
        val report = FingerprintNarrative.build(emptyMap())
        assertEquals(0, report.uniquenessScore)
        assertTrue(report.cards.isEmpty())
        assertTrue(report.topSignals.isEmpty())
        assertTrue(report.headline.isNotBlank())
    }

    @Test
    fun richSnapshotProducesThreeToSixCards() {
        val snapshot = mapOf(
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Model", "Pixel 8"),
            ),
            SignalCategory.LOCALE to listOf(
                signal(SignalCategory.LOCALE, "region", "Region", "Canada"),
            ),
            SignalCategory.STORAGE to listOf(
                signal(SignalCategory.STORAGE, "total", "Total", "128 GB"),
            ),
            SignalCategory.GPU to listOf(
                signal(SignalCategory.GPU, "renderer", "Renderer", "Adreno 730"),
            ),
            SignalCategory.SENSORS to listOf(
                signal(SignalCategory.SENSORS, "count", "Sensor count", "34"),
            ),
        )
        val report = FingerprintNarrative.build(snapshot)
        assertTrue("expected 3-6 cards, got ${report.cards.size}", report.cards.size in 3..6)
        report.cards.forEach { card ->
            assertTrue("basis must be phrased 'Read from'", card.basis.startsWith("Read from "))
            assertTrue(card.title.isNotBlank())
            assertTrue(card.body.isNotBlank())
        }
    }

    @Test
    fun strongCategoriesSurfaceAsTopSignals() {
        val snapshot = mapOf(
            SignalCategory.GPU to listOf(
                signal(SignalCategory.GPU, "renderer", "Renderer", "Adreno 730"),
            ),
            SignalCategory.SENSORS to listOf(
                signal(SignalCategory.SENSORS, "count", "Sensor count", "34"),
            ),
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Model", "Pixel 8"),
            ),
        )
        val report = FingerprintNarrative.build(snapshot)
        assertTrue(report.topSignals.isNotEmpty())
        // GPU is highest priority and should come first.
        assertEquals(SignalCategory.GPU, report.topSignals.first().category)
        report.topSignals.forEach { assertTrue(it.why.isNotBlank()) }
    }

    @Test
    fun scoreIsDeterministicAndBounded() {
        val snapshot = mapOf(
            SignalCategory.DEVICE_IDENTITY to listOf(
                signal(SignalCategory.DEVICE_IDENTITY, "model", "Model", "Pixel 8"),
            ),
        )
        val a = FingerprintNarrative.build(snapshot)
        val b = FingerprintNarrative.build(snapshot)
        assertEquals(a.uniquenessScore, b.uniquenessScore)
        assertTrue(a.uniquenessScore in 0..100)
        assertNotNull(a.headline)
    }

    @Test
    fun everyCategoryPopulatedStaysCappedAtSixCards() {
        val snapshot = SignalCategory.entries.associateWith { category ->
            listOf(signal(category, "k", category.title, "value"))
        }
        val report = FingerprintNarrative.build(snapshot)
        assertTrue(report.cards.size <= 6)
        assertTrue(report.topSignals.size <= 5)
        assertTrue(report.uniquenessScore in 0..100)
    }
}
