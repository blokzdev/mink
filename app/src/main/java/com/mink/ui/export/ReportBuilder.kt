package com.mink.ui.export

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A serializable snapshot of one category for the exported report. */
@Serializable
data class ExportCategory(
    val id: String,
    val title: String,
    val sensitivity: String,
    val signals: List<FingerprintSignal>,
)

/** The whole exported report: metadata plus every collected category. */
@Serializable
data class ExportReport(
    val app: String = "Mink",
    val generatedAtEpochMs: Long,
    val categoryCount: Int,
    val signalCount: Int,
    val categories: List<ExportCategory>,
)

/**
 * Turns the store's snapshot into a JSON document and a human-readable text
 * report. Pure and deterministic given a snapshot and a timestamp, so it is
 * easy to reason about and stays off the main thread. This is the only path by
 * which any data leaves the device, and only when the user asks.
 */
object ReportBuilder {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun buildReport(
        snapshot: Map<SignalCategory, List<FingerprintSignal>>,
        generatedAtEpochMs: Long,
    ): ExportReport {
        // Iterate the enum for a stable, declaration-ordered document.
        val categories = SignalCategory.entries.mapNotNull { category ->
            val signals = snapshot[category].orEmpty()
            if (signals.isEmpty()) return@mapNotNull null
            ExportCategory(
                id = category.id,
                title = category.title,
                sensitivity = category.sensitivity.title,
                signals = signals,
            )
        }
        return ExportReport(
            generatedAtEpochMs = generatedAtEpochMs,
            categoryCount = categories.size,
            signalCount = categories.sumOf { it.signals.size },
            categories = categories,
        )
    }

    fun toJson(report: ExportReport): String = json.encodeToString(report)

    fun toText(report: ExportReport): String = buildString {
        appendLine("Mink privacy report")
        appendLine("Generated at epoch ms: ${report.generatedAtEpochMs}")
        appendLine("Categories: ${report.categoryCount}   Signals: ${report.signalCount}")
        appendLine()
        appendLine(
            "This report was read on your phone and stays with you. Each reading below is " +
                "something an app could take. On their own they seem harmless; together they " +
                "can single your phone out.",
        )
        appendLine()
        report.categories.forEach { category ->
            appendLine("== ${category.title} (${category.sensitivity}) ==")
            category.signals.forEach { signal ->
                appendLine("  ${signal.name}: ${signal.value.ifBlank { "unavailable" }}")
                signal.entries?.forEach { entry ->
                    appendLine("      - ${entry.label}: ${entry.value}")
                }
            }
            appendLine()
        }
    }
}
