package com.mink.narrative

import com.mink.core.model.SignalCategory
import com.mink.guardian.llm.MiniCpmChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SummaryNarration]: the grounded fact list handed to the
 * model, the chat prompt that wraps it, and the deterministic scrub that reduces
 * an untrusted model reply to a calm paragraph. No Android and no model, so
 * every branch is asserted with no device.
 *
 * The fixtures are built by hand with the real [FingerprintReport],
 * [IdentifyingSignal], and [StoryCard] constructors so the assertions exercise
 * the exact strings the implementation emits. The raw signal values are given
 * distinctive markers so a test can prove they never reach the model.
 */
class SummaryNarrationTest {

    /** A top signal whose raw [value] is a marker that must not reach the model. */
    private fun signal(
        category: SignalCategory,
        why: String,
        value: String,
    ): IdentifyingSignal = IdentifyingSignal(
        category = category,
        name = "$category reading",
        value = value,
        why = why,
    )

    private fun story(id: String, body: String): StoryCard =
        StoryCard(id = id, title = "Story $id", body = body, basis = "Inferred from $id.")

    private fun report(
        score: Int = 73,
        topSignals: List<IdentifyingSignal> = listOf(
            signal(SignalCategory.GPU, "The GPU renderer string is stable and rare.", "Adreno640SECRET"),
            signal(SignalCategory.SENSORS, "Few phone models share the same sensors.", "SENSORLISTSECRET"),
        ),
    ): FingerprintReport = FingerprintReport(
        headline = "Your phone is easy to single out.",
        detail = "Mink read 40 values across 9 surfaces on this phone.",
        uniquenessScore = score,
        cards = emptyList(),
        topSignals = topSignals,
    )

    // ---- buildNarrationFacts: only real, grounded facts go in ----

    @Test
    fun factsIncludeScoreTopSignalsAndStoryBodies() {
        val story = listOf(
            story("birthday", "The oldest app on it was installed March 2019."),
            story("apps", "Your installed apps suggest you may manage money."),
        )
        val facts = SummaryNarration.buildNarrationFacts(report(), story)

        // The recognizability score, phrased exactly.
        assertTrue(facts.contains("Recognizability: 73 out of 100."))
        // Each top signal as "<category title>: <why>", never the raw name or value.
        assertTrue(facts.contains("Graphics & GPU: The GPU renderer string is stable and rare."))
        assertTrue(facts.contains("Sensors: Few phone models share the same sensors."))
        // The already-grounded story sentences, verbatim.
        assertTrue(facts.contains("The oldest app on it was installed March 2019."))
        assertTrue(facts.contains("Your installed apps suggest you may manage money."))
    }

    @Test
    fun factsExcludeRawSignalValues() {
        // Raw values are deliberately left out so there is little for the model to
        // fabricate: only categories and pre-grounded sentences reach it.
        val facts = SummaryNarration.buildNarrationFacts(report(), emptyList())
        assertFalse(facts.contains("Adreno640SECRET"))
        assertFalse(facts.contains("SENSORLISTSECRET"))
    }

    @Test
    fun factsHandleEmptyTopSignalsAndStory() {
        // With nothing but a score, the fact list is exactly the one score line.
        val facts = SummaryNarration.buildNarrationFacts(report(topSignals = emptyList()), emptyList())
        assertEquals("Recognizability: 73 out of 100.", facts)
    }

    @Test
    fun factsSkipBlankStoryBodies() {
        // A story card with only whitespace contributes no line.
        val story = listOf(
            story("blank", "   "),
            story("apps", "Your installed apps suggest you may manage money."),
        )
        val facts = SummaryNarration.buildNarrationFacts(report(topSignals = emptyList()), story)
        assertEquals(
            "Recognizability: 73 out of 100.\nYour installed apps suggest you may manage money.",
            facts,
        )
    }

    @Test
    fun factsCapTopSignalsAndStoryAtFour() {
        // Only the first four of each are carried; the fifth and later are dropped.
        val topSignals = (1..6).map {
            signal(SignalCategory.GPU, "Why number $it matters.", "VALUE$it")
        }
        val story = (1..6).map { story("card$it", "Story body number $it.") }
        val facts = SummaryNarration.buildNarrationFacts(report(topSignals = topSignals), story)

        assertTrue(facts.contains("Why number 4 matters."))
        assertFalse(facts.contains("Why number 5 matters."))
        assertTrue(facts.contains("Story body number 4."))
        assertFalse(facts.contains("Story body number 5."))
    }

    // ---- buildNarrationPrompt: persona rules + facts + an open assistant turn ----

    @Test
    fun promptCarriesRulesFactsAndOpensAssistantTurn() {
        val rpt = report()
        val story = listOf(story("apps", "Your installed apps suggest you may manage money."))
        val prompt = SummaryNarration.buildNarrationPrompt(rpt, story)

        // The system rules that hold the model to the facts and to plain prose.
        assertTrue(prompt.contains("only the facts"))
        // The copy capitalises the first rule ("No markdown"); assert the real string.
        assertTrue(prompt.contains("No markdown, no lists, no headings"))

        // The grounded facts are carried verbatim inside the user turn.
        assertTrue(prompt.contains(SummaryNarration.buildNarrationFacts(rpt, story)))

        // Delegates to the chat format: a system turn, no-think mode, and an open
        // assistant turn so the model continues with the paragraph.
        assertTrue(prompt.contains("${MiniCpmChatFormat.IM_START}${MiniCpmChatFormat.ROLE_SYSTEM}\n"))
        assertTrue(prompt.contains(MiniCpmChatFormat.NO_THINK))
        assertTrue(prompt.endsWith("${MiniCpmChatFormat.IM_START}${MiniCpmChatFormat.ROLE_ASSISTANT}\n"))
    }

    // ---- postProcessNarration: the untrusted reply is scrubbed to a paragraph ----

    @Test
    fun stripsLeadingAndInlineThinkSpans() {
        // A leading reasoning block is dropped; the whole visible paragraph stays.
        assertEquals(
            "You are easy to single out. Your GPU is distinctive.",
            SummaryNarration.postProcessNarration(
                "<think>The score is high, so say it plainly.</think>" +
                    "You are easy to single out. Your GPU is distinctive.",
            ),
        )
        // An inline span the leading-only parse missed is removed too.
        assertEquals(
            "Your phone is easy to recognize.",
            SummaryNarration.postProcessNarration(
                "Your phone <think>hidden reasoning</think>is easy to recognize.",
            ),
        )
    }

    @Test
    fun stripsMarkdownBackticksAndListMarkers() {
        // Emphasis and code markers are removed and the inner words are kept.
        assertEquals(
            "Your phone is easily recognized.",
            SummaryNarration.postProcessNarration("**Your** phone is `easily` __recognized__."),
        )
        // A leading list marker at the very start is dropped.
        assertEquals(
            "Your phone is easy to single out.",
            SummaryNarration.postProcessNarration("- Your phone is easy to single out."),
        )
    }

    @Test
    fun dropsUnterminatedInlineThinkTail() {
        // An unterminated inline <think> (the model hit the token cap mid-reasoning,
        // no closing tag) leaves its reasoning tail behind; it is dropped rather than
        // leaking into the read.
        val result = SummaryNarration.postProcessNarration("You are recognizable. <think>hmm the score")
        assertEquals("You are recognizable.", result)
        assertFalse(result.contains("hmm"))
    }

    @Test
    fun stripsLeadingHeadingMarker() {
        // A leading markdown heading marker is dropped, keeping the words after it.
        assertEquals(
            "You stand out.",
            SummaryNarration.postProcessNarration("# You stand out."),
        )
    }

    @Test
    fun keepsMultipleSentencesUnlikeTheOneLineRemark() {
        // The whole paragraph survives: it is not cut at the first terminator, and
        // the newline between sentences is collapsed into a single space.
        val raw = "You are easy to single out.\nYour GPU is distinctive, and your sensors narrow it further."
        assertEquals(
            "You are easy to single out. Your GPU is distinctive, and your sensors narrow it further.",
            SummaryNarration.postProcessNarration(raw),
        )
    }

    @Test
    fun capsLongTextAtSentenceBoundaryWithEllipsis() {
        val sentence = "Your phone is easy to recognize. "
        val long = sentence.repeat(20)
        val result = SummaryNarration.postProcessNarration(long)

        assertTrue(result.length <= SummaryNarration.NARRATION_MAX_CHARS)
        assertTrue(result.endsWith("…"))
        // The cut fell on a sentence boundary: the char before the ellipsis is a period.
        val head = result.dropLast(1)
        assertTrue(head.endsWith("."))
        // The kept head is a prefix of the collapsed narration.
        assertTrue(sentence.repeat(20).trim().startsWith(head))
    }

    @Test
    fun capsLongUnpunctuatedTextAtWordBoundaryWithEllipsis() {
        val word = "phone "
        val long = word.repeat(90)
        val result = SummaryNarration.postProcessNarration(long)

        assertTrue(result.length <= SummaryNarration.NARRATION_MAX_CHARS)
        assertTrue(result.endsWith("…"))
        // With no terminator to prefer, the cut backs off to the last word boundary.
        val head = result.dropLast(1)
        val collapsed = word.repeat(90).trim()
        assertTrue(collapsed.startsWith(head))
        assertEquals(' ', collapsed[head.length])
    }

    @Test
    fun blankOrReasoningOnlyBecomesEmpty() {
        assertEquals("", SummaryNarration.postProcessNarration(""))
        assertEquals("", SummaryNarration.postProcessNarration("   "))
        // Nothing but an unterminated reasoning block: no visible reply survives.
        assertEquals("", SummaryNarration.postProcessNarration("<think>just reasoning, no answer"))
        // A closed reasoning block with an empty answer.
        assertEquals("", SummaryNarration.postProcessNarration("<think>reasoning</think>"))
    }
}
