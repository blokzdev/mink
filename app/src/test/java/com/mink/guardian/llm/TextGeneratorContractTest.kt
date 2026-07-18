package com.mink.guardian.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The [TextGenerator] contract, held by both the scriptable fake and the real
 * engine in its bridge-absent state (the only state a JVM test can reach — on
 * the JVM the native library never loads, so the real engine stays unloaded).
 * Keeping the two in agreement is what makes fake-driven surface tests
 * meaningful: a path proven against the fake behaves the same against an
 * unloaded engine, and the loaded-engine semantics are pinned by the interface
 * KDoc + the instrumented suite.
 */
class TextGeneratorContractTest {

    private val params = GenParams.noThink(maxTokens = 8)

    // ---- contract: not loaded => no output ----

    @Test
    fun realEngineWithoutBridgeIsNotLoadedAndEmitsNothing() = runTest {
        val real = LlmEngine()
        assertFalse(real.isLoaded)
        assertEquals(emptyList<String>(), real.generate("hello", params).toList())
    }

    @Test
    fun fakeWhenNotLoadedEmitsNothing() = runTest {
        val fake = FakeTextGenerator(
            behavior = FakeTextGenerator.Behavior.Tokens(listOf("should", "not", "appear")),
            isLoaded = false,
        )
        assertEquals(emptyList<String>(), fake.generate("hello", params).toList())
        assertTrue(fake.prompts.isEmpty())
    }

    // ---- fake behaviours the surface tests will lean on ----

    @Test
    fun fakeStreamsScriptedTokensAndLogsThePrompt() = runTest {
        val fake = FakeTextGenerator(FakeTextGenerator.Behavior.Tokens(listOf("a ", "b ", "c")))
        assertEquals(listOf("a ", "b ", "c"), fake.generate("prompt-1", params).toList())
        assertEquals(listOf("prompt-1"), fake.prompts)
    }

    @Test
    fun fakeRespectsMaxTokens() = runTest {
        val fake = FakeTextGenerator(FakeTextGenerator.Behavior.Tokens(List(50) { "t$it " }))
        assertEquals(8, fake.generate("p", params).toList().size)
    }

    @Test
    fun fakeHangIsCancellableByATimeout() = runTest {
        val fake = FakeTextGenerator(FakeTextGenerator.Behavior.Hang)
        // Mirrors the surfaces' budget pattern: a hung generation is abandoned
        // and the caller falls back. runTest's virtual clock makes this instant.
        val result = withTimeoutOrNull(1_000) {
            fake.generate("p", params).toList()
        }
        assertNull(result)
    }

    @Test
    fun fakeFailThrowsAfterItsPrefix() = runTest {
        val fake = FakeTextGenerator(FakeTextGenerator.Behavior.Fail(after = listOf("partial ")))
        val seen = mutableListOf<String>()
        val thrown = runCatching { fake.generate("p", params).collect { seen += it } }
        assertEquals(listOf("partial "), seen)
        assertTrue(thrown.isFailure)
    }
}
