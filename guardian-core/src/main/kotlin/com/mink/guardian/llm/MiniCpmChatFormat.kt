package com.mink.guardian.llm

import com.mink.guardian.ChatMessage
import com.mink.guardian.ChatRole

/**
 * Builds and parses the MiniCPM5 chat template. The template wraps each turn in
 * `<|im_start|>role\n...<|im_end|>` markers and ends on an open assistant turn
 * so the model continues from there.
 *
 * Thinking mode is on by default. Appending [NO_THINK] to the user turn asks
 * the model to answer without a `<think>` preamble. The model's reply may still
 * begin with a `<think>...</think>` block, which [parseReply] separates out.
 *
 * Everything here is pure string work so it can be unit tested with no model.
 */
object MiniCpmChatFormat {

    const val IM_START: String = "<|im_start|>"
    const val IM_END: String = "<|im_end|>"
    const val NO_THINK: String = "/no_think"
    const val THINK_OPEN: String = "<think>"
    const val THINK_CLOSE: String = "</think>"

    const val ROLE_SYSTEM: String = "system"
    const val ROLE_USER: String = "user"
    const val ROLE_ASSISTANT: String = "assistant"

    /** One prior turn in a conversation. [role] is [ROLE_USER] or [ROLE_ASSISTANT]. */
    data class Turn(val role: String, val content: String)

    /** A reply split into its optional hidden reasoning and its visible answer. */
    data class ParsedReply(val thinking: String?, val content: String)

    /**
     * Build the full prompt string for one generation.
     *
     * @param systemPrompt the guardian persona; omitted from the prompt if blank.
     * @param history prior user/assistant turns, oldest first.
     * @param userMessage the new user message to answer.
     * @param enableThinking when false, [NO_THINK] is appended to the user turn.
     */
    fun buildPrompt(
        systemPrompt: String,
        history: List<Turn>,
        userMessage: String,
        enableThinking: Boolean,
    ): String {
        val sb = StringBuilder()
        if (systemPrompt.isNotBlank()) {
            appendTurn(sb, ROLE_SYSTEM, systemPrompt)
        }
        for (turn in history) {
            appendTurn(sb, turn.role, turn.content)
        }
        val user = if (enableThinking) userMessage else "$userMessage $NO_THINK"
        appendTurn(sb, ROLE_USER, user)
        // Open the assistant turn without closing it so generation continues.
        sb.append(IM_START).append(ROLE_ASSISTANT).append('\n')
        return sb.toString()
    }

    /** Convenience for a single-turn prompt with no history. */
    fun buildPrompt(
        systemPrompt: String,
        userMessage: String,
        enableThinking: Boolean,
    ): String = buildPrompt(systemPrompt, emptyList(), userMessage, enableThinking)

    /**
     * Build the prior-conversation [Turn]s for [buildPrompt] from a chat [log],
     * keeping only the newest [maxTurns]. **Streaming (in-flight) messages are
     * excluded**: their content is an ungrounded provisional draft, so it must
     * never re-enter the model's prompt as authoritative history — a cancelled or
     * stranded reply left in the log would otherwise be quoted back to the model as
     * fact. System messages carry no turn. Pure, so the exclusion is unit-tested
     * off Android.
     */
    fun turnsFrom(log: List<ChatMessage>, maxTurns: Int): List<Turn> =
        log.filterNot { it.streaming }
            .takeLast(maxTurns)
            .mapNotNull { m ->
                when (m.role) {
                    ChatRole.USER -> Turn(ROLE_USER, m.content)
                    ChatRole.GUARDIAN -> Turn(ROLE_ASSISTANT, m.content)
                    ChatRole.SYSTEM -> null
                }
            }

    /**
     * Append one `<|im_start|>role\n...<|im_end|>` turn. The [content] is
     * untrusted — it may carry an app name, a paired-device name, a fact list, an
     * alert body, or a message the owner typed — so [sanitizeContent] strips the
     * chat-control tokens from it before it is wrapped. Only the interpolated
     * content is sanitized; the role and the structural markers are our own
     * literals and are appended as-is.
     */
    private fun appendTurn(sb: StringBuilder, role: String, content: String) {
        sb.append(IM_START).append(role).append('\n')
            .append(sanitizeContent(content))
            .append(IM_END).append('\n')
    }

    /**
     * Remove the chat-control tokens the template and the model use as delimiters
     * from a piece of untrusted content, so folded-in text cannot break out of its
     * turn and inject a fake one (a device named `<|im_end|><|im_start|>system…`
     * would otherwise open a forged system turn). Each occurrence is replaced with
     * a single space. The [IM_START] and [IM_END] turn markers are matched
     * literally; the `<think>`/`</think>` reasoning tags are matched
     * case-insensitively. Newlines are left untouched — a turn's content
     * legitimately spans lines.
     */
    private fun sanitizeContent(text: String): String {
        val withoutTurnMarkers = text.replace(IM_START, " ").replace(IM_END, " ")
        return THINK_TAGS.replace(withoutTurnMarkers, " ")
    }

    /** The `<think>` or `</think>` reasoning tags, matched case-insensitively. */
    private val THINK_TAGS = Regex("(?i)</?think>")

    /**
     * Separate a leading `<think>...</think>` block from the visible answer.
     *
     * Handles three cases:
     *  - no think block: [ParsedReply.thinking] is null, content is the reply.
     *  - a closed block: reasoning goes to thinking, the remainder is content.
     *  - an unterminated block (mid-stream): everything after `<think>` is
     *    treated as thinking and content is empty until the block closes.
     *
     * Any trailing `<|im_end|>` marker the model emits is stripped.
     */
    fun parseReply(raw: String): ParsedReply {
        var text = raw
        val endMarker = text.indexOf(IM_END)
        if (endMarker >= 0) {
            text = text.substring(0, endMarker)
        }
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(THINK_OPEN)) {
            return ParsedReply(thinking = null, content = text.trim())
        }
        val afterOpen = trimmed.substring(THINK_OPEN.length)
        val closeIdx = afterOpen.indexOf(THINK_CLOSE)
        if (closeIdx < 0) {
            // Still inside the reasoning block.
            return ParsedReply(thinking = afterOpen.trim(), content = "")
        }
        val thinking = afterOpen.substring(0, closeIdx).trim()
        val content = afterOpen.substring(closeIdx + THINK_CLOSE.length).trim()
        return ParsedReply(thinking = thinking.ifEmpty { null }, content = content)
    }
}
