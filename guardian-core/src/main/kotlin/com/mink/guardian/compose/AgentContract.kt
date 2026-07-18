package com.mink.guardian.compose

import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.GenParams

/**
 * Everything one agent-mode (chat) generation needs. Like [HybridSpec] but for
 * a streamed conversation, and with grounding policy carried by the TYPE:
 * agent replies are checked numbers-only. A chat answer ranges over the whole
 * device snapshot, so a proper-noun check against that broad a ground truth
 * would be low-value and false-positive-prone; [GroundedComposer.agent] instead
 * checks only that any figure the model cites traces back to [facts]. There is
 * no `postProcess` — agent output is parsed with the model's own chat format
 * (reasoning split from the visible reply) intrinsically.
 *
 * @param prompt the fully-built model prompt; the composer never edits it.
 * @param facts the ground truth every cited figure must trace back to. Non-null
 *   by construction.
 * @param fallback the deterministic reply this turn degrades to on budget
 *   elapse, blank output, a fabricated figure, or an engine failure. Non-null
 *   by construction; carried into [FinalReply.Fallback].
 * @param budgetMs wall-clock budget for the whole generation, including the
 *   wait for the engine's generation mutex.
 * @param params sampling parameters for the generation.
 */
class AgentSpec(
    val prompt: String,
    val facts: GroundingCheck.GroundingFacts,
    val fallback: String,
    val budgetMs: Long,
    val params: GenParams,
)

/**
 * One event in an agent generation. The stream is zero or more [Delta]s — the
 * provisional, show-then-correct draft as tokens arrive — followed by exactly
 * one [Final], the authoritative reply. A [Delta] is advisory live text; only a
 * [Final] carries text fit to persist. That split is the point: a chat log
 * commit accepts a [FinalReply], and [Delta] is a distinct type no persistence
 * path accepts, so unchecked model text can reach the live bubble but never the
 * saved record.
 */
sealed interface AgentEvent {
    /**
     * A streamed increment: [visibleDelta] is the new visible text (empty while
     * the model is still inside its reasoning block), [thinkingSoFar] the
     * reasoning accumulated to this point.
     */
    data class Delta(val visibleDelta: String, val thinkingSoFar: String?) : AgentEvent

    /** The terminal event. Always emitted, always exactly once, always last. */
    data class Final(val reply: FinalReply) : AgentEvent
}

/**
 * The authoritative end of an agent reply: grounded model prose (its figures
 * checked, so it carries a [GroundedProse]) or the deterministic fallback. The
 * only text a chat log commits.
 */
sealed interface FinalReply {
    data class Grounded(val prose: GroundedProse, val thinking: String?) : FinalReply
    data class Fallback(val text: String, val thinking: String?) : FinalReply
}
