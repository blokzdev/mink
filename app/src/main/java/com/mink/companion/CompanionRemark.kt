package com.mink.companion

import com.mink.guardian.AlertLevel
import com.mink.guardian.DATA_USE_CATEGORY
import com.mink.guardian.GuardianAlert
import com.mink.guardian.HIGH_RISK_CATEGORY
import com.mink.guardian.SENSOR_USE_CATEGORY
import com.mink.guardian.llm.MiniCpmChatFormat

/**
 * Pure text logic for the companion's spoken remarks: rules pick the mood, the
 * model only writes the sentence, and every raw model reply is scrubbed down to
 * one calm line before it can reach the bubble. Nothing here touches Android or
 * the model directly, so all of it is unit-testable with no device.
 *
 * The invariant this file guards: the LLM never drives a decision or a mood. It
 * is handed a fully-formed prompt and its output is treated as untrusted text.
 */
object CompanionRemark {

    /**
     * The longest a spoken remark may be. Tunable copy-budget, not a lane-5
     * immutable — the sprite carries alarm by posture, so the line stays short.
     */
    const val REMARK_MAX_CHARS = 110

    /**
     * How many tokens the model may spend on one remark. Tunable latency budget,
     * not a lane-5 immutable — a single fact needs no more.
     */
    const val REMARK_MAX_TOKENS = 48

    /**
     * The mood the sprite shows for an alert, decided by rules alone. Critical
     * findings and the sharper warning families read as attentive; softer
     * findings read as curious; informational findings leave the sprite idle.
     */
    fun moodForAlert(level: AlertLevel, categoryId: String?): CompanionMood = when (level) {
        AlertLevel.CRITICAL -> CompanionMood.ALERT
        AlertLevel.WARNING ->
            if (categoryId == SENSOR_USE_CATEGORY ||
                categoryId == HIGH_RISK_CATEGORY ||
                categoryId == DATA_USE_CATEGORY
            ) {
                CompanionMood.ALERT
            } else {
                CompanionMood.CURIOUS
            }
        AlertLevel.SUGGESTION -> CompanionMood.CURIOUS
        AlertLevel.INFO -> CompanionMood.IDLE
    }

    /**
     * Build the calm-bystander narration prompt for [alert]: a system persona,
     * two Event -> Remark few-shots, and the alert filled into the template. The
     * prompt runs in no-think mode and ends on an open assistant turn, so the
     * model continues with the one-line remark.
     */
    fun buildRemarkPrompt(alert: GuardianAlert): String {
        val userMessage = buildString {
            append(FEW_SHOTS)
            append("\n\n")
            append("Event: ${alert.title}. Detail: ${alert.body}\nRemark:")
        }
        return MiniCpmChatFormat.buildPrompt(
            systemPrompt = REMARK_SYSTEM,
            userMessage = userMessage,
            enableThinking = false,
        )
    }

    /**
     * Reduce a raw model reply to one short, quotable sentence. A 1B model can
     * never be trusted to self-limit, so the cleanup is deterministic: strip any
     * hidden reasoning (leading or inline), keep the first line, drop an echoed
     * label and surrounding quotes, remove inline markdown, cut at the first real
     * sentence terminator, collapse whitespace, and cap the length on a word
     * boundary. Returns "" when nothing usable remains.
     */
    fun postProcessRemark(raw: String): String {
        // Separate out any leading <think> block first; only the visible reply matters.
        var text = MiniCpmChatFormat.parseReply(raw).content
        // Keep only the first line the model produced.
        text = text.substringBefore('\n')
        // Remove any inline <think>...</think> spans the leading-only parse missed.
        text = stripThinkSpans(text)
        // Drop an echoed "Remark:"/"Reply:" label from the template.
        text = stripLeadingLabel(text)
        // Strip quotes or backticks the model wrapped the sentence in.
        text = stripWrappingQuotes(text)
        // Strip inline markdown the model may have wrapped around words.
        text = stripInlineMarkdown(text)
        // Cut at the first real sentence terminator, keeping the terminator.
        text = cutAtFirstTerminator(text)
        // Collapse any remaining runs of whitespace.
        text = WHITESPACE.replace(text, " ").trim()
        if (text.isEmpty()) return ""
        return capLength(text)
    }

    private val WHITESPACE = Regex("\\s+")

    /** A full <think>…</think> span, matched non-greedily across lines. */
    private val THINK_SPAN = Regex("(?is)<think>.*?</think>")

    /** A lone <think> or </think> tag left over after spans are removed. */
    private val THINK_TAG = Regex("(?i)</?think>")

    /** A lone opening <think> tag that never closed, matched case-insensitively. */
    private val THINK_OPEN_TAG = Regex("(?i)<think>")

    /** A markdown list, block-quote, or heading marker at the very start of the line. */
    private val LEADING_LIST_MARKER = Regex("^\\s*([-*>]|#+)\\s+")

    private val LEADING_LABELS = listOf("remark:", "reply:")

    private val QUOTE_CHARS = setOf(
        '"', '\'', '`', '“', '”', '‘', '’',
    )

    private fun stripLeadingLabel(input: String): String {
        val trimmed = input.trimStart()
        for (label in LEADING_LABELS) {
            if (trimmed.length >= label.length &&
                trimmed.substring(0, label.length).lowercase() == label
            ) {
                return trimmed.substring(label.length).trimStart()
            }
        }
        return trimmed
    }

    private fun stripWrappingQuotes(input: String): String {
        var text = input.trim()
        while (text.isNotEmpty() && text.first() in QUOTE_CHARS) {
            text = text.substring(1)
        }
        while (text.isNotEmpty() && text.last() in QUOTE_CHARS) {
            text = text.dropLast(1)
        }
        return text.trim()
    }

    /**
     * Remove any inline reasoning: full <think>…</think> spans first, then the
     * tail of an unterminated <think> that never closed, then any stray lone tag
     * left behind. parseReply only strips a leading block, so inline reasoning
     * would otherwise leak straight into the bubble.
     */
    private fun stripThinkSpans(input: String): String {
        // Remove closed <think>…</think> spans first.
        val withoutSpans = THINK_SPAN.replace(input, "")
        // An unterminated <think> (the model hit the token cap mid-reasoning, with
        // no closing tag) leaves its reasoning tail behind; drop everything from
        // that lone opening tag to end-of-text.
        val openTag = THINK_OPEN_TAG.find(withoutSpans)
        val withoutTail =
            if (openTag != null) withoutSpans.substring(0, openTag.range.first) else withoutSpans
        // Remove any stray lone tag left behind.
        return THINK_TAG.replace(withoutTail, "")
    }

    /**
     * Strip the small set of inline markdown a model may add: a leading list,
     * block-quote, or heading marker, `**`/`__` emphasis markers, and code
     * backticks. Only the
     * marker characters are removed; the words inside are kept. Deliberately not a
     * markdown parser — a fixed set of deterministic replacements.
     */
    private fun stripInlineMarkdown(input: String): String {
        var text = LEADING_LIST_MARKER.replace(input, "")
        text = text.replace("**", "").replace("__", "")
        text = text.replace("`", "")
        return text
    }

    /**
     * Cut at the first real sentence boundary, keeping the terminator. A `.`, `!`,
     * or `?` only ends a sentence when it sits at end-of-string, or is followed by
     * whitespace and then an uppercase letter that opens the next sentence. That
     * leaves abbreviation and decimal periods intact — "approx. 3 times", "U.S.
     * app", "3.5 GB" all keep flowing — and only splits where a new sentence
     * genuinely begins. Returns the input unchanged when nothing qualifies.
     */
    private fun cutAtFirstTerminator(input: String): String {
        for (i in input.indices) {
            val c = input[i]
            if (c != '.' && c != '!' && c != '?') continue
            if (i == input.lastIndex) return input.substring(0, i + 1)
            if (input[i + 1].isWhitespace()) {
                val next = firstNonWhitespaceAfter(input, i + 1)
                if (next != null && next.isUpperCase()) {
                    return input.substring(0, i + 1)
                }
            }
        }
        return input
    }

    private fun firstNonWhitespaceAfter(input: String, from: Int): Char? {
        var j = from
        while (j < input.length && input[j].isWhitespace()) j++
        return if (j < input.length) input[j] else null
    }

    private fun capLength(input: String): String {
        if (input.length <= REMARK_MAX_CHARS) return input
        // Leave room for the ellipsis, then back off to the last word boundary.
        val slice = input.substring(0, REMARK_MAX_CHARS - 1)
        val boundary = slice.lastIndexOf(' ')
        val head = if (boundary > 0) slice.substring(0, boundary) else slice
        return head.trimEnd() + "…"
    }

    private const val REMARK_SYSTEM =
        "You are Mink, a small on-device privacy companion. You are a calm bystander telling your " +
            "owner, in plain second person, about one thing their phone just did. State the concrete " +
            "fact — the app or setting and what it touched. One short sentence. No advice, no " +
            "questions, no alarm, no first-person as an app, no markdown, no quotes. Output only the " +
            "sentence."

    private val FEW_SHOTS = buildString {
        append("Event: Weather gained camera access. ")
        append("Detail: The Weather app can now use the camera.\n")
        append("Remark: Weather can now reach your camera.\n\n")
        append("Event: Microphone in use. ")
        append("Detail: A voice recorder started using the microphone.\n")
        append("Remark: A voice recorder just started using your microphone.")
    }
}
