package com.mink.guardian.compose

import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.FakeTextGenerator
import com.mink.guardian.llm.FakeTextGenerator.Behavior
import com.mink.guardian.llm.GenParams
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of the agent (chat) path on a plain JVM with virtual time.
 * These pin the streaming contract the pre-composer chat had — a run of
 * provisional deltas (show-then-correct) closed by exactly one authoritative
 * Final — plus the two things that make chat distinct from the hybrid surfaces:
 * grounding is numbers-only (an unknown app name does NOT reject), and the
 * fallback is a plain deterministic string.
 *
 * The one deliberate change from the literal old chat is called out in its own
 * test: an engine exception now degrades to Fallback instead of propagating and
 * stranding a half-streamed message.
 */
class AgentComposerTest {

    /** Facts a battery/display answer would be checked against. */
    private val facts = GroundingCheck.factsOf(
        "Your battery is at 82 percent.",
        "Your display resolution is common.",
    )

    private fun chatSpec(
        budgetMs: Long = 60_000L,
        fallback: String = "Here is what I can tell you from the readings.",
    ) = AgentSpec(
        prompt = "PROMPT",
        facts = facts,
        fallback = fallback,
        budgetMs = budgetMs,
        params = GenParams.noThink(),
    )

    private fun List<AgentEvent>.deltas() = filterIsInstance<AgentEvent.Delta>()
    private fun List<AgentEvent>.finals() = filterIsInstance<AgentEvent.Final>()
    private fun List<AgentEvent>.visible() =
        deltas().joinToString("") { it.visibleDelta }

    // ---- the terminal-event invariant ----

    @Test
    fun everyRunEndsWithExactlyOneFinalAsTheLastEvent() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery ", "is at 82 percent.")))
        val events = GroundedComposer(fake).agent(chatSpec()).toList()
        assertEquals(1, events.finals().size)
        assertSame("the Final must be the last event", events.last(), events.finals().first())
    }

    // ---- the grounded happy path ----

    @Test
    fun aGroundedReplyStreamsDeltasThenAGroundedFinal() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery ", "reads ", "82 percent.")))
        val events = GroundedComposer(fake).agent(chatSpec()).toList()
        assertEquals("Your battery reads 82 percent.", events.visible())
        val final = (events.finals().first().reply)
        assertTrue(final is FinalReply.Grounded)
        assertEquals("Your battery reads 82 percent.", (final as FinalReply.Grounded).prose.text)
    }

    @Test
    fun thePromptReachesTheGeneratorVerbatim() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery is at 82 percent.")))
        GroundedComposer(fake).agent(chatSpec()).toList()
        assertEquals(listOf("PROMPT"), fake.prompts)
    }

    // ---- thinking split ----

    @Test
    fun reasoningIsSplitFromTheVisibleReplyAndCarriedOnTheFinal() = runTest {
        val fake = FakeTextGenerator(
            Behavior.Tokens(listOf("<think>", "checking", "</think>", "Your battery ", "is at 82 percent.")),
        )
        val events = GroundedComposer(fake).agent(chatSpec()).toList()
        // The reasoning never leaks into the visible stream.
        assertEquals("Your battery is at 82 percent.", events.visible())
        val final = events.finals().first().reply
        assertTrue(final is FinalReply.Grounded)
        assertEquals("checking", (final as FinalReply.Grounded).thinking)
    }

    // ---- numbers-only grounding (the chat-specific policy) ----

    @Test
    fun anUnknownAppNameDoesNotRejectAChatReply() = runTest {
        // Entities are NOT checked in agent mode: a proper noun absent from the
        // (whole-snapshot) facts must still pass, unlike a hybrid surface.
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Spotify may be watching your habits.")))
        val final = GroundedComposer(fake).agent(chatSpec()).toList().finals().first().reply
        assertTrue("an unknown app name must not trip the numbers-only gate", final is FinalReply.Grounded)
    }

    @Test
    fun aFabricatedFigureFallsBack() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery reads 37 percent.")))
        val final = GroundedComposer(fake).agent(chatSpec()).toList().finals().first().reply
        assertTrue(final is FinalReply.Fallback)
        assertEquals("Here is what I can tell you from the readings.", (final as FinalReply.Fallback).text)
    }

    @Test
    fun aGroundedFigurePasses() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery reads 82 percent.")))
        val final = GroundedComposer(fake).agent(chatSpec()).toList().finals().first().reply
        assertTrue(final is FinalReply.Grounded)
    }

    // ---- budget / degenerate output / failure ----

    @Test
    fun aBudgetElapseMidStreamStreamsThenFallsBack() = runTest {
        // noThink caps at 512 tokens, so 100 pieces at 500ms each is ~50s of
        // streaming — well past the 2s budget, which cuts it mid-stream.
        val fake = FakeTextGenerator(
            Behavior.Tokens(List(100) { "battery " }, perTokenDelayMs = 500L),
        )
        val events = GroundedComposer(fake).agent(chatSpec(budgetMs = 2_000L)).toList()
        assertTrue("some provisional deltas should have streamed", events.deltas().isNotEmpty())
        assertTrue(events.finals().first().reply is FinalReply.Fallback)
    }

    @Test
    fun blankOutputFallsBack() = runTest {
        val fake = FakeTextGenerator(Behavior.Empty)
        val final = GroundedComposer(fake).agent(chatSpec()).toList().finals().first().reply
        assertTrue(final is FinalReply.Fallback)
    }

    @Test
    fun thinkOnlyOutputFallsBack() = runTest {
        // Reasoning with no visible answer scrubs to blank content -> fallback.
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("<think>", "still reasoning about the battery")))
        val final = GroundedComposer(fake).agent(chatSpec()).toList().finals().first().reply
        assertTrue(final is FinalReply.Fallback)
    }

    @Test
    fun anUnloadedGeneratorFallsBack() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Your battery is at 82 percent.")), isLoaded = false)
        val events = GroundedComposer(fake).agent(chatSpec()).toList()
        assertEquals("", events.visible())
        assertTrue(events.finals().first().reply is FinalReply.Fallback)
    }

    @Test
    fun anEngineExceptionFallsBackInsteadOfPropagating() = runTest {
        // The deliberate deviation from literal old-chat parity: the old path had
        // no guard and would crash the collecting scope, stranding a streaming
        // message. Now the partial stream is corrected to the fallback.
        val fake = FakeTextGenerator(Behavior.Fail(after = listOf("Your battery ")))
        val events = GroundedComposer(fake).agent(chatSpec()).toList()
        assertTrue(events.finals().first().reply is FinalReply.Fallback)
    }

    // ---- cancellation ----

    @Test
    fun callerCancellationProducesNoTerminalEvent() = runTest {
        val fake = FakeTextGenerator(Behavior.Hang)
        val collected = mutableListOf<AgentEvent>()
        val job = launch { GroundedComposer(fake).agent(chatSpec(budgetMs = 60_000L)).toList(collected) }
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        // A cancelled chat did not degrade to a fallback Final.
        assertFalse(collected.any { it is AgentEvent.Final })
    }
}
