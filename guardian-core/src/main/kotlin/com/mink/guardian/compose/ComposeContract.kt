package com.mink.guardian.compose

import com.mink.guardian.GroundingCheck
import com.mink.guardian.llm.GenParams

/**
 * The types that make skipping the grounding gate a compile error rather than a
 * review comment. Raw model output only exists as a [Draft], which nothing
 * outside this module can create or read; text only leaves the composer as a
 * [SurfaceText] stamped with its [Author]; and a [HybridSpec] will not
 * construct without the ground-truth facts and a deterministic fallback.
 */

/** Who authored the text a surface shows. */
enum class Author {
    /** The model wrote it and every concrete claim passed the grounding gate. */
    MODEL_GROUNDED,

    /** Deterministic text: a fallback, a template, or a rules answer. */
    DETERMINISTIC,
}

/** One finished piece of surface text, stamped with who authored it. */
data class SurfaceText(val text: String, val author: Author)

/**
 * Raw, untrusted model output. Only [GenerationRunner] creates one, and only
 * the composer's gate reads [raw] — the internal constructor and internal field
 * are compiler-enforced across the module boundary, so surface code in the app
 * can never see model text that has not been judged.
 */
class Draft internal constructor(internal val raw: String)

/**
 * Model prose that passed the grounding gate. The private constructor is the
 * point: the only way to obtain one is [fromGate], and the only caller of
 * [fromGate] is the composer's gate (backstopped by the architecture test), so
 * holding a [GroundedProse] IS the proof its claims were checked.
 */
class GroundedProse private constructor(val text: String) {
    companion object {
        /** For the composer's grounding gate alone; see the class contract. */
        internal fun fromGate(text: String): GroundedProse = GroundedProse(text)
    }
}

/**
 * Everything one hybrid-mode composition needs, declared up front. Grounding
 * policy is carried by the spec's TYPE, not a caller-remembered flag: a hybrid
 * surface (companion remark, summary narration) always checks numbers AND
 * entities, because its [facts] are a tight, purpose-built ground truth.
 *
 * @param prompt the fully-built model prompt; the composer never edits it.
 * @param facts the ground truth every concrete claim in the draft must trace
 *   back to. Non-null by construction — a spec without facts cannot exist.
 * @param fallback the deterministic text this surface degrades to. Non-null by
 *   construction; every failure path lands on it.
 * @param postProcess the surface's deterministic scrub of the raw draft (strip
 *   reasoning, markdown, length caps) applied before the grounding gate. It
 *   receives UNTRUSTED model text — it must return it scrubbed for judging,
 *   never publish it or stash it anywhere else.
 * @param budgetMs wall-clock budget for the whole generation, including the
 *   wait for the engine's generation mutex. On elapse the surface falls back.
 * @param params sampling parameters for the generation.
 */
class HybridSpec(
    val prompt: String,
    val facts: GroundingCheck.GroundingFacts,
    val fallback: String,
    val postProcess: (String) -> String,
    val budgetMs: Long,
    val params: GenParams,
)
