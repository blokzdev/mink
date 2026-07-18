package com.mink.guardian

import kotlinx.serialization.Serializable

/**
 * Pure data carriers the guardian pipeline passes around and the store
 * persists, kept apart from [GuardianStore] (an Android/DataStore concern) so
 * the pipeline logic that consumes them stays platform-free.
 */

/** Persisted guardian settings. */
data class GuardianSettings(
    val enabled: Boolean = false,
    val tierOverride: GuardianTier? = null,
    val modelDownloaded: Boolean = false,
    val alertness: Alertness = Alertness.STANDARD,
    val mutedSources: Set<AlertSource> = emptySet(),
)

/** One recorded signal value, used for diffing snapshots across sweeps. */
@Serializable
data class SignalSnap(val id: String, val name: String, val value: String)

/** A full sweep snapshot: category id to the signals collected for it. */
@Serializable
data class GuardianSnapshot(
    val epochMs: Long = 0L,
    val categories: Map<String, List<SignalSnap>> = emptyMap(),
)
