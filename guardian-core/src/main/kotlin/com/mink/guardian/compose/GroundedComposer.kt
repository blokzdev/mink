package com.mink.guardian.compose

import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.GenParams
import com.mink.guardian.llm.MiniCpmChatFormat
import com.mink.guardian.llm.TextGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    /**
     * The raw token stream for a generation, for the streaming (agent) path.
     * Keeps the generator confined to this class — the composer's [agent]
     * parses and gates these tokens; they only reach the user as an advisory
     * live draft, never as a persisted record.
     */
    fun stream(prompt: String, params: GenParams): Flow<String> = generator.generate(prompt, params)
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

    /**
     * Run one agent-mode (chat) generation: stream the visible reply as tokens
     * arrive, then close with exactly one [AgentEvent.Final] carrying the
     * authoritative reply — grounded model prose if the stream finished inside
     * the budget, was non-blank, and every cited figure traced back to the
     * spec's facts (numbers-only; entities are not checked over so broad a
     * ground truth), or the deterministic fallback otherwise.
     *
     * Show-then-correct: the [AgentEvent.Delta]s are the provisional draft the
     * live bubble may show; the [AgentEvent.Final] is the record. A [Delta] is a
     * distinct type no persistence path accepts, so unchecked model text can
     * reach the bubble but never the saved log.
     *
     * Failure handling matches [compose]: budget elapse, blank/think-only
     * output, a fabricated figure, and an engine exception all resolve to
     * [FinalReply.Fallback]; only caller cancellation propagates (a cancelled
     * chat produces no terminal event, not a fallback). The engine-exception
     * guard is deliberate — the pre-composer chat path lacked it and could
     * strand a half-streamed message; the remark and read paths always had it.
     */
    fun agent(spec: AgentSpec): Flow<AgentEvent> = flow {
        val raw = StringBuilder()
        val completed = withTimeoutOrNull(spec.budgetMs) {
            runCatching {
                runner.stream(spec.prompt, spec.params).collect { piece ->
                    raw.append(piece)
                    // Emit the whole parsed reply so far, not an increment, so a
                    // consumer's displayed draft tracks the model's own parse even
                    // when it is non-monotonic (a trailing fragment scrubbed away).
                    val parsed = MiniCpmChatFormat.parseReply(raw.toString())
                    emit(AgentEvent.Delta(parsed.content, parsed.thinking))
                }
            }.getOrElse { if (it is CancellationException) throw it else return@withTimeoutOrNull false }
            true
        } ?: false

        val finalParsed = MiniCpmChatFormat.parseReply(raw.toString())
        val candidate = finalParsed.content
        val reply = if (completed && candidate.isNotBlank() &&
            GroundingCheck.isGrounded(candidate, spec.facts, checkEntities = false)
        ) {
            FinalReply.Grounded(GroundedProse.fromGate(candidate), finalParsed.thinking)
        } else {
            FinalReply.Fallback(spec.fallback, finalParsed.thinking)
        }
        emit(AgentEvent.Final(reply))
    }
}
