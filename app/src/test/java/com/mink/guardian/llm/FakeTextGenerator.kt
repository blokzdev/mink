package com.mink.guardian.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A scriptable [TextGenerator] for plain-JVM tests of the generation surfaces —
 * budgets, grounding, fallbacks — with no native engine. Behaviours mirror the
 * failure shapes the real engine can produce; the prompt log lets a test assert
 * what was actually asked of the model.
 *
 * Honours the [TextGenerator] contract as tests rely on it: emits nothing when
 * [isLoaded] is false, completes at end-of-script, and is safe to cancel
 * between tokens ([Behavior.Hang] parks in a cancellable delay).
 */
class FakeTextGenerator(
    var behavior: Behavior = Behavior.Empty,
    override var isLoaded: Boolean = true,
) : TextGenerator {

    sealed interface Behavior {
        /** Emit these pieces in order, optionally pausing between them. */
        data class Tokens(val pieces: List<String>, val perTokenDelayMs: Long = 0L) : Behavior

        /** Emit nothing and never complete (until cancelled) — a stuck generation. */
        data object Hang : Behavior

        /** Complete immediately with no output — the not-loaded/empty shape. */
        data object Empty : Behavior

        /** Throw mid-generation — a native failure surfacing as an exception. */
        data class Fail(val after: List<String> = emptyList()) : Behavior
    }

    /** Every prompt passed to [generate], oldest first. */
    val prompts = mutableListOf<String>()

    override fun generate(prompt: String, params: GenParams): Flow<String> = flow {
        if (!isLoaded) return@flow
        prompts += prompt
        when (val b = behavior) {
            is Behavior.Tokens -> {
                for (piece in b.pieces.take(params.maxTokens)) {
                    if (b.perTokenDelayMs > 0) delay(b.perTokenDelayMs)
                    emit(piece)
                }
            }
            Behavior.Hang -> delay(Long.MAX_VALUE)
            Behavior.Empty -> Unit
            is Behavior.Fail -> {
                b.after.forEach { emit(it) }
                throw IllegalStateException("fake generation failure")
            }
        }
    }
}
