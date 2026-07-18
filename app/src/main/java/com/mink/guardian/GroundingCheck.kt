package com.mink.guardian

/**
 * The rules engine as an adversarial evaluator of the on-device model. Every
 * user-facing sentence the 1B model writes — a companion remark, the summary
 * read, a chat reply — is checked here against the ground truth it was given
 * before it is committed as that surface's text. If the model states a concrete
 * fact the ground truth does not support — an invented number, or a proper-noun
 * app or setting name it was never shown — the output is rejected whole and the
 * caller falls back to the deterministic text it already has. In a privacy tool
 * where the whole promise is trustworthy claims, the model must never be able to
 * commit a fabricated figure, nor — on the surfaces that check entities — the
 * wrong app's name.
 *
 * When the check runs relative to the screen differs by surface, and the caller
 * chooses which claims to check. The companion remark and the summary read are
 * computed in full and only shown if they pass, so there the check genuinely
 * precedes display, and both check numbers AND entities against tight ground
 * truth. The chat reply STREAMS token by token for a live feel and ranges over
 * the whole snapshot, so it checks numbers only (see [ungroundedClaims]) and its
 * check runs once the last token lands: a reply that fails is replaced in the
 * observed chat log by the deterministic answer (see [GuardianController.chat]).
 * Chat therefore shows a provisional draft and corrects it rather than gating
 * before the first token — the corrected [GuardianController.chatLog] is the
 * authoritative record.
 *
 * Pure, deterministic, Android-free — unit-testable with no device, like
 * [Baseline] and the post-processors it backs up. The model has no part in
 * judging itself (the capability floor).
 *
 * Design bias: **rejection precision over coverage.** The dominant failure to
 * avoid is a false reject — discarding good, grounded prose because a legitimate
 * token was not recognised, which would silently degrade a model surface to its
 * fallback and waste the on-device model. So the check only fires on
 * high-confidence hallucinations:
 *  - a **standalone** digit-form number that appears nowhere in the ground truth
 *    (a fabricated count, percentage, size or year is a checkable lie). Digit
 *    runs glued inside a token — "24/7", "2FA", "AES-256", "IPv6", "10:30" — are
 *    idiom or identifiers, not factual figures, and are not treated as numbers.
 *  - a capitalised (or internally-capitalised, "iMessage"-shaped) proper-noun
 *    word that is neither ordinary vocabulary (a broad stoplist of function
 *    words, common sentence openers, tech terms and units) nor present — after
 *    light singularisation — in the ground truth (a swapped-in app or setting
 *    name).
 * Spelled-out numbers ("two or three"), ordinary words, second-person phrasing
 * and the persona's own vocabulary are all tolerated. What it deliberately does
 * NOT bridge, and so will reject to the (graceful) fallback: a morphological
 * variant the singulariser cannot map back — a demonym like "German" for a
 * grounded "Germany". Under the precision-first bias that is an accepted loss,
 * since the deterministic fallback still names the place correctly. The classes
 * it does not check at all — relationships, tone, grounded-but-misleading
 * claims — are out of scope and left to the persona and the fallback.
 */
object GroundingCheck {

    /**
     * The supported facts distilled from ground-truth text: the set of numbers
     * mentioned, and the word vocabulary. [vocab] holds each ground-truth word
     * lowercased AND its singularised [stem], so a plural in the facts grounds a
     * singular in the output and vice-versa. A claim is grounded iff it traces to
     * one of these.
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
                if (w.length >= MIN_TOKEN_LEN) {
                    vocab += w
                    vocab += stem(w)
                }
            }
        }
        return GroundingFacts(numbers, vocab)
    }

    /** Whether every concrete claim in [text] is supported by [facts]. */
    fun isGrounded(
        text: String,
        facts: GroundingFacts,
        checkEntities: Boolean = true,
        skipSentenceInitial: Boolean = false,
    ): Boolean = ungroundedClaims(text, facts, checkEntities, skipSentenceInitial).isEmpty()

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
     *
     * [skipSentenceInitial] exempts a plain-capitalised word that merely opens a
     * sentence (grammatical capitalisation, not a proper noun). The multi-sentence
     * read sets it — its named entities are all in its facts, so the cost of
     * missing a sentence-opening name is far below the cost of rejecting every
     * ordinary opener ("None", "Overall", "Around"). The terse one-line remark
     * leaves it off, because there the app/setting name is usually the sentence's
     * first word and must still be checked. An internally-capitalised token
     * ("iMessage") is never exempted this way — a mere opener never has that shape.
     */
    fun ungroundedClaims(
        text: String,
        facts: GroundingFacts,
        checkEntities: Boolean,
        skipSentenceInitial: Boolean = false,
    ): List<String> {
        val claims = mutableListOf<String>()
        NUMBER.findAll(text).forEach { m ->
            val n = parseNumber(m.value) ?: return@forEach
            if (facts.numbers.none { supports(it, n) }) claims += m.value
        }
        if (checkEntities) {
            WORD.findAll(text).forEach { m ->
                val raw = m.value
                if (!isProperNounCandidate(raw)) return@forEach   // proper-noun candidates only
                if (skipSentenceInitial && !hasInternalCaps(raw) &&
                    isSentenceInitial(text, m.range.first)
                ) {
                    return@forEach
                }
                val lower = raw.lowercase()
                // Two forms are matched: the exact lowercased token (so a contraction
                // like "Don't" is grounded by its stoplist entry, and an exact vocab
                // word matches) and the [stem] — which drops any apostrophe tail and a
                // plural marker ("Weather's" -> "weather", "Cameras" -> "camera"). The
                // vocab stores both the raw and stemmed forms of each ground-truth
                // word, so a supported term grounds its possessive, plural or singular
                // in either direction.
                val stem = stem(lower)
                if (lower in STOPLIST || stem in STOPLIST) return@forEach
                if (lower in facts.vocab || stem in facts.vocab) return@forEach
                claims += raw
            }
        }
        return claims
    }

    /**
     * A token worth checking as a proper-noun claim: it starts with a capital (an
     * ordinary proper noun), or it carries internal capitalisation ("iMessage",
     * "eBay", "iOS"). The internal-caps case matters because a first-letter-only
     * test misses a swapped-in lowercase-initial brand name, letting it slip
     * through the entity check.
     */
    private fun isProperNounCandidate(raw: String): Boolean {
        val first = raw.firstOrNull() ?: return false
        return first.isUpperCase() || hasInternalCaps(raw)
    }

    /** An uppercase letter anywhere after the first ("iMessage", "eBay", "iOS"). */
    private fun hasInternalCaps(raw: String): Boolean = raw.drop(1).any { it.isUpperCase() }

    /**
     * Whether the token starting at [start] opens a sentence: it is the first word
     * of [text], or the nearest character before it — skipping whitespace and any
     * opening quote or bracket — is a sentence terminator (`.`, `!`, `?`).
     */
    private fun isSentenceInitial(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0) {
            val c = text[i]
            if (c.isWhitespace() || c in LEADING_SKIP) {
                i--
                continue
            }
            return c == '.' || c == '!' || c == '?'
        }
        return true
    }

    /** Opening quotes and brackets skipped when looking back for a sentence start. */
    private val LEADING_SKIP = setOf('"', '\'', '‘', '’', '“', '”', '(', '[')

    /**
     * Light singularisation for cross-form matching: drop a possessive/contraction
     * tail, then a plural marker. Enough to bridge "Cameras"↔"camera",
     * "Addresses"↔"address" and "Watches"↔"watch"; not a real stemmer. Roots that
     * end in "ss" (access, class) and short words are left alone, and it makes no
     * attempt at irregular or derivational forms (a demonym like "German" does not
     * reduce to "Germany" — that is the documented fallback case).
     */
    private fun stem(word: String): String {
        val s = word.substringBefore('\'')
        return when {
            s.length <= MIN_TOKEN_LEN -> s
            s.endsWith("ies") && s.length > 4 -> s.dropLast(3) + "y"
            s.endsWith("ses") || s.endsWith("xes") || s.endsWith("zes") ||
                s.endsWith("ches") || s.endsWith("shes") -> s.dropLast(2)
            s.endsWith("ss") -> s
            s.endsWith("s") && s.length > 3 -> s.dropLast(1)
            else -> s
        }
    }

    /**
     * Two numbers match when equal to a small tolerance — so "40" grounds "40.0"
     * and float noise never causes a spurious reject. Deliberately exact
     * otherwise: a privacy claim's figure must be the real one, not merely close.
     * For integers beyond a Double's exact range (~16 digits) two distinct values
     * can round together and compare equal; these surfaces deal in small counts,
     * scores and sizes, so that bound is documented rather than mitigated.
     */
    private fun supports(fact: Double, claim: Double): Boolean = kotlin.math.abs(fact - claim) < EPSILON

    private fun parseNumber(token: String): Double? = token.replace(",", "").toDoubleOrNull()

    /**
     * A **standalone** digit-form number: an integer or decimal (thousands
     * separators allowed), that is not glued to letters or into a compound token.
     * The lookbehind/lookahead reject a digit run touching a letter or a `/ : . -`
     * on either side, so "24/7", "2FA", "AES-256", "IPv6", "5G" and a clock
     * "10:30" are not mistaken for factual figures, while "40%", "1,024", "3.5"
     * and a bare "73" still match. The tail is anchored on a digit so a trailing
     * thousands-separator comma ("73, which…") is not swallowed into the token.
     */
    private val NUMBER = Regex("""(?<![A-Za-z0-9/:.\-])\d(?:[\d,]*\d)?(?:\.\d+)?(?![A-Za-z0-9/:\-])""")

    /** A word token: letters/digits, hyphens and apostrophes kept inside. */
    private val WORD = Regex("""[A-Za-z][A-Za-z0-9]*(?:[-'][A-Za-z0-9]+)*""")

    private const val MIN_TOKEN_LEN = 2
    private const val EPSILON = 1e-9

    /**
     * Words a capitalised token may be without being treated as a proper-noun
     * claim: function words and common sentence openers (so an ordinary
     * capitalised opener — "None", "Overall", "Together" — does not flag), the
     * persona's own name, and generic tech terms and units (vocabulary, not
     * app/setting names — the entity check is for a swapped-in *product* name,
     * not "Bluetooth" or "GB"). Kept broad on purpose: a false reject is worse
     * than a missed edge case, and stoplisting an ordinary word only ever makes
     * the check more lenient. It is not exhaustive — a capitalised opener not
     * listed here is still treated as a candidate and, if unsupported, rejected
     * to the fallback.
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
        // Common contractions (matched on the exact lowercased form, since the "n't"
        // family does not reduce to a stoplisted stem).
        addAll(
            listOf(
                "don't", "doesn't", "didn't", "won't", "can't", "isn't", "aren't", "wasn't",
                "weren't", "hasn't", "haven't", "hadn't", "wouldn't", "shouldn't", "couldn't",
                "you're", "they're", "we're", "i'm", "it's", "that's", "there's", "here's",
                "what's", "let's", "i've", "you've", "we've", "they've", "i'll", "you'll",
                "we'll", "they'll", "i'd", "you'd", "he's", "she's", "who's",
            ),
        )
        // Common capitalised sentence openers a warm, factual read uses: indefinite
        // pronouns and transitional/summarising adverbs. These are ordinary
        // vocabulary, never product names, so exempting them only prevents false
        // rejects (a swapped-in app name is not among them).
        addAll(
            listOf(
                "none", "nothing", "nobody", "no-one", "noone", "someone", "somebody", "anyone",
                "anybody", "everyone", "everybody", "everything", "something", "anything", "overall",
                "together", "altogether", "however", "meanwhile", "moreover", "furthermore", "therefore",
                "instead", "otherwise", "besides", "additionally", "finally", "ultimately", "essentially",
                "basically", "currently", "recently", "lately", "already", "perhaps", "maybe", "likely",
                "notably", "importantly", "overall", "using", "used", "combined", "given", "seen", "taken",
                "based", "several", "plenty", "either", "neither", "although", "though", "despite",
                "across", "between", "among", "within", "throughout", "during", "before", "after",
            ),
        )
        // The persona.
        addAll(listOf("mink"))
        // Generic tech terms, units and common acronyms — vocabulary, not product names.
        addAll(
            listOf(
                "wi-fi", "wifi", "bluetooth", "gps", "usb", "sim", "vpn", "dns", "ip", "os", "id",
                "app", "apps", "gb", "mb", "kb", "tb", "ghz", "mhz", "hz", "am", "pm", "us", "uk",
                "sms", "nfc", "led", "cpu", "gpu", "ram", "url", "http", "https", "ok",
                "mac", "imei", "ssid", "gsm", "lte", "oem", "meid", "iccid", "imsi", "api", "sdk",
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
