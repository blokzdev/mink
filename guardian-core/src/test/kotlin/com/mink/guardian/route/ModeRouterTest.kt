package com.mink.guardian.route

import com.mink.guardian.GuardianTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The router's contract, tested two ways: truth tables asserting parity with
 * the literal tier/loaded conditionals it replaced, and a property over the
 * whole input space that resolution is downgrade-only.
 */
class ModeRouterTest {

    private val tiers = GuardianTier.values()
    private val bools = listOf(false, true)
    private val prefs = ModePreference.values()

    @Test
    fun theIntrinsicTableMatchesTheDesign() {
        assertEquals(Mode.NOTIFICATION, ModeRouter.intrinsic(Surface.SYSTEM_NOTIFICATION))
        assertEquals(Mode.SCRIPT, ModeRouter.intrinsic(Surface.TIMELINE))
        assertEquals(Mode.HYBRID, ModeRouter.intrinsic(Surface.COMPANION_REMARK))
        assertEquals(Mode.HYBRID, ModeRouter.intrinsic(Surface.SUMMARY_NARRATION))
        assertEquals(Mode.AGENT, ModeRouter.intrinsic(Surface.CHAT))
    }

    /**
     * The downgrade-only property test compares Mode ordinals, so the enum's
     * declaration order (increasing model-fulness) IS the safety property. Pin
     * it: a reorder that made e.g. AGENT.ordinal < HYBRID.ordinal would silently
     * weaken that test into accepting a raise, invisible until a raising bug
     * lands on top of it.
     */
    @Test
    fun theModeOrderIsMonotoneInModelFulness() {
        assertEquals(
            listOf(Mode.NOTIFICATION, Mode.SCRIPT, Mode.HYBRID, Mode.AGENT),
            Mode.values().toList(),
        )
    }

    @Test
    fun deterministicSurfacesResolveIntrinsicallyUnderEveryInput() {
        for (tier in tiers) for (loaded in bools) for (imm in bools) for (pref in prefs) {
            assertEquals(
                Mode.NOTIFICATION,
                ModeRouter.resolve(Surface.SYSTEM_NOTIFICATION, tier, loaded, imm, pref),
            )
            assertEquals(
                Mode.SCRIPT,
                ModeRouter.resolve(Surface.TIMELINE, tier, loaded, imm, pref),
            )
        }
    }

    /** Parity with the old literal check: RULES_ONLY or nothing loaded -> deterministic. */
    @Test
    fun remarkAndNarrationMatchTheOldTierChecks() {
        for (tier in tiers) for (loaded in bools) {
            val expected =
                if (tier == GuardianTier.RULES_ONLY || !loaded) Mode.SCRIPT else Mode.HYBRID
            assertEquals(expected, ModeRouter.resolve(Surface.COMPANION_REMARK, tier, loaded))
            assertEquals(expected, ModeRouter.resolve(Surface.SUMMARY_NARRATION, tier, loaded))
        }
    }

    /** Parity with chat's old useLlm check: a non-rules tier with a loaded model. */
    @Test
    fun chatMatchesTheOldUseLlmCheck() {
        for (tier in tiers) for (loaded in bools) {
            val expected =
                if (tier != GuardianTier.RULES_ONLY && loaded) Mode.AGENT else Mode.SCRIPT
            assertEquals(expected, ModeRouter.resolve(Surface.CHAT, tier, loaded))
        }
    }

    /**
     * THE one intentional behavior change of the refactor: an immutable-rule
     * alert on the companion remark is pinned to SCRIPT under every tier, load
     * state, and preference — the model never re-words a lane-5 finding.
     */
    @Test
    fun anImmutableAlertPinsTheCompanionRemarkToScript() {
        for (tier in tiers) for (loaded in bools) for (pref in prefs) {
            assertEquals(
                Mode.SCRIPT,
                ModeRouter.resolve(
                    Surface.COMPANION_REMARK,
                    tier,
                    loaded,
                    immutableAlert = true,
                    preference = pref,
                ),
            )
        }
    }

    /** The pin is remark-only: the flag never lowers the other model surfaces. */
    @Test
    fun theImmutablePinDoesNotTouchNarrationOrChat() {
        assertEquals(
            Mode.HYBRID,
            ModeRouter.resolve(
                Surface.SUMMARY_NARRATION,
                GuardianTier.FULL,
                modelLoaded = true,
                immutableAlert = true,
            ),
        )
        assertEquals(
            Mode.AGENT,
            ModeRouter.resolve(
                Surface.CHAT,
                GuardianTier.FULL,
                modelLoaded = true,
                immutableAlert = true,
            ),
        )
    }

    @Test
    fun aDeterministicPreferenceLowersEveryModelSurfaceToScript() {
        // Swept over tier x loaded, not pinned at one point: rule 4 is
        // tier-independent, so a future edit that conditioned it on tier (letting
        // model text through against an explicit deterministic preference — the
        // catastrophic direction) would escape a single-point test yet still
        // satisfy the downgrade-only property. At (RULES_ONLY, false) the ceiling
        // already forces SCRIPT, so SCRIPT is the expected value across the sweep.
        for (surface in listOf(Surface.COMPANION_REMARK, Surface.SUMMARY_NARRATION, Surface.CHAT)) {
            for (tier in tiers) for (loaded in bools) {
                assertEquals(
                    "resolve($surface, $tier, loaded=$loaded, DETERMINISTIC) should be SCRIPT",
                    Mode.SCRIPT,
                    ModeRouter.resolve(
                        surface,
                        tier,
                        modelLoaded = loaded,
                        preference = ModePreference.DETERMINISTIC,
                    ),
                )
            }
        }
    }

    /**
     * The lattice property: over the entire input space, resolution never
     * returns a mode more model-ful than the surface's intrinsic mode. Leans
     * on Mode's declaration order (NOTIFICATION < SCRIPT < HYBRID < AGENT).
     */
    @Test
    fun resolutionIsDowngradeOnlyOverTheWholeInputSpace() {
        for (surface in Surface.values()) {
            for (tier in tiers) for (loaded in bools) for (imm in bools) for (pref in prefs) {
                val resolved = ModeRouter.resolve(surface, tier, loaded, imm, pref)
                assertTrue(
                    "resolve($surface, $tier, loaded=$loaded, immutable=$imm, $pref) = $resolved " +
                        "raised above intrinsic ${ModeRouter.intrinsic(surface)}",
                    resolved.ordinal <= ModeRouter.intrinsic(surface).ordinal,
                )
            }
        }
    }
}
