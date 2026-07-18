package com.mink.narrative

import com.mink.guardian.llm.MiniCpmChatFormat

/**
 * Pure text logic for Mink's model-authored read of the fingerprint summary. The
 * deterministic [FingerprintReport] and the [StoryCard]s stay the grounded
 * backbone; this file only turns them into a compact fact list, wraps that in a
 * MiniCPM chat prompt, and scrubs the raw reply back down to a calm paragraph.
 * Nothing here touches Android or the model directly, so all of it is
 * unit-testable with no device.
 *
 * The invariant this file guards (the capability floor from the memory ADR): the
 * model only writes prose. It is fed ONLY real facts and told not to invent any
 * value, app, or number, and its output is treated as untrusted text. The
 * deterministic report remains shown alongside and is the fallback.
 */
object SummaryNarration {

    /**
     * The longest a narrated read may be. Tunable copy-budget, not a lane-5
     * immutable — two or three short sentences fit well inside this.
     */
    const val NARRATION_MAX_CHARS = 420

    /**
     * How many tokens the model may spend on one read. Tunable latency budget,
     * not a lane-5 immutable. Sized to the [NARRATION_MAX_CHARS] display cap:
     * ~420 characters is only ~110-120 tokens of English, so anything past this
     * would be generated only to be trimmed away — wasted latency on a slow
     * device. A few grounded sentences fit comfortably inside it.
     */
    const val NARRATION_MAX_TOKENS = 128

    /**
     * Build the compact, grounded fact list handed to the model, one item per
     * line. Only real values go in: the recognizability score, the categories of
     * the most-identifying surfaces with why each matters, and the already-grounded
     * story sentences. Raw signal values are deliberately left out, so there is
     * little for the model to fabricate.
     */
    fun buildNarrationFacts(report: FingerprintReport, story: List<StoryCard>): String {
        val lines = buildList {
            add("Recognizability: ${report.uniquenessScore} out of 100.")
            report.topSignals.take(4).forEach { signal ->
                add("${signal.category.title}: ${signal.why}")
            }
            story.take(4).forEach { card ->
                val body = card.body.trim()
                if (body.isNotEmpty()) add(body)
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Build the full MiniCPM chat prompt for one read: the calm-guardian persona,
     * the grounded facts from [buildNarrationFacts], and an instruction to write
     * the read. The prompt runs in no-think mode and ends on an open assistant
     * turn, so the model continues with the paragraph.
     */
    fun buildNarrationPrompt(report: FingerprintReport, story: List<StoryCard>): String {
        val userMessage =
            "Here is what I read from this phone:\n" +
                "${buildNarrationFacts(report, story)}\n\nWrite the read:"
        return MiniCpmChatFormat.buildPrompt(
            systemPrompt = NARRATION_SYSTEM,
            userMessage = userMessage,
            enableThinking = false,
        )
    }

    /**
     * Reduce a raw model reply to a calm, multi-sentence paragraph. A 1B model can
     * never be trusted to self-limit, so the cleanup is deterministic: strip any
     * hidden reasoning (leading or inline), drop per-line markdown and list
     * markers, collapse blank lines and newlines into single spaces, and cap the
     * length on a sentence or word boundary with an ellipsis. Unlike the one-line
     * companion remark, the whole paragraph is kept — it is not cut at the first
     * sentence terminator. Returns "" when nothing usable remains.
     */
    fun postProcessNarration(raw: String): String {
        // Separate out any leading <think> block first; only the visible reply matters.
        var text = MiniCpmChatFormat.parseReply(raw).content
        // Remove any inline <think>...</think> spans the leading-only parse missed.
        text = stripThinkSpans(text)
        // Clean each line: drop a leading list/quote marker and inline markdown.
        text = text.lineSequence()
            .joinToString("\n") { stripInlineMarkdown(it) }
        // Collapse blank lines and newlines into single spaces so the read is one paragraph.
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

    /**
     * Remove any inline reasoning: full <think>…</think> spans first, then the
     * tail of an unterminated <think> that never closed, then any stray lone tag
     * left behind. parseReply only strips a leading block, so inline reasoning
     * would otherwise leak straight into the read.
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
     * Cap the read at [NARRATION_MAX_CHARS], leaving room for the ellipsis and
     * backing off to a boundary. A late sentence terminator (past the halfway
     * point) is preferred so a whole sentence survives; otherwise it falls back to
     * the last word boundary. The ellipsis marks that more was cut.
     */
    private fun capLength(input: String): String {
        if (input.length <= NARRATION_MAX_CHARS) return input
        val slice = input.substring(0, NARRATION_MAX_CHARS - 1)
        val sentenceEnd = slice.lastIndexOfAny(TERMINATORS)
        if (sentenceEnd >= NARRATION_MAX_CHARS / 2) {
            return slice.substring(0, sentenceEnd + 1).trimEnd() + "…"
        }
        val boundary = slice.lastIndexOf(' ')
        val head = if (boundary > 0) slice.substring(0, boundary) else slice
        return head.trimEnd() + "…"
    }

    /** Sentence terminators used to prefer a clean cut in [capLength]. */
    private val TERMINATORS = charArrayOf('.', '!', '?')

    private const val NARRATION_SYSTEM =
        "You are Mink, a calm on-device privacy guardian. In two or three short sentences, tell the " +
            "owner in plain second person what these readings add up to and why the phone is " +
            "recognizable. Use only the facts given — never invent an app, a value, or a number. No " +
            "markdown, no lists, no headings, no first-person-as-an-app beyond 'I'. Warm and factual, " +
            "never alarmist."
}
