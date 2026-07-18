package com.mink.guardian

/**
 * The rules engine as an adversarial evaluator of the on-device model. Every
 * user-facing sentence the 1B model writes — a companion remark, the summary
 * read, a chat reply — is checked here against the ground truth it was given
 * BEFORE it can reach the screen. If the model states a concrete fact that the
 * ground truth does not support — an invented number, or a proper-noun app or
 * setting name it was never shown — the output is rejected whole and the caller
 * falls back to the deterministic text it already has. In a privacy tool where
 * the whole promise is trustworthy claims, the model must never be able to show
 * the user a fabricated figure or the wrong app's name.
 *
 * Pure, deterministic, Android-free — unit-testable with no device, like
 * [Baseline] and the post-processors it backs up. The model has no part in
 * judging itself (the capability floor).
 *
 * Design bias: **rejection precision over coverage.** The dominant failure to
 * avoid is a false reject — discarding good, grounded prose because a legitimate
 * token was not recognised, which would silently degrade every model surface to
 * its fallback and waste the on-device model. So the check only fires on
 * unambiguous, high-confidence hallucinations:
 *  - a digit-form number that appears nowhere in the ground truth (a fabricated
 *    count, percentage, size or year is an unambiguous, checkable lie);
 *  - a capitalised proper-noun-like word that is neither ordinary vocabulary
 *    (a broad stoplist of function words, tech terms and units) nor present in
 *    the ground truth (a swapped-in app or setting name).
 * Spelled-out numbers ("two or three"), ordinary words, second-person phrasing
 * and the persona's own vocabulary are all tolerated. Anything ambiguous passes.
 * The classes it does NOT check — relationships, tone, grounded-but-misleading
 * claims — are out of scope and left to the persona and the fallback.
 */
object GroundingCheck {

    /**
     * The supported facts distilled from ground-truth text: the set of numbers
     * mentioned, and the lowercased word vocabulary. A claim is grounded iff it
     * traces to one of these.
     */
    data class GroundingFacts(val numbers: Set<Double>, val vocab: Set<String>)

    /** Build [GroundingFacts] from one or more ground-truth strings. */
    fun factsOf(vararg sources: String): GroundingFacts = factsOf(sources.asIterable())

    fun factsOf(sources: Iterable<String>): GroundingFacts {
        val numbers = HashSet<Double>()
        val vocab = HashSet<String>()
        for (source in sources) {
            NUMBER.findAll(source).forEach { m -> parseNumber(m.value)?.let { numbers += it } }
            WORD.findAll(source).forEach { m ->
                val w = m.value.lowercase()
                if (w.length >= MIN_TOKEN_LEN) vocab += w
            }
        }
        return GroundingFacts(numbers, vocab)
    }

    /** Whether every concrete claim in [text] is supported by [facts]. */
    fun isGrounded(text: String, facts: GroundingFacts, checkEntities: Boolean = true): Boolean =
        ungroundedClaims(text, facts, checkEntities).isEmpty()

    /**
     * The concrete claims in [text] that [facts] does not support, most useful
     * for tests and telemetry. A non-empty result means [text] should be rejected
     * in favour of the deterministic fallback.
     *
     * Numbers are always checked. Entities are checked only when [checkEntities]
     * is true — appropriate where the ground truth is a tight, known set (a single
     * alert, or the exact fact list fed to the model), not for open-ended ground
     * truth (chat over the whole snapshot), where a proper-noun check would be
     * both low-value and false-positive-prone.
     */
    fun ungroundedClaims(text: String, facts: GroundingFacts, checkEntities: Boolean): List<String> {
        val claims = mutableListOf<String>()
        NUMBER.findAll(text).forEach { m ->
            val n = parseNumber(m.value) ?: return@forEach
            if (facts.numbers.none { supports(it, n) }) claims += m.value
        }
        if (checkEntities) {
            WORD.findAll(text).forEach { m ->
                val raw = m.value
                if (raw.firstOrNull()?.isUpperCase() != true) return@forEach   // proper-noun candidates only
                val lower = raw.lowercase()
                // The base is the word before any apostrophe with a trailing plural
                // 's' dropped: "Weather's" -> "weather", "Cameras" -> "camera". That
                // grounds possessives and plurals of a supported term. Contractions
                // ("You're", "Don't") are covered by the contraction stoplist.
                val base = lower.substringBefore('\'').trimEnd('s')
                if (lower in STOPLIST || base in STOPLIST) return@forEach
                if (lower in facts.vocab || base in facts.vocab) return@forEach
                claims += raw
            }
        }
        return claims
    }

    /**
     * Two numbers match when equal to a small tolerance — so "40" grounds "40.0"
     * and float noise never causes a spurious reject. Deliberately exact
     * otherwise: a privacy claim's figure must be the real one, not merely close.
     */
    private fun supports(fact: Double, claim: Double): Boolean = kotlin.math.abs(fact - claim) < EPSILON

    private fun parseNumber(token: String): Double? = token.replace(",", "").toDoubleOrNull()

    /** A digit-form number: an integer or decimal, thousands separators allowed. */
    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")

    /** A word token: letters/digits, hyphens and apostrophes kept inside. */
    private val WORD = Regex("""[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*""")

    private const val MIN_TOKEN_LEN = 2
    private const val EPSILON = 1e-9

    /**
     * Words a capitalised token may be without being treated as a proper-noun
     * claim: sentence openers and function words (so an ordinary capital at the
     * start of a sentence never flags), the persona's own name, and generic tech
     * terms and units (which are vocabulary, not app/setting names — the entity
     * check is for a swapped-in *product* name, not "Bluetooth" or "GB"). Kept
     * broad on purpose: a false reject is worse than a missed edge case.
     */
    private val STOPLIST: Set<String> = buildSet {
        // Pronouns, articles, determiners, common openers.
        addAll(
            listOf(
                "a", "an", "the", "i", "it", "its", "it's", "you", "your", "yours", "we", "our", "ours",
                "they", "their", "theirs", "them", "he", "she", "his", "her", "this", "that", "these",
                "those", "there", "here", "what", "which", "who", "whom", "whose", "when", "where", "why",
                "how", "and", "but", "or", "nor", "so", "yet", "for", "if", "then", "than", "as", "because",
                "since", "while", "with", "without", "of", "on", "in", "at", "to", "from", "by", "up",
                "down", "out", "off", "over", "under", "again", "once", "also", "just", "now", "not", "no",
                "yes", "can", "cannot", "could", "will", "would", "should", "may", "might", "must", "do",
                "does", "did", "is", "are", "was", "were", "be", "been", "being", "has", "have", "had",
                "one", "two", "three", "many", "some", "any", "each", "every", "all", "both", "few", "more",
                "most", "other", "another", "such", "only", "own", "same", "too", "very", "still", "about",
            ),
        )
        // Common contractions (the base-word check misses the "n't" family).
        addAll(
            listOf(
                "don't", "doesn't", "didn't", "won't", "can't", "isn't", "aren't", "wasn't",
                "weren't", "hasn't", "haven't", "hadn't", "wouldn't", "shouldn't", "couldn't",
                "you're", "they're", "we're", "i'm", "it's", "that's", "there's", "here's",
                "what's", "let's", "i've", "you've", "we've", "they've", "i'll", "you'll",
                "we'll", "they'll", "i'd", "you'd", "he's", "she's", "who's",
            ),
        )
        // The persona.
        addAll(listOf("mink"))
        // Generic tech terms and units — vocabulary, not product names.
        addAll(
            listOf(
                "wi-fi", "wifi", "bluetooth", "gps", "usb", "sim", "vpn", "dns", "ip", "os", "id",
                "app", "apps", "gb", "mb", "kb", "tb", "ghz", "mhz", "hz", "am", "pm", "us", "uk",
                "sms", "nfc", "led", "cpu", "gpu", "ram", "url", "http", "https", "ok",
            ),
        )
        // Days and months (a capital opener that is not a product name).
        addAll(
            listOf(
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
                "january", "february", "march", "april", "may", "june", "july", "august",
                "september", "october", "november", "december", "today", "tomorrow", "yesterday",
            ),
        )
    }
}
