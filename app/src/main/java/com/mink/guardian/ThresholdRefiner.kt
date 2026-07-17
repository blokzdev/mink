package com.mink.guardian

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/**
 * The slow loop of a two-loop notification policy. The fast loop is
 * [NotificationGate], which decides notify/suppress on every sweep. This slow
 * loop runs once every [RefinerConfig.periodSweeps] sweeps and turns the user's
 * only real engagement signal — whether they tap "Got it" on an alert — into a
 * per-[AlertSource] cooldown adjustment, so a family of findings the user
 * demonstrably ignores repeats less often, while one they engage with keeps its
 * voice.
 *
 * Everything here is pure, deterministic, statistical, and Android-free, exactly
 * like [Baseline] and [AlertPolicy]. The on-device model has no input to and no
 * output from any of it — the capability floor: rules and statistics decide,
 * the 1B model never authors its own scaffolding.
 *
 * The knob is deliberately the gentlest one reachable. The only thing adapted is
 * a per-source integer [SourceRefine.level] `0..levelMax`, exposed to the gate
 * as a cooldown multiplier `level + 1 ∈ {1, 2, 3}` → windows of {30, 60, 90}
 * minutes. Because the multiplier lives solely in the gate's cooldown step and
 * is always ≥ 1, the refiner **can only make the guardian quieter**, and it can
 * never touch an immutable-rule alert, a muted source, a CRITICAL finding, or
 * the first occurrence of any alert — only the repeat interval of something the
 * user has already been shown at least once. Level 0 reproduces today's flat
 * 30-minute cooldown exactly.
 *
 * See [refineThresholds] for the decision and its safeguards against the central
 * statistical trap — a globally disengaged user who acknowledges nothing must
 * never be read as "every source is noise".
 */

/** Current on-disk schema of [RefinerState]; bump on any incompatible change. */
const val REFINER_SCHEMA_VERSION = 1

/** Hard cap on the per-source level; multiplier = level + 1, so 2 → {1,2,3}. */
const val REFINER_LEVEL_MAX = 2

/**
 * Tunables for the refiner, all defaulted so tests can vary them and the
 * production defaults live in one place. Every value biases hard toward
 * inaction: the refiner would rather leave a source loud than wrongly quiet one.
 */
data class RefinerConfig(
    /** Sweeps between refiner ticks. 24 ≈ once a day at the 60-minute cadence. */
    val periodSweeps: Int = 24,
    /** A fresh unacked alert may simply not have been seen yet; ignore it until this old. */
    val maturityMs: Long = 48L * 60 * 60 * 1000,
    /** Evidence older than this ages out of the window. */
    val windowMs: Long = 14L * 24 * 60 * 60 * 1000,
    /** Matured, eligible alerts a source needs before it can be raised. */
    val minSourceSamples: Int = 8,
    /** Matured, eligible alerts across all sources before the loop acts at all. */
    val minGlobalSamples: Int = 20,
    /** Below this smoothed global ack rate the user is globally disengaged: no raises. */
    val globalEngagementFloor: Double = 0.20,
    /** Raise only when a source runs at or below this fraction of the user's own rate. */
    val relTighten: Double = 0.5,
    /** Release when a source recovers to at or above this fraction; 0.5↔0.8 stops flapping. */
    val relRecover: Double = 0.8,
    /** EWMA weight on the newest observation; the rest is carried memory. */
    val alpha: Double = 0.4,
    /** Level cap; multiplier = level + 1. */
    val levelMax: Int = REFINER_LEVEL_MAX,
    /** Wilson score z for the small-sample upper bound (1.96 ≈ 95%). */
    val wilsonZ: Double = 1.96,
) {
    companion object {
        val DEFAULT = RefinerConfig()
    }
}

/** The learned adjustment for one [AlertSource]. */
@Serializable
data class SourceRefine(
    /** 0..levelMax. Cooldown multiplier = level + 1. */
    val level: Int = 0,
    /** EWMA of this source's ack rate, carried across ticks and list eviction. */
    val smoothedAckRate: Double = 0.0,
    /** Whether [smoothedAckRate] has ever been seeded from a real sample. */
    val seeded: Boolean = false,
)

/**
 * The persisted refiner state. Only *derived* smoothed rates and levels — never
 * raw alert history — so it is not a second copy of behaviour, mirroring how
 * [GuardianBaseline] stores hashes, not values.
 */
@Serializable
data class RefinerState(
    /**
     * On-disk schema. Defaults to **0** (legacy/unversioned), deliberately NOT
     * [REFINER_SCHEMA_VERSION]: a blob written before versioning existed decodes
     * to 0 and must be discarded on load rather than trusted. Discarding is
     * strictly safe — an empty state means every multiplier is 1, i.e. today's
     * behaviour. [GuardianStore.loadRefinerState] enforces discard-on-mismatch.
     */
    val schemaVersion: Int = 0,
    /** key = [AlertSource.name]. */
    val perSource: Map<String, SourceRefine> = emptyMap(),
    /** EWMA of the user's overall ack rate, the yardstick each source is judged against. */
    val smoothedGlobalAckRate: Double = 0.0,
    /** Whether [smoothedGlobalAckRate] has ever been seeded from a real sample. */
    val globalSeeded: Boolean = false,
    /** The dial in force at the last tick, so a dial change can reset learned levels. */
    val lastAlertnessName: String = "",
    /** The muted sources at the last tick, so a mute toggle can reset that source. */
    val lastMutedSourceNames: Set<String> = emptySet(),
) {
    /**
     * The cooldown multiplier for [source]: `level + 1`, coerced into
     * {1..levelMax+1}. An unknown source (never adjusted) yields 1 — the flat
     * default. This is the ONLY value the gate reads from the refiner.
     */
    fun cooldownMultiplier(source: AlertSource): Int =
        (perSource[source.name]?.level ?: 0).coerceIn(0, REFINER_LEVEL_MAX) + 1

    companion object {
        /** A stamped empty state, for a first run or after a discarded blob. */
        fun empty(): RefinerState = RefinerState(schemaVersion = REFINER_SCHEMA_VERSION)
    }
}

/** Matured, eligible counts for one source over the window. */
data class SourceSample(val total: Int, val acked: Int)

/** The engagement projection of the alert list at one tick. */
data class EngagementSample(
    val perSource: Map<AlertSource, SourceSample>,
    val globalTotal: Int,
    val globalAcked: Int,
)

/** The user's current manual settings, so a change can reset stale learned levels. */
data class RefinerContext(
    val alertness: Alertness,
    val mutedSources: Set<AlertSource>,
)

/**
 * Project the alert list into per-source engagement counts. The only function
 * that reads clock and alert fields; the decision itself ([refineThresholds]) is
 * pure arithmetic over the result.
 *
 * An alert counts toward its source only if it was a genuine notification
 * candidate whose acknowledgement (or lack of it) is a real signal:
 *  - not an immutable-rule alert (those always notify and can never be throttled);
 *  - not CRITICAL (CRITICAL is exempt from the cooldown, so it is never throttled);
 *  - at or above the [eligibleFloor] the given dial would notify (an alert below
 *    the floor never became a notification, so its unacked state is not noise);
 *  - matured past [RefinerConfig.maturityMs] (a fresh unacked alert may simply
 *    not have been seen yet — recency must not read as rejection);
 *  - within [RefinerConfig.windowMs] (stale evidence ages out).
 */
fun engagementSampleOf(
    alerts: List<GuardianAlert>,
    eligibleFloor: AlertLevel,
    nowMs: Long,
    config: RefinerConfig = RefinerConfig.DEFAULT,
): EngagementSample {
    val perSource = HashMap<AlertSource, IntArray>() // [total, acked]
    var globalTotal = 0
    var globalAcked = 0
    for (alert in alerts) {
        if (alert.fromImmutableRule) continue
        if (alert.level == AlertLevel.CRITICAL) continue
        if (alert.level < eligibleFloor) continue
        val age = nowMs - alert.createdAtEpochMs
        if (age < config.maturityMs || age > config.windowMs) continue
        val source = alertSource(alert)
        val bucket = perSource.getOrPut(source) { IntArray(2) }
        bucket[0]++
        globalTotal++
        if (alert.acknowledged) {
            bucket[1]++
            globalAcked++
        }
    }
    return EngagementSample(
        perSource = perSource.mapValues { SourceSample(it.value[0], it.value[1]) },
        globalTotal = globalTotal,
        globalAcked = globalAcked,
    )
}

/**
 * The Wilson score interval's upper bound for [successes] out of [n] at
 * [z] confidence. Used so a small, noisy sample is judged by its *optimistic*
 * bound, not its point estimate: a 0/8 source has an upper bound near 0.31, so
 * it must be ignored even under that generous reading before the refiner acts.
 * Returns 1.0 for `n <= 0` (no evidence → maximally optimistic → never raises).
 */
internal fun wilson95Upper(successes: Int, n: Int, z: Double = 1.96): Double {
    if (n <= 0) return 1.0
    val nD = n.toDouble()
    val phat = successes.toDouble() / nD
    val z2 = z * z
    val denom = 1.0 + z2 / nD
    val center = phat + z2 / (2 * nD)
    val margin = z * sqrt(phat * (1 - phat) / nD + z2 / (4 * nD * nD))
    return ((center + margin) / denom).coerceIn(0.0, 1.0)
}

/**
 * The slow loop. Pure and total: same inputs → same output, no clock, no I/O.
 * Given the [prior] state, this tick's [sample], and the user's [context],
 * returns the next state. Mirrors [GuardianBaseline.updated] in shape.
 *
 * Order of operations:
 *  0. **Override reset (race-free).** A change to the dial resets every learned
 *     level to 0 (the whole notification regime moved); toggling a source's mute
 *     resets just that source. Done here, deterministically, so no refiner write
 *     ever happens off the sweep mutex. The user's explicit act supersedes
 *     anything learned.
 *  1. **Smoothing.** Update the global and per-source EWMA rates, but only from a
 *     sample large enough to trust ([RefinerConfig.minGlobalSamples] /
 *     [RefinerConfig.minSourceSamples]); otherwise carry the learned rate
 *     forward, so a quiet source keeps its memory across list eviction.
 *  2. **Global disengagement gate.** If the user has too few samples or a
 *     smoothed global ack rate below [RefinerConfig.globalEngagementFloor], they
 *     are not engaging at all: make no raises and snap every level back to 0. A
 *     user who acknowledges nothing has a near-zero global rate, so global
 *     silence can only *loosen* the guardian, never tighten it.
 *  3. **Per source.** RAISE (+1, capped) only when there is enough evidence, the
 *     Wilson upper bound sits at or below the smoothed global rate, and the
 *     smoothed source rate is at or below [RefinerConfig.relTighten] of it — a
 *     purely *relative* judgement, never absolute. RELEASE to 0 the moment the
 *     source recovers to [RefinerConfig.relRecover] of global or its evidence
 *     falls away. The 0.5↔0.8 hysteresis band keeps a borderline source from
 *     flapping. Escalation is one level per tick (so the cap needs days of
 *     sustained, specific, confident ignoring); release is immediate.
 *
 * Because every level is re-justified from the live window each tick, a stale
 * suppression can never outlive the evidence that created it.
 */
fun refineThresholds(
    prior: RefinerState,
    sample: EngagementSample,
    context: RefinerContext,
    config: RefinerConfig = RefinerConfig.DEFAULT,
): RefinerState {
    val nowAlertness = context.alertness.name
    val nowMuted = context.mutedSources.map { it.name }.toSet()

    // Step 0: override reset.
    var working: Map<String, SourceRefine> = prior.perSource
    if (prior.lastAlertnessName.isNotEmpty() && nowAlertness != prior.lastAlertnessName) {
        working = working.mapValues { it.value.copy(level = 0) }
    } else {
        // Symmetric difference: sources whose mute membership toggled either way.
        val toggled = (prior.lastMutedSourceNames - nowMuted) + (nowMuted - prior.lastMutedSourceNames)
        if (toggled.isNotEmpty()) {
            working = working.mapValues { (k, v) -> if (k in toggled) v.copy(level = 0) else v }
        }
    }

    // Step 1: smooth the global rate (seed on first trusted sample, else carry).
    val globalRate = if (sample.globalTotal > 0) sample.globalAcked.toDouble() / sample.globalTotal else 0.0
    val globalTrusted = sample.globalTotal >= config.minGlobalSamples
    val smoothedGlobal = when {
        !globalTrusted -> prior.smoothedGlobalAckRate
        prior.globalSeeded -> config.alpha * globalRate + (1 - config.alpha) * prior.smoothedGlobalAckRate
        else -> globalRate
    }
    val globalSeeded = prior.globalSeeded || globalTrusted

    val baseState = prior.copy(
        schemaVersion = REFINER_SCHEMA_VERSION,
        smoothedGlobalAckRate = smoothedGlobal,
        globalSeeded = globalSeeded,
        lastAlertnessName = nowAlertness,
        lastMutedSourceNames = nowMuted,
    )

    // Step 2: global disengagement gate — snap everything down, act on nothing.
    if (!globalTrusted || smoothedGlobal < config.globalEngagementFloor) {
        return baseState.copy(perSource = working.mapValues { it.value.copy(level = 0) })
    }

    // Step 3: per-source raise / keep / release.
    val next = working.toMutableMap()
    val sampledKeys = HashSet<String>()
    for ((source, s) in sample.perSource) {
        val key = source.name
        sampledKeys += key
        val prev = next[key] ?: SourceRefine()
        val enoughSamples = s.total >= config.minSourceSamples
        val rate = if (s.total > 0) s.acked.toDouble() / s.total else 0.0
        val smoothed = when {
            !enoughSamples -> prev.smoothedAckRate
            prev.seeded -> config.alpha * rate + (1 - config.alpha) * prev.smoothedAckRate
            else -> rate
        }
        val seeded = prev.seeded || enoughSamples

        val level = when {
            enoughSamples &&
                wilson95Upper(s.acked, s.total, config.wilsonZ) <= smoothedGlobal &&
                smoothed <= config.relTighten * smoothedGlobal ->
                (prev.level + 1).coerceAtMost(config.levelMax)
            !enoughSamples || smoothed >= config.relRecover * smoothedGlobal ->
                0
            else ->
                prev.level
        }
        next[key] = prev.copy(level = level, smoothedAckRate = smoothed, seeded = seeded)
    }
    // A source with no matured evidence this window has no case to stay throttled.
    for (key in next.keys.toList()) {
        val entry = next.getValue(key)
        if (key !in sampledKeys && entry.level != 0) {
            next[key] = entry.copy(level = 0)
        }
    }

    return baseState.copy(perSource = next)
}
