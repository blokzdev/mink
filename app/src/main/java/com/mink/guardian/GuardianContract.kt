package com.mink.guardian

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Capability tier the guardian runs at, chosen from device RAM / CPU. Higher
 * tiers load a larger MiniCPM5-1B quant; the lowest tier runs no model at all
 * and degrades to the deterministic rules engine.
 */
enum class GuardianTier {
    /** 8 GB+ RAM, 64-bit, modern SoC: MiniCPM5-1B Q8_0, thinking mode allowed. */
    FULL,

    /** 4-6 GB RAM: MiniCPM5-1B Q4_K_M, no-think mode for latency. */
    LITE,

    /** 3 GB RAM: Q4_K_M loaded lazily, short context, summaries only. */
    MINIMAL,

    /** < 3 GB or unsupported ABI: rules-only, no LLM. */
    RULES_ONLY;

    val displayName: String
        get() = when (this) {
            FULL -> "Full Guardian"
            LITE -> "Lite Guardian"
            MINIMAL -> "Minimal Guardian"
            RULES_ONLY -> "Rules Guardian"
        }
}

/** Lifecycle of the on-device model asset. */
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, LOADING, LOADED, FAILED, UNSUPPORTED }

/** Snapshot of the model manager for the UI. */
data class GuardianModelState(
    val status: ModelStatus = ModelStatus.ABSENT,
    val downloadProgress: Float = 0f,
    val quantName: String = "",
    val sizeBytes: Long = 0L,
    val message: String? = null,
)

/** Top-level guardian state the UI observes. */
data class GuardianState(
    val enabled: Boolean = false,
    val tier: GuardianTier = GuardianTier.RULES_ONLY,
    val model: GuardianModelState = GuardianModelState(),
    val lastSweepEpochMs: Long = 0L,
    val observationCount: Int = 0,
    val openAlertCount: Int = 0,
    val alertness: Alertness = Alertness.STANDARD,
    val mutedSources: Set<AlertSource> = emptySet(),
)

/** Severity of a guardian finding, drives colour and companion urgency. */
enum class AlertLevel { INFO, SUGGESTION, WARNING, CRITICAL }

/**
 * How eagerly the guardian interrupts. Configuration for notifications only —
 * every finding always lands in the timeline regardless.
 */
enum class Alertness { QUIET, STANDARD, PARANOID }

/**
 * A single thing the guardian noticed: a new exposure, an anomaly, a pattern,
 * or a suggestion. Persisted so history survives restarts.
 */
data class GuardianAlert(
    val id: String,
    val level: AlertLevel,
    val title: String,
    val body: String,
    val categoryId: String?,
    val createdAtEpochMs: Long,
    val acknowledged: Boolean = false,
    /**
     * Set only by lane-5 immutable rules. An alert with this flag always
     * notifies and no setting can mute it.
     */
    val fromImmutableRule: Boolean = false,
)

/** A recorded observation in the guardian's timeline. */
data class Observation(
    val id: String,
    val categoryId: String,
    val summary: String,
    val epochMs: Long,
    val kind: ObservationKind,
)

enum class ObservationKind { SNAPSHOT, CHANGE, ANOMALY, PATTERN }

/** Role in a guardian chat exchange. */
enum class ChatRole { USER, GUARDIAN, SYSTEM }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val epochMs: Long,
    val thinking: String? = null,
    val streaming: Boolean = false,
)

/**
 * The guardian's public surface. A concrete [GuardianController] implements
 * this; the UI and companion depend only on the interface.
 *
 * Contract for the implementation constructor (wired in ServiceWiring):
 *   GuardianController(context: Context, store: SignalStore, scope: CoroutineScope)
 */
interface Guardian {
    val state: StateFlow<GuardianState>
    val alerts: StateFlow<List<GuardianAlert>>
    val observations: StateFlow<List<Observation>>
    val chatLog: StateFlow<List<ChatMessage>>

    /**
     * A digest of what the guardian has learned about this device's rhythms, or
     * null until a baseline exists. Defaulted so other [Guardian] implementers
     * stay source-compatible; [GuardianController] overrides it with a real flow.
     */
    val baseline: StateFlow<BaselineSummary?>
        get() = MutableStateFlow(null)

    /** Enable the guardian: pick a tier, prepare the model, begin observing. */
    fun enable()

    /** Disable and release the model. */
    fun disable()

    /** Force a full signal sweep and analysis pass now. */
    fun sweepNow()

    /** Begin (or resume) downloading the model asset for this device's tier. */
    fun prepareModel()

    /**
     * Send a user message and stream the guardian's reply tokens. The returned
     * flow completes when generation ends. Implementations also append the
     * exchange to [chatLog].
     */
    fun chat(message: String): Flow<String>

    /**
     * Compose a one-line companion remark for [alert] using the on-device model,
     * or null to fall back to the alert title. Never throws; returns null when no
     * model is loaded. Defaulted so other [Guardian] implementers stay
     * source-compatible; [GuardianController] overrides it. The model only writes
     * the sentence — it never drives the alert or the companion's mood.
     */
    suspend fun composeRemark(alert: GuardianAlert): String? = null

    fun acknowledgeAlert(id: String)

    /**
     * Set how eagerly the guardian notifies. Notifications only; every finding
     * still lands in the timeline. Defaulted so other [Guardian] implementers
     * stay source-compatible; [GuardianController] overrides it.
     */
    fun setAlertness(alertness: Alertness) {}

    /**
     * Mute or unmute one family of findings for notifications. Notifications
     * only; muted findings still land in the timeline. Defaulted so other
     * [Guardian] implementers stay source-compatible; [GuardianController]
     * overrides it.
     */
    fun setSourceMuted(source: AlertSource, muted: Boolean) {}
}
