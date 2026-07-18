package com.mink.guardian.compose

import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.GenParams
import com.mink.guardian.llm.TextGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Collects one whole generation into a [Draft]. Internal on purpose: this is
 * the only code in the core that runs the generator, so raw model output can
 * only enter the world as a [Draft] the composer's gate must judge.
 */
internal class GenerationRunner(private val generator: TextGenerator) {

    suspend fun collect(prompt: String, params: GenParams): Draft {
        val raw = StringBuilder()
        generator.generate(prompt, params).collect { raw.append(it) }
        return Draft(raw.toString())
    }
}

/**
 * The one gate between the on-device model and a text surface: it runs the
 * generation inside the spec's budget, scrubs the draft with the surface's
 * deterministic post-processor, and checks every concrete claim against the
 * spec's ground truth before any model prose can reach the user.
 *
 * This class is the only public acceptor of a [TextGenerator] in the core —
 * the decision-making types (rules, policy, refiner, router) have no generator
 * parameter anywhere, which is the capability floor expressed at type level.
 *
 * Every failure path — budget elapse, empty or think-only output, a failed
 * grounding check, an engine exception — lands on the spec's deterministic
 * [HybridSpec.fallback] as [Author.DETERMINISTIC]. Caller cancellation is the
 * one exception that propagates: a cancelled surface must not pop a fallback.
 *
 * Budget honesty (unchanged from the pre-composer code): the budget covers the
 * wait for the engine's generation mutex plus the generation, and enforcement
 * is cooperative — cancellation lands between the engine's blocking native
 * calls, so a single pathologically slow call can overrun before the next
 * check. In practice the budget bounds the dominant slow path, a long token
 * stream.
 */
class GroundedComposer(generator: TextGenerator) {

    private val runner = GenerationRunner(generator)

    /**
     * Compose one hybrid surface text: model prose if it completes in budget
     * and survives the gate, the spec's deterministic fallback otherwise.
     */
    suspend fun compose(spec: HybridSpec): SurfaceText {
        val prose = withTimeoutOrNull(spec.budgetMs) {
            runCatching { gate(runner.collect(spec.prompt, spec.params), spec) }
                .getOrElse { if (it is CancellationException) throw it else null }
        }
        return if (prose != null) {
            SurfaceText(prose.text, Author.MODEL_GROUNDED)
        } else {
            SurfaceText(spec.fallback, Author.DETERMINISTIC)
        }
    }

    /**
     * Judge one draft: scrub it, reject blank remains, and reject the whole
     * draft when any concrete claim (a number on every surface, an unknown
     * proper noun on hybrid surfaces) does not trace back to [HybridSpec.facts].
     * Binary by design — a draft with one fabricated claim is not trimmed, it
     * is discarded whole for the deterministic fallback.
     */
    private fun gate(draft: Draft, spec: HybridSpec): GroundedProse? {
        val processed = spec.postProcess(draft.raw)
        if (processed.isBlank()) return null
        if (!GroundingCheck.isGrounded(processed, spec.facts, checkEntities = true)) return null
        return GroundedProse.fromGate(processed)
    }
}
