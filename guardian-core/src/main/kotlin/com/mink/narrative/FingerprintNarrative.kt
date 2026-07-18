package com.mink.narrative

import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory

/**
 * A single readable card on the summary screen: a plain-English claim about
 * what the phone gives away, plus a short caption saying where it was read.
 */
data class NarrativeCard(
    val id: String,
    val title: String,
    val body: String,
    /** Always phrased "Read from <source>." to match the Loupe voice. */
    val basis: String,
)

/** One of the strongest identifiers found in the current snapshot. */
data class IdentifyingSignal(
    val category: SignalCategory,
    val name: String,
    val value: String,
    val why: String,
)

/**
 * The whole summary read: a headline uniqueness claim, a supporting paragraph,
 * a rough 0-100 uniqueness score, the narrative cards, and the handful of
 * readings that individuate the device the most.
 */
data class FingerprintReport(
    val headline: String,
    val detail: String,
    val uniquenessScore: Int,
    val cards: List<NarrativeCard>,
    val topSignals: List<IdentifyingSignal>,
)

/**
 * Pure, deterministic translation of a collected signal snapshot into the
 * summary narrative. No Android, no I/O, no randomness: the same snapshot
 * always yields the same report, which keeps it unit-testable and lets the
 * guardian reason about it too.
 *
 * This is the Android analogue of Loupe's FingerprintNarrative. It reads the
 * category snapshot the store already gathered rather than touching the OS
 * itself, so it stays independent of the per-signal provider details.
 */
object FingerprintNarrative {

    /** Categories that individuate a device the most, in priority order. */
    private val STRONG_CATEGORIES = listOf(
        SignalCategory.GPU,
        SignalCategory.SENSORS,
        SignalCategory.DEVICE_IDENTITY,
        SignalCategory.CAMERA,
        SignalCategory.FONTS,
        SignalCategory.WEB_VIEW_FINGERPRINT,
        SignalCategory.INSTALLED_APPS,
        SignalCategory.CPU,
    )

    /** Order cards are considered in; the first present ones fill the summary. */
    private val CARD_ORDER = listOf(
        SignalCategory.DEVICE_IDENTITY,
        SignalCategory.LOCALE,
        SignalCategory.STORAGE,
        SignalCategory.NETWORK,
        SignalCategory.LOCAL_NETWORK,
        SignalCategory.GPU,
        SignalCategory.SENSORS,
        SignalCategory.INSTALLED_APPS,
        SignalCategory.SYSTEM_INFO,
        SignalCategory.DISPLAY,
        SignalCategory.CPU,
    )

    fun build(snapshot: Map<SignalCategory, List<FingerprintSignal>>): FingerprintReport {
        val populated = snapshot.filterValues { it.isNotEmpty() }
        val categoriesRead = populated.size
        val totalSignals = populated.values.sumOf { it.size }
        val strongPresent = STRONG_CATEGORIES.count { populated[it]?.isNotEmpty() == true }

        val score = uniquenessScore(categoriesRead, totalSignals, strongPresent)
        val (headline, detail) = headlineFor(score, categoriesRead, totalSignals)
        val cards = buildCards(populated)
        val topSignals = buildTopSignals(populated)

        return FingerprintReport(
            headline = headline,
            detail = detail,
            uniquenessScore = score,
            cards = cards,
            topSignals = topSignals,
        )
    }

    // ---- Scoring ----

    private fun uniquenessScore(categoriesRead: Int, totalSignals: Int, strongPresent: Int): Int {
        if (categoriesRead == 0) return 0
        // Weighted, deterministic, capped. Reading many categories and having the
        // strong side-channel surfaces populated pushes the score up.
        val breadth = (categoriesRead * 4).coerceAtMost(48)
        val depth = (totalSignals * 1).coerceAtMost(24)
        val strength = (strongPresent * 7).coerceAtMost(28)
        return (breadth + depth + strength).coerceIn(0, 100)
    }

    private fun headlineFor(
        score: Int,
        categoriesRead: Int,
        totalSignals: Int,
    ): Pair<String, String> {
        if (categoriesRead == 0) {
            return "Nothing has been read yet." to
                "Open a category or run a full sweep and Mink will show you what your " +
                "phone quietly gives away."
        }
        val where = "Mink read $totalSignals values across $categoriesRead surfaces on this phone."
        return when {
            score >= 70 -> "Your phone is easy to single out." to
                "$where None of them is your name, but together they form a combination " +
                "distinctive enough to recognize this phone again across apps and days."
            score >= 40 -> "Your phone stands out from the crowd." to
                "$where Each reading looks harmless on its own. Combined, they narrow the " +
                "pool of possible devices down toward yours."
            else -> "Your phone blends in, for now." to
                "$where The more surfaces an app reads, the smaller the crowd your phone " +
                "hides in. This is the start of a fingerprint."
        }
    }

    // ---- Cards ----

    private fun buildCards(populated: Map<SignalCategory, List<FingerprintSignal>>): List<NarrativeCard> {
        val cards = mutableListOf<NarrativeCard>()
        for (category in CARD_ORDER) {
            if (cards.size >= 6) break
            val signals = populated[category] ?: continue
            if (signals.isEmpty()) continue
            cards += cardFor(category, signals)
        }
        // Guarantee at least three cards when anything at all was read, padding
        // from whatever other categories are present.
        if (cards.isNotEmpty() && cards.size < 3) {
            for ((category, signals) in populated) {
                if (cards.size >= 3) break
                if (cards.any { it.id == category.id }) continue
                cards += cardFor(category, signals)
            }
        }
        return cards
    }

    private fun cardFor(category: SignalCategory, signals: List<FingerprintSignal>): NarrativeCard {
        val sample = representativeValue(signals)
        val body = when (category) {
            SignalCategory.DEVICE_IDENTITY ->
                "Your phone announces its hardware to any app that asks. $sample"
            SignalCategory.LOCALE ->
                "Your language, region, and time settings narrow the field. $sample"
            SignalCategory.STORAGE ->
                "The size and setup date of your storage volume are surprisingly telling. $sample"
            SignalCategory.NETWORK ->
                "Your network interfaces and VPN state are visible without a prompt. $sample"
            SignalCategory.LOCAL_NETWORK ->
                "The devices on your Wi-Fi are a stable, revealing set that rarely matches " +
                    "another home. $sample"
            SignalCategory.GPU ->
                "Your graphics chip reports a renderer string that acts like a hardware " +
                    "signature. $sample"
            SignalCategory.SENSORS ->
                "The exact lineup of sensors in your phone is a strong model fingerprint. " +
                    "$sample"
            SignalCategory.INSTALLED_APPS ->
                "The mix of apps you have installed hints at your work, money, and habits. " +
                    "$sample"
            SignalCategory.SYSTEM_INFO ->
                "Your build fingerprint and uptime individuate this phone. $sample"
            SignalCategory.DISPLAY ->
                "Your screen size, density, and refresh rate combine into a distinctive " +
                    "profile. $sample"
            SignalCategory.CPU ->
                "Your processor's cores, ABI, and features narrow down the model. $sample"
            else ->
                "${category.subtitle}. $sample"
        }
        return NarrativeCard(
            id = category.id,
            title = category.title,
            body = body.trim(),
            basis = "Read from ${category.title}.",
        )
    }

    /** A short, human sample drawn from the first signal, used inside a card body. */
    private fun representativeValue(signals: List<FingerprintSignal>): String {
        val first = signals.firstOrNull() ?: return ""
        val raw = first.value.ifBlank {
            first.entries?.firstOrNull()?.let { "${it.label}: ${it.value}" }.orEmpty()
        }
        val trimmed = raw.trim().replace('\n', ' ')
        val clipped = if (trimmed.length > 80) trimmed.take(77) + "..." else trimmed
        return if (clipped.isBlank()) "" else "For example, ${first.name.lowercase()} reads $clipped."
    }

    // ---- Top identifying signals ----

    private fun buildTopSignals(
        populated: Map<SignalCategory, List<FingerprintSignal>>,
    ): List<IdentifyingSignal> {
        val out = mutableListOf<IdentifyingSignal>()
        for (category in STRONG_CATEGORIES) {
            if (out.size >= 5) break
            val signals = populated[category] ?: continue
            val signal = signals.firstOrNull() ?: continue
            out += IdentifyingSignal(
                category = category,
                name = signal.name,
                value = signal.value.ifBlank { "${signals.size} readings" },
                why = whyIdentifying(category),
            )
        }
        return out
    }

    private fun whyIdentifying(category: SignalCategory): String = when (category) {
        SignalCategory.GPU ->
            "The GPU renderer string is stable and rare, much like a browser's WebGL hash."
        SignalCategory.SENSORS ->
            "Few phone models share the exact same set of sensors from the exact same vendors."
        SignalCategory.DEVICE_IDENTITY ->
            "The model, build, and stable identifiers point straight at your hardware."
        SignalCategory.CAMERA ->
            "The camera lineup and its optics usually pin down the exact model."
        SignalCategory.FONTS ->
            "The installed font list rarely matches between different phones."
        SignalCategory.WEB_VIEW_FINGERPRINT ->
            "This is the same fingerprint any web page can compute in the background."
        SignalCategory.INSTALLED_APPS ->
            "The combination of apps you have installed is close to unique to you."
        SignalCategory.CPU ->
            "The core layout and CPU features narrow the pool of possible devices."
        SignalCategory.LOCAL_NETWORK ->
            "The mix of devices on your Wi-Fi rarely matches another home."
        else ->
            "This reading helps single your phone out of the crowd."
    }
}
