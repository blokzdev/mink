package com.mink.guardian

import com.mink.core.model.SignalCategory
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

// ---- Learning constants ----

/** Most recent distinct value hashes kept per signal (LRU, most-recent last). */
const val MAX_KNOWN_HASHES = 8

/** Most recent change timestamps kept per signal (ring, oldest first). */
const val MAX_CHANGE_EPOCHS = 16

/** Hard cap on tracked signals; least-recently-seen are evicted beyond this. */
const val MAX_TRACKED_SIGNALS = 1200

/** Signals not seen for this long are pruned from the baseline. */
const val PRUNE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

/** Sweeps required before learned behaviour (vs. the legacy analyzer) kicks in. */
const val MIN_SWEEPS_FOR_LEARNING = 6

/** Sweeps a signal must be seen, unchanged, before it counts as a stable anchor. */
const val STABLE_MIN_SWEEPS = 12

/** Age a signal must reach, unchanged, before it counts as a stable anchor. */
const val STABLE_MIN_AGE_MS = 7L * 24 * 60 * 60 * 1000

/** changeCount/sweepsSeen at or above this (with enough samples) is expected-volatile. */
const val VOLATILE_RATE = 0.5

/** Minimum sweeps before a signal can be classified expected-volatile. */
const val VOLATILE_MIN_SWEEPS = 6

/** Window used to decide a signal keeps flapping. */
const val FLAP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** Changes within [FLAP_WINDOW_MS] (including this sweep) that make a pattern. */
const val FLAP_MIN_CHANGES = 3

/** Minimum recorded change events before the hour histogram is trusted. */
const val UNUSUAL_HOUR_MIN_SAMPLES = 8

private const val MS_PER_DAY = 24L * 60 * 60 * 1000
private const val DIGEST_MAX_CHARS = 500
private const val NAME_TRUNCATE = 40

/**
 * Per-signal learned history. Stores only value *hashes*, never raw values, so
 * the baseline never becomes a second copy of the fingerprint data.
 *
 * This is data-layer machinery persisted and consumed by [GuardianAnalyzer], not
 * a public contract type, so — a deliberate, documented deviation from
 * [GuardianStore]'s private-DTO convention — it is `@Serializable` directly.
 */
@Serializable
data class SignalStats(
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sweepsSeen: Int,
    val changeCount: Int,
    val lastChangeMs: Long? = null,
    val currentValueHash: String,
    /** Bounded LRU of distinct value hashes, most-recent LAST, max [MAX_KNOWN_HASHES]. */
    val knownValueHashes: List<String> = emptyList(),
    /** Bounded ring of change timestamps, oldest first, max [MAX_CHANGE_EPOCHS]. */
    val recentChangeEpochs: List<Long> = emptyList(),
    /** Change events bucketed by local hour-of-day. */
    val changeHourHistogram: List<Int> = List(24) { 0 },
    /** Last-known display name (the baseline never stores the raw value). */
    val name: String = "",
)

/**
 * The learned historical baseline for a device: how many sweeps have run, when,
 * at what hours, and the per-signal history keyed by [FingerprintSignal.id]
 * (`"category.key"`).
 *
 * All types in this file are data-layer machinery (persisted and consumed by the
 * analyzer), NOT public contract types, so — a deliberate, documented deviation
 * from [GuardianStore]'s private-DTO convention — they are `@Serializable`
 * directly rather than mirrored through a DTO. Privacy invariant: only value
 * *hashes* are ever stored, never raw signal values.
 */
@Serializable
data class GuardianBaseline(
    val createdMs: Long,
    val sweepCount: Int = 0,
    val lastSweepMs: Long = 0L,
    val sweepHourHistogram: List<Int> = List(24) { 0 },
    /** key = [FingerprintSignal.id] ("category.key"). */
    val signals: Map<String, SignalStats> = emptyMap(),
) {
    companion object {
        /** An empty baseline created now. */
        fun empty(nowMs: Long): GuardianBaseline = GuardianBaseline(createdMs = nowMs)
    }
}

/** One signal that has been changing lately, for the UI/LLM summary. */
data class DriftingSignal(
    val signalId: String,
    val name: String,
    val categoryId: String,
    val recentChanges: Int,
)

/**
 * A compact, UI/LLM-facing digest of the baseline. Not serializable: it is
 * derived on demand from [GuardianBaseline.summary].
 */
data class BaselineSummary(
    val learningSinceMs: Long,
    val sweepCount: Int,
    val trackedSignals: Int,
    val stableAnchors: Int,
    val expectedVolatile: Int,
    val driftingSignals: List<DriftingSignal>,
    val isMature: Boolean,
)

/**
 * SHA-256 of the UTF-8 bytes of [value], as the first 8 bytes in lowercase hex
 * (16 chars). Used so the baseline stores value identity without the value.
 */
fun hashValue(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

/**
 * Fold [current] into this baseline, returning the updated copy. Pure: all time
 * comes from [nowMs] and hour attribution from [zone].
 *
 * Semantics: sweep count and hour histogram advance; each signal present in the
 * snapshot is created or updated; on a value-hash change the change count,
 * timestamp ring, hour histogram and known-hash LRU advance. Signals ABSENT from
 * the snapshot are left untouched (a denied/unreadable category must not read as
 * a change). Stale entries are pruned and the map is capped.
 */
fun GuardianBaseline.updated(
    current: GuardianSnapshot,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): GuardianBaseline {
    val hour = hourOf(nowMs, zone)
    val newSweepHist = sweepHourHistogram.toMutableList().also { it[hour] = it[hour] + 1 }
    val next = signals.toMutableMap()

    for ((_, snaps) in current.categories) {
        for (snap in snaps) {
            val id = snap.id
            val hash = hashValue(snap.value)
            val existing = next[id]
            next[id] = if (existing == null) {
                SignalStats(
                    firstSeenMs = nowMs,
                    lastSeenMs = nowMs,
                    sweepsSeen = 1,
                    changeCount = 0,
                    lastChangeMs = null,
                    currentValueHash = hash,
                    knownValueHashes = listOf(hash),
                    recentChangeEpochs = emptyList(),
                    changeHourHistogram = List(24) { 0 },
                    name = snap.name,
                )
            } else if (existing.currentValueHash != hash) {
                existing.copy(
                    lastSeenMs = nowMs,
                    sweepsSeen = existing.sweepsSeen + 1,
                    changeCount = existing.changeCount + 1,
                    lastChangeMs = nowMs,
                    currentValueHash = hash,
                    knownValueHashes = lruAppend(existing.knownValueHashes, hash, MAX_KNOWN_HASHES),
                    recentChangeEpochs = (existing.recentChangeEpochs + nowMs).takeLast(MAX_CHANGE_EPOCHS),
                    changeHourHistogram = existing.changeHourHistogram.toMutableList()
                        .also { it[hour] = it[hour] + 1 },
                    name = snap.name,
                )
            } else {
                existing.copy(
                    lastSeenMs = nowMs,
                    sweepsSeen = existing.sweepsSeen + 1,
                    name = snap.name,
                )
            }
        }
    }

    val pruneBefore = nowMs - PRUNE_AFTER_MS
    val pruned = next.filterValues { it.lastSeenMs >= pruneBefore }
    val capped = if (pruned.size > MAX_TRACKED_SIGNALS) {
        pruned.entries
            .sortedByDescending { it.value.lastSeenMs }
            .take(MAX_TRACKED_SIGNALS)
            .associate { it.key to it.value }
    } else {
        pruned
    }

    return copy(
        sweepCount = sweepCount + 1,
        lastSweepMs = nowMs,
        sweepHourHistogram = newSweepHist,
        signals = capped,
    )
}

/** Derive the [BaselineSummary] for this baseline as of [nowMs]. */
fun GuardianBaseline.summary(nowMs: Long): BaselineSummary {
    var stable = 0
    var volatileCount = 0
    val drifting = mutableListOf<DriftingSignal>()
    for ((id, s) in signals) {
        if (s.isStableAnchor(nowMs)) stable++
        if (s.isExpectedVolatile()) {
            volatileCount++
        } else {
            val recent = s.changesWithin(FLAP_WINDOW_MS, nowMs)
            if (recent > 0) {
                drifting += DriftingSignal(
                    signalId = id,
                    name = s.name,
                    categoryId = categoryIdOf(id),
                    recentChanges = recent,
                )
            }
        }
    }
    return BaselineSummary(
        learningSinceMs = createdMs,
        sweepCount = sweepCount,
        trackedSignals = signals.size,
        stableAnchors = stable,
        expectedVolatile = volatileCount,
        driftingSignals = drifting.sortedByDescending { it.recentChanges }.take(5),
        isMature = sweepCount >= MIN_SWEEPS_FOR_LEARNING,
    )
}

/**
 * "since today", "for 1 day", or "for N days" — the shared phrasing for how
 * long the guardian has been learning, used by the dashboard card, the chat
 * rules fallback, and the LLM digest so all three surfaces agree.
 */
fun learningDurationPhrase(learningSinceMs: Long, nowMs: Long): String {
    val days = ((nowMs - learningSinceMs) / MS_PER_DAY).coerceAtLeast(0)
    return when (days) {
        0L -> "since today"
        1L -> "for 1 day"
        else -> "for $days days"
    }
}

/**
 * A compact learned-rhythm digest for the LLM system prompt and the rules
 * fallback. Empty until the baseline is mature. Signal names are truncated and
 * the whole block is kept well under 500 chars (nCtx is 1024-2048). [nowMs]
 * defaults to the wall clock but is injectable for testing.
 */
internal fun rhythmDigest(summary: BaselineSummary, nowMs: Long = System.currentTimeMillis()): String {
    if (!summary.isMature) return ""
    val duration = learningDurationPhrase(summary.learningSinceMs, nowMs)
    val drifting = summary.driftingSignals.take(3).joinToString(", ") { d ->
        "${truncateName(d.name)} (${d.recentChanges}× this week)"
    }
    val digest = buildString {
        append("Learned device rhythm (be specific when asked):\n")
        append("- watching ${summary.trackedSignals} signals $duration across ${summary.sweepCount} sweeps\n")
        append(
            "- ${summary.stableAnchors} stable identity anchors, " +
                "${summary.expectedVolatile} naturally-changing readings",
        )
        if (drifting.isNotEmpty()) append("\n- drifting lately: $drifting")
    }
    return digest.take(DIGEST_MAX_CHARS)
}

// ---- Per-signal insight helpers (pure) ----

/** A value that changes so often it is noise, not signal (e.g. battery %, uptime). */
fun SignalStats.isExpectedVolatile(): Boolean =
    sweepsSeen >= VOLATILE_MIN_SWEEPS && changeCount.toDouble() / sweepsSeen >= VOLATILE_RATE

/** A value that has held steady long enough to be a trustworthy anchor. */
fun SignalStats.isStableAnchor(nowMs: Long): Boolean =
    changeCount == 0 &&
        sweepsSeen >= STABLE_MIN_SWEEPS &&
        nowMs - firstSeenMs >= STABLE_MIN_AGE_MS

/** How many recorded changes fall within [windowMs] before [nowMs]. */
fun SignalStats.changesWithin(windowMs: Long, nowMs: Long): Int =
    recentChangeEpochs.count { it >= nowMs - windowMs }

/**
 * Whether a change at [nowMs] lands on an hour this device has never (nor in the
 * adjacent hours) changed this signal before, once enough history exists.
 */
fun SignalStats.isUnusualHour(nowMs: Long, zone: ZoneId): Boolean {
    if (changeHourHistogram.sum() < UNUSUAL_HOUR_MIN_SAMPLES) return false
    val h = hourOf(nowMs, zone)
    val prev = (h - 1 + 24) % 24
    val nextHour = (h + 1) % 24
    return changeHourHistogram[h] == 0 &&
        changeHourHistogram[prev] == 0 &&
        changeHourHistogram[nextHour] == 0
}

// ---- internals ----

/** Local hour-of-day (0-23) for [epochMs] in [zone]. */
internal fun hourOf(epochMs: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(epochMs).atZone(zone).hour

/**
 * The category id for a signal id. Signal ids are `"${category.id}.$key"` and
 * category ids never contain a dot, but keys can (e.g. `cpu.cores.detail`,
 * `network.iface.eth0`), so a plain substring split is unsafe. Instead match the
 * longest known [SignalCategory] id that prefixes the signal id.
 */
internal fun categoryIdOf(signalId: String): String =
    SignalCategory.entries
        .filter { signalId.startsWith(it.id + ".") }
        .maxByOrNull { it.id.length }
        ?.id
        ?: signalId.substringBefore('.')

/** LRU append: move-to-end if present, then evict oldest past [cap]. */
private fun lruAppend(list: List<String>, value: String, cap: Int): List<String> {
    val appended = list.filter { it != value } + value
    return if (appended.size > cap) appended.takeLast(cap) else appended
}

/** Truncate a display name to [NAME_TRUNCATE] chars with an ellipsis. */
private fun truncateName(name: String): String =
    if (name.length <= NAME_TRUNCATE) name else name.take(NAME_TRUNCATE - 1) + "…"
