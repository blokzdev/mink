package com.mink.guardian.llm

import kotlinx.coroutines.flow.Flow

/**
 * Sampling parameters for one generation. The temperature and top-p defaults
 * follow the MiniCPM5 deploy guidance: thinking runs a little hotter for
 * exploratory reasoning, no-think stays tighter for direct answers.
 */
data class GenParams(
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int = 512,
    val thinking: Boolean = false,
) {
    companion object {
        fun think(maxTokens: Int = 768) =
            GenParams(temperature = 0.9f, topP = 0.95f, maxTokens = maxTokens, thinking = true)

        fun noThink(maxTokens: Int = 512) =
            GenParams(temperature = 0.7f, topP = 0.95f, maxTokens = maxTokens, thinking = false)
    }
}

/**
 * The generation seam between the guardian's text surfaces and the concrete
 * on-device engine: everything a caller may do with a loaded model, and nothing
 * of its lifecycle. Model lifecycle — download, file paths, load/unload, tier
 * sizing — deliberately stays OFF this interface on the Android side (the
 * controller owns it against the concrete [LlmEngine]), so a fake generator can
 * drive every generation path in plain JVM tests without pretending to manage a
 * native context.
 *
 * Contract an implementation must honour (the concrete engine's semantics,
 * which callers and tests rely on):
 *  - [generate] streams token pieces for one whole generation; the flow
 *    completes at end-of-stream or [GenParams.maxTokens].
 *  - Whole generations serialise: a second collector suspends until the first
 *    generation finishes, and never interleaves its output.
 *  - Cancelling collection between tokens is safe and leaves the engine clean
 *    for the next generation (the caller's generation budgets rely on this).
 *  - When [isLoaded] is false, [generate] completes without emitting.
 */
interface TextGenerator {
    val isLoaded: Boolean

    fun generate(prompt: String, params: GenParams): Flow<String>
}
