package com.mink.guardian.route

import com.mink.guardian.GuardianTier

/**
 * How much of the model a surface's text may involve, in increasing order of
 * model-fulness. The declaration order is load-bearing: [ModeRouter.resolve] is
 * downgrade-only, and the property test that proves it compares ordinals.
 */
enum class Mode {
    /** Deterministic system notification. Zero model, ever. */
    NOTIFICATION,

    /** Deterministic templated text (timeline entries, rules answers). Zero model. */
    SCRIPT,

    /** Rules pick the finding; the model writes prose that must pass grounding. */
    HYBRID,

    /** Free conversation, streamed, grounded before it becomes the record. */
    AGENT,
}

/** Every place guardian text reaches the user. */
enum class Surface {
    SYSTEM_NOTIFICATION,
    TIMELINE,
    COMPANION_REMARK,
    SUMMARY_NARRATION,
    CHAT,
}

/**
 * The user's per-surface voice preference. AUTO lets the surface run at its
 * intrinsic mode; DETERMINISTIC lowers a model surface to SCRIPT. There is
 * deliberately no raising variant — configuration that adds model involvement
 * to a surface is unrepresentable, not just disallowed.
 */
enum class ModePreference { AUTO, DETERMINISTIC }

/**
 * Decides, in one place, whether a surface speaks with the model or with
 * deterministic text. Replaces the tier/loaded conditionals that used to be
 * scattered across the chat, remark, and narration paths.
 *
 * [resolve] is a total, downgrade-only lattice: no rule can ever return a mode
 * more model-ful than the surface's [intrinsic] mode. Runtime degradation
 * *within* HYBRID/AGENT (budget elapse, blank output, failed grounding) is the
 * composer's job, not the router's — it lands on the same deterministic
 * fallback a SCRIPT resolution would have produced.
 */
object ModeRouter {

    /** The mode a surface runs at when nothing lowers it. */
    fun intrinsic(surface: Surface): Mode = when (surface) {
        Surface.SYSTEM_NOTIFICATION -> Mode.NOTIFICATION
        Surface.TIMELINE -> Mode.SCRIPT
        Surface.COMPANION_REMARK -> Mode.HYBRID
        Surface.SUMMARY_NARRATION -> Mode.HYBRID
        Surface.CHAT -> Mode.AGENT
    }

    /**
     * Resolve the mode for one composition. First match wins:
     *
     * 1. An intrinsically deterministic surface stays deterministic. Zero
     *    model, unconditionally.
     * 2. Immutable pin: an immutable-rule alert on the companion remark runs
     *    SCRIPT — the model never re-words a lane-5 immutable finding. This is
     *    the third leg of the evaluation-drift guard, alongside the
     *    notification gate's immutable short-circuit and the refiner's
     *    immutable exclusion.
     * 3. Capability ceiling: no model on this tier, or none loaded, means
     *    SCRIPT.
     * 4. User preference: DETERMINISTIC lowers the surface to SCRIPT.
     * 5. Otherwise the surface runs at its intrinsic mode.
     */
    fun resolve(
        surface: Surface,
        tier: GuardianTier,
        modelLoaded: Boolean,
        immutableAlert: Boolean = false,
        preference: ModePreference = ModePreference.AUTO,
    ): Mode {
        val intrinsic = intrinsic(surface)
        if (intrinsic == Mode.NOTIFICATION || intrinsic == Mode.SCRIPT) return intrinsic
        if (immutableAlert && surface == Surface.COMPANION_REMARK) return Mode.SCRIPT
        if (tier == GuardianTier.RULES_ONLY || !modelLoaded) return Mode.SCRIPT
        if (preference == ModePreference.DETERMINISTIC) return Mode.SCRIPT
        return intrinsic
    }
}
