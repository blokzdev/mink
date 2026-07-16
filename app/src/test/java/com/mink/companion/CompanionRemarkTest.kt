package com.mink.companion

import com.mink.guardian.APP_ACCESS_CATEGORY
import com.mink.guardian.AlertLevel
import com.mink.guardian.DATA_USE_CATEGORY
import com.mink.guardian.GuardianAlert
import com.mink.guardian.HIGH_RISK_CATEGORY
import com.mink.guardian.SENSOR_USE_CATEGORY
import com.mink.guardian.llm.MiniCpmChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CompanionRemark]: the rules-only mood mapping, the
 * deterministic scrub that reduces an untrusted model reply to one calm line,
 * and the narration prompt that carries the persona, the few-shots, and the
 * alert. No Android and no model, so every branch is asserted with no device.
 */
class CompanionRemarkTest {

    private fun alert(
        id: String = "alert-1",
        level: AlertLevel = AlertLevel.WARNING,
        title: String = "Something changed",
        body: String = "Body.",
        categoryId: String? = "network",
        fromImmutableRule: Boolean = false,
    ): GuardianAlert = GuardianAlert(
        id = id,
        level = level,
        title = title,
        body = body,
        categoryId = categoryId,
        createdAtEpochMs = 0L,
        fromImmutableRule = fromImmutableRule,
    )

    // ---- moodForAlert: rules pick the mood ----

    @Test
    fun moodForAlertMatrix() {
        // CRITICAL always reads as attentive, whatever the category.
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.CRITICAL, categoryId = null))
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.CRITICAL, categoryId = "network"))
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.CRITICAL, SENSOR_USE_CATEGORY))

        // A WARNING in a sharper family reads as attentive too.
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.WARNING, SENSOR_USE_CATEGORY))
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.WARNING, HIGH_RISK_CATEGORY))
        assertEquals(CompanionMood.ALERT, CompanionRemark.moodForAlert(AlertLevel.WARNING, DATA_USE_CATEGORY))

        // Any other WARNING reads as curious, including app-access and no category.
        assertEquals(CompanionMood.CURIOUS, CompanionRemark.moodForAlert(AlertLevel.WARNING, APP_ACCESS_CATEGORY))
        assertEquals(CompanionMood.CURIOUS, CompanionRemark.moodForAlert(AlertLevel.WARNING, "network"))
        assertEquals(CompanionMood.CURIOUS, CompanionRemark.moodForAlert(AlertLevel.WARNING, categoryId = null))

        // Suggestions are curious; the category never sharpens them.
        assertEquals(CompanionMood.CURIOUS, CompanionRemark.moodForAlert(AlertLevel.SUGGESTION, categoryId = null))
        assertEquals(CompanionMood.CURIOUS, CompanionRemark.moodForAlert(AlertLevel.SUGGESTION, SENSOR_USE_CATEGORY))

        // Informational findings leave the sprite idle, whatever the category.
        assertEquals(CompanionMood.IDLE, CompanionRemark.moodForAlert(AlertLevel.INFO, categoryId = null))
        assertEquals(CompanionMood.IDLE, CompanionRemark.moodForAlert(AlertLevel.INFO, HIGH_RISK_CATEGORY))
    }

    // ---- postProcessRemark: the untrusted reply is scrubbed to one line ----

    @Test
    fun stripsHiddenThinkBlock() {
        // The reasoning ends in a terminator that would survive the cut, so
        // stripping the block first is what leaves only the visible sentence.
        val raw = "<think>The Weather app requested camera access.</think>Weather can now reach your camera."
        assertEquals("Weather can now reach your camera.", CompanionRemark.postProcessRemark(raw))
    }

    @Test
    fun keepsOnlyTheFirstLine() {
        // The first line has no terminator, so only the newline can drop the
        // second line — proving the first-line rule, not the terminator cut.
        val raw = "Maps is using your location\nIt also used the microphone."
        assertEquals("Maps is using your location", CompanionRemark.postProcessRemark(raw))
    }

    @Test
    fun stripsLeadingLabel() {
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("Remark: Weather can now reach your camera."),
        )
        assertEquals(
            "A voice recorder is using your microphone.",
            CompanionRemark.postProcessRemark("Reply: A voice recorder is using your microphone."),
        )
    }

    @Test
    fun stripsSurroundingQuotesAndBackticks() {
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("\"Weather can now reach your camera.\""),
        )
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("`Weather can now reach your camera.`"),
        )
    }

    @Test
    fun cutsAtFirstSentenceTerminator() {
        // A trailing second sentence is dropped; the terminator is kept.
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("Weather can now reach your camera. It has not been used yet."),
        )
        // A question mark ends the sentence just the same.
        assertEquals(
            "Something is listening now?",
            CompanionRemark.postProcessRemark("Something is listening now? A second sentence follows."),
        )
    }

    @Test
    fun keepsAbbreviationAndDecimalPeriods() {
        // An abbreviation period followed by a digit is not a sentence end, so the
        // whole line survives rather than being cut to "approx.".
        assertEquals(
            "Maps used location approx. 3 times today.",
            CompanionRemark.postProcessRemark("Maps used location approx. 3 times today."),
        )
        // "U.S." (dotted, no space) and the lowercase word after it both survive:
        // neither dot opens a new sentence, so the line is not cut to "U.".
        assertEquals(
            "U.S. app got contacts.",
            CompanionRemark.postProcessRemark("U.S. app got contacts."),
        )
        // A real boundary — a capitalised next sentence — still cuts the tail off.
        assertEquals("One thing.", CompanionRemark.postProcessRemark("One thing. Two."))
    }

    @Test
    fun stripsInlineThinkBlockMidString() {
        // parseReply only strips a LEADING think block; an inline one must be
        // removed by the scrub before it can leak into the bubble.
        val raw = "Weather <think>keep it short</think>can now reach your camera."
        assertEquals("Weather can now reach your camera.", CompanionRemark.postProcessRemark(raw))
    }

    @Test
    fun dropsUnterminatedInlineThinkTail() {
        // An unterminated inline <think> (the model hit the token cap mid-reasoning,
        // no closing tag) leaves its reasoning tail behind; it is dropped rather than
        // leaking into the bubble.
        val result = CompanionRemark.postProcessRemark("You are recognizable. <think>hmm the score")
        assertEquals("You are recognizable.", result)
        assertFalse(result.contains("hmm"))
    }

    @Test
    fun stripsLeadingHeadingMarker() {
        // A leading markdown heading marker is dropped, keeping the words after it.
        assertEquals(
            "You stand out.",
            CompanionRemark.postProcessRemark("# You stand out."),
        )
    }

    @Test
    fun stripsInlineMarkdown() {
        // Emphasis markers are removed and the inner word is kept.
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("Weather can now **reach** your camera."),
        )
        // Underscore emphasis is stripped the same way.
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("Weather can now __reach__ your camera."),
        )
        // Inline code backticks are removed, keeping the word inside.
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("Weather can now reach your `camera`."),
        )
        // A leading list marker at the very start is dropped.
        assertEquals(
            "Weather can now reach your camera.",
            CompanionRemark.postProcessRemark("- Weather can now reach your camera."),
        )
    }

    @Test
    fun capsAtMaxCharsOnWordBoundaryWithEllipsis() {
        val original =
            "maps quietly reached your camera and then your microphone and then your precise " +
                "location and then your saved contacts list"
        val result = CompanionRemark.postProcessRemark(original)
        assertTrue(result.length <= CompanionRemark.REMARK_MAX_CHARS)
        assertTrue(result.length < original.length)
        assertTrue(result.endsWith("…"))
        // The kept head is a prefix of the original that ends on a word boundary:
        // the very next character in the original is the space we cut on.
        val head = result.dropLast(1)
        assertTrue(original.startsWith(head))
        assertEquals(' ', original[head.length])
    }

    @Test
    fun blankOrGarbageBecomesEmpty() {
        assertEquals("", CompanionRemark.postProcessRemark(""))
        assertEquals("", CompanionRemark.postProcessRemark("   "))
        // Nothing but reasoning, unterminated: no visible reply survives.
        assertEquals("", CompanionRemark.postProcessRemark("<think>just reasoning, no answer"))
        // A closed think block with an empty answer.
        assertEquals("", CompanionRemark.postProcessRemark("<think>reasoning</think>"))
        // A bare echoed label with nothing after it.
        assertEquals("", CompanionRemark.postProcessRemark("Remark:"))
    }

    // ---- buildRemarkPrompt: persona + few-shots + the alert ----

    @Test
    fun buildRemarkPromptCarriesRulesFewShotsAndAlert() {
        val alert = alert(
            title = "Notification access granted",
            body = "A new app can read your notifications.",
        )
        val prompt = CompanionRemark.buildRemarkPrompt(alert)

        // The system persona and its hard rules.
        assertTrue(prompt.contains("You are Mink, a small on-device privacy companion."))
        assertTrue(prompt.contains("Output only the sentence."))

        // Both Event -> Remark few-shots.
        assertTrue(prompt.contains("Event: Weather gained camera access."))
        assertTrue(prompt.contains("Remark: Weather can now reach your camera."))
        assertTrue(prompt.contains("A voice recorder just started using your microphone."))

        // The alert filled into the template.
        assertTrue(
            prompt.contains(
                "Event: Notification access granted. Detail: A new app can read your notifications.\nRemark:",
            ),
        )

        // Delegates to the chat format: a system turn, no-think mode, and an open
        // assistant turn so the model continues with the remark.
        assertTrue(prompt.contains("${MiniCpmChatFormat.IM_START}${MiniCpmChatFormat.ROLE_SYSTEM}\n"))
        assertTrue(prompt.contains(MiniCpmChatFormat.NO_THINK))
        assertTrue(prompt.endsWith("${MiniCpmChatFormat.IM_START}${MiniCpmChatFormat.ROLE_ASSISTANT}\n"))
    }
}
