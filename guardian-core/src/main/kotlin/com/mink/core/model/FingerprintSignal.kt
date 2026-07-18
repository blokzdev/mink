package com.mink.core.model

import kotlinx.serialization.Serializable

/**
 * How a signal's value should be laid out in the detail UI. Mirrors Loupe's
 * DisplayHint so the same rich rows (key/value tables, axis vectors, chips)
 * render consistently.
 */
@Serializable
enum class DisplayHint {
    /** Default monospaced text. */
    PLAIN,

    /** Vertical list of label -> value rows. */
    KEY_VALUE,

    /** Compact horizontal layout for 3-axis / vector data. */
    AXIS,

    /** Horizontally wrapping chips. */
    TAGS,

    /** Side-by-side labelled parts for a composite value. */
    COMPOUND,
}

/** One label/value pair inside a rich signal (used by KEY_VALUE / COMPOUND / AXIS). */
@Serializable
data class SignalEntry(
    val label: String,
    val value: String,
)

/**
 * One row in a category detail screen: a named reading, its displayable
 * value, and a short rationale that teaches the user why it leaks identity.
 *
 * [id] must be stable across launches (it is derived from the category and a
 * provider-chosen key) so snapshots hash and diff cleanly and the guardian
 * can track a signal's history over time.
 */
@Serializable
data class FingerprintSignal(
    val id: String,
    val name: String,
    val value: String,
    val rationale: String,
    val sensitivity: Sensitivity = Sensitivity.PASSIVE,
    val displayHint: DisplayHint = DisplayHint.PLAIN,
    val entries: List<SignalEntry>? = null,
) {
    companion object {
        /**
         * Convenience builder used inside provider bodies. Prepends the
         * category id to [key] so the resulting [id] is deterministic and
         * globally unique across launches.
         */
        fun make(
            key: String,
            category: SignalCategory,
            name: String,
            value: String,
            rationale: String,
            displayHint: DisplayHint = DisplayHint.PLAIN,
            entries: List<SignalEntry>? = null,
        ): FingerprintSignal = FingerprintSignal(
            id = "${category.id}.$key",
            name = name,
            value = value,
            rationale = rationale,
            sensitivity = category.sensitivity,
            displayHint = displayHint,
            entries = entries,
        )
    }
}
