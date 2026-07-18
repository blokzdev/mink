package com.mink.guardian.compose

import com.mink.companion.CompanionRemark
import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.FakeTextGenerator
import com.mink.guardian.llm.FakeTextGenerator.Behavior
import com.mink.guardian.llm.GenParams
import com.mink.narrative.SummaryNarration
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The composer's contract on a plain JVM with virtual time: model prose only
 * comes back [Author.MODEL_GROUNDED] when the generation completed inside the
 * budget AND survived the grounding gate; every failure shape — budget elapse,
 * hang, blank or think-only output, an unsupported claim, an engine exception,
 * an unloaded engine — lands on the spec's deterministic fallback. These are
 * the timeout/fallback paths that previously had no unit harness because they
 * lived inside the Android controller.
 */
class GroundedComposerTest {

    /**
     * A remark-shaped spec grounded in one alert's title and body, the same
     * ground truth the production delegation builds.
     */
    private fun remarkSpec(
        budgetMs: Long = 20_000L,
        fallback: String = "Weather gained camera access",
    ) = HybridSpec(
        prompt = "PROMPT",
        facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        ),
        fallback = fallback,
        postProcess = CompanionRemark::postProcessRemark,
        budgetMs = budgetMs,
        params = GenParams.noThink(maxTokens = CompanionRemark.REMARK_MAX_TOKENS),
    )

    // ---- the grounded happy path ----

    @Test
    fun aGroundedDraftReturnsModelGroundedProse() = runTest {
        val fake = FakeTextGenerator(
            Behavior.Tokens(listOf("Weather ", "can now use ", "your camera.")),
        )
        val out = GroundedComposer(fake).compose(remarkSpec())
        assertEquals(Author.MODEL_GROUNDED, out.author)
        assertEquals("Weather can now use your camera.", out.text)
    }

    @Test
    fun thePromptReachesTheGeneratorVerbatim() = runTest {
        val fake = FakeTextGenerator(Behavior.Tokens(listOf("Weather can now use your camera.")))
        GroundedComposer(fake).compose(remarkSpec())
        assertEquals(listOf("PROMPT"), fake.prompts)
    }

    @Test
    fun theNarrationPipelinePassesAGroundedRead() = runTest {
        val facts = "Recognizability: 82 out of 100.\nDisplay: your screen size is common."
        val spec = HybridSpec(
            prompt = "PROMPT",
            facts = GroundingCheck.factsOf(facts),
            fallback = "",
            postProcess = SummaryNarration::postProcessNarration,
            budgetMs = 40_000L,
            params = GenParams.noThink(maxTokens = SummaryNarration.NARRATION_MAX_TOKENS),
        )
        val fake = FakeTextGenerator(
            Behavior.Tokens(listOf("Your phone scores ", "82 out of 100. ", "Your display is common.")),
        )
        val out = GroundedComposer(fake).compose(spec)
        assertEquals(Author.MODEL_GROUNDED, out.author)
        assertEquals("Your phone scores 82 out of 100. Your display is common.", out.text)
    }

    // ---- the budget ----

    @Test
    fun aBudgetElapseMidStreamFallsBackDeterministically() = runTest {
        // The spec caps at REMARK_MAX_TOKENS (48), so the fake emits 48 of these
        // 500ms tokens — ~24s of streaming, well past the 2s budget, so the
        // budget trips mid-stream and the surface falls back.
        val fake = FakeTextGenerator(
            Behavior.Tokens(List(100) { "Weather " }, perTokenDelayMs = 500L),
        )
        val out = GroundedComposer(fake).compose(remarkSpec(budgetMs = 2_000L))
        assertEquals(Author.DETERMINISTIC, out.author)
        assertEquals("Weather gained camera access", out.text)
    }

    @Test
    fun aHungGenerationFallsBackWhenTheBudgetElapses() = runTest {
        val fake = FakeTextGenerator(Behavior.Hang)
        val out = GroundedComposer(fake).compose(remarkSpec(budgetMs = 20_000L))
        assertEquals(Author.DETERMINISTIC, out.author)
        // The whole wait ran on the virtual clock and stopped at the budget.
        assertEquals(20_000L, testScheduler.currentTime)
    }

    // ---- the grounding gate ----

    @Test
    fun noDraftWithAnUnsupportedClaimEverReturnsModelGrounded() = runTest {
        val fabricated = listOf(
            "Spotify can now use your camera.",          // fabricated app name (first-upper)
            "iMessage read your codes.",                 // internal-caps fabrication (lowercase-initial)
            "Weather opened 37 apps.",                   // fabricated count
            "Weather sent 1.2GB overnight.",             // fabricated magnitude
            "TikTok read your contacts.",                // fabricated app name (first-upper)
            "Weather used your camera 24 times.",        // grounded name, fabricated number
        )
        for (text in fabricated) {
            val fake = FakeTextGenerator(Behavior.Tokens(listOf(text)))
            val out = GroundedComposer(fake).compose(remarkSpec())
            assertEquals("\"$text\" must not pass the gate", Author.DETERMINISTIC, out.author)
            assertEquals("Weather gained camera access", out.text)
        }
    }

    // ---- degenerate output ----

    @Test
    fun blankOutputFallsBack() = runTest {
        val fake = FakeTextGenerator(Behavior.Empty)
        val out = GroundedComposer(fake).compose(remarkSpec())
        assertEquals(Author.DETERMINISTIC, out.author)
    }

    @Test
    fun thinkOnlyOutputFallsBack() = runTest {
        // An unterminated reasoning block scrubs down to nothing.
        val fake = FakeTextGenerator(
            Behavior.Tokens(listOf("<think>", "the owner should hear about the camera")),
        )
        val out = GroundedComposer(fake).compose(remarkSpec())
        assertEquals(Author.DETERMINISTIC, out.author)
    }

    @Test
    fun anUnloadedGeneratorFallsBackWithoutGenerating() = runTest {
        val fake = FakeTextGenerator(
            Behavior.Tokens(listOf("Weather can now use your camera.")),
            isLoaded = false,
        )
        val out = GroundedComposer(fake).compose(remarkSpec())
        assertEquals(Author.DETERMINISTIC, out.author)
        assertEquals(emptyList<String>(), fake.prompts)
    }

    // ---- failure and cancellation ----

    @Test
    fun aGenerationExceptionFallsBackInsteadOfThrowing() = runTest {
        val fake = FakeTextGenerator(Behavior.Fail(after = listOf("Weather ")))
        val out = GroundedComposer(fake).compose(remarkSpec())
        assertEquals(Author.DETERMINISTIC, out.author)
        assertEquals("Weather gained camera access", out.text)
    }

    @Test
    fun callerCancellationPropagatesInsteadOfFallingBack() = runTest {
        val fake = FakeTextGenerator(Behavior.Hang)
        val composer = GroundedComposer(fake)
        var result: SurfaceText? = null
        val job = launch { result = composer.compose(remarkSpec(budgetMs = 60_000L)) }
        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        // A cancelled surface produced nothing — it did not degrade to a fallback.
        assertNull(result)
    }
}
