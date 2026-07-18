package com.mink.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [GroundingCheck]: number and entity extraction and the
 * grounded/ungrounded verdict. The suite is deliberately heavy on FALSE-POSITIVE
 * regressions — realistic grounded model outputs that must NOT be rejected —
 * because a false reject silently degrades a model surface to its fallback, the
 * dominant failure the check is tuned to avoid.
 */
class GroundingCheckTest {

    // ---- numbers ----

    @Test
    fun aFabricatedNumberIsUngrounded() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        assertFalse(GroundingCheck.isGrounded("You have 73 tracking apps.", facts))
        assertTrue(GroundingCheck.ungroundedClaims("You have 73 apps.", facts, checkEntities = false).contains("73"))
    }

    @Test
    fun aGroundedNumberPasses() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        assertTrue(GroundingCheck.isGrounded("You scored 40 out of 100.", facts))
    }

    @Test
    fun numbersNormalizeAcrossPercentDecimalAndCommas() {
        assertTrue(GroundingCheck.isGrounded("40%", GroundingCheck.factsOf("the score is 40")))
        assertTrue(GroundingCheck.isGrounded("40.0", GroundingCheck.factsOf("40")))
        assertTrue(GroundingCheck.isGrounded("1,024 MB", GroundingCheck.factsOf("used 1024 MB")))
        assertTrue(GroundingCheck.isGrounded("3.5 GB", GroundingCheck.factsOf("3.5 GB over cellular")))
        assertFalse(GroundingCheck.isGrounded("3.6 GB", GroundingCheck.factsOf("3.5 GB over cellular")))
    }

    @Test
    fun spelledOutNumbersAreNotChecked() {
        // Idiomatic spelled-out numbers ("two or three") must never be flagged.
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        assertTrue(GroundingCheck.isGrounded("Two or three readings make you stand out.", facts))
    }

    // ---- entities ----

    @Test
    fun aSwappedAppNameIsUngrounded() {
        val facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        assertFalse(GroundingCheck.isGrounded("Facebook can now reach your camera.", facts))
        assertTrue(
            GroundingCheck.ungroundedClaims("Facebook can now reach your camera.", facts, checkEntities = true)
                .contains("Facebook"),
        )
    }

    @Test
    fun theGroundedAppNamePasses() {
        val facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        assertTrue(GroundingCheck.isGrounded("Weather can now reach your camera.", facts))
    }

    @Test
    fun possessivesAndPluralsOfGroundedTermsPass() {
        val facts = GroundingCheck.factsOf("The Weather app can now use the camera.")
        assertTrue(GroundingCheck.isGrounded("Weather's access to your Cameras is new.", facts))
    }

    @Test
    fun entityCheckIsSkippedWhenDisabled() {
        // Chat mode: numbers still checked, proper nouns tolerated (open ground truth).
        val facts = GroundingCheck.factsOf("Your paired devices reveal a name.")
        assertTrue(GroundingCheck.isGrounded("Instagram is chatty.", facts, checkEntities = false))
        assertFalse(GroundingCheck.isGrounded("You opened 99 apps.", facts, checkEntities = false))
    }

    // ---- false-positive regressions (must NOT be rejected) ----

    @Test
    fun realGroundedRemarksAreNotRejected() {
        val weather = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        assertTrue(GroundingCheck.isGrounded("Weather can now reach your camera.", weather))

        val data = GroundingCheck.factsOf(
            "Maps used 1.2 GB in the background",
            "Maps used 1.2 GB of cellular data in the background this week.",
        )
        assertTrue(GroundingCheck.isGrounded("Maps used 1.2 GB of background data.", data))

        val mic = GroundingCheck.factsOf(
            "Microphone in use",
            "A voice recorder started using the microphone.",
        )
        assertTrue(GroundingCheck.isGrounded("A voice recorder just started using your microphone.", mic))
    }

    @Test
    fun realGroundedNarrationsAreNotRejected() {
        val facts = GroundingCheck.factsOf(
            "Recognizability: 40 out of 100.",
            "Bluetooth: your paired devices reveal a name.",
            "Travel: you were recently in Germany.",
        )
        assertTrue(
            GroundingCheck.isGrounded(
                "You're fairly recognizable, 40 out of 100, mostly from the Bluetooth device names and " +
                    "your recent travel to Germany. Don't worry — none of this leaves your phone.",
                facts,
            ),
        )
    }

    @Test
    fun sentenceOpenersAndTechTermsAreNotFlagged() {
        val facts = GroundingCheck.factsOf("Your phone is recognizable.")
        // "This", "It", "Wi-Fi", "Bluetooth", "GPS", "GB" are vocabulary, not app names.
        assertTrue(GroundingCheck.isGrounded("This is why. It uses Wi-Fi, Bluetooth, and GPS.", facts))
    }

    @Test
    fun aFabricatedNarrationNumberOrAppIsRejected() {
        val facts = GroundingCheck.factsOf(
            "Recognizability: 40 out of 100.",
            "Bluetooth: your paired devices reveal a name.",
        )
        // 73 is invented.
        assertFalse(GroundingCheck.isGrounded("You're 73 out of 100 recognizable.", facts))
        // TikTok is invented.
        assertFalse(GroundingCheck.isGrounded("Your TikTok use makes you stand out.", facts))
    }

    // ---- factsOf ----

    @Test
    fun factsExtractNumbersAndVocabulary() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100. Bluetooth reveals a name.")
        assertTrue(40.0 in facts.numbers)
        assertTrue(100.0 in facts.numbers)
        assertTrue("bluetooth" in facts.vocab)
        assertTrue("recognizability" in facts.vocab)
        assertEquals(setOf(40.0, 100.0), facts.numbers)
    }

    @Test
    fun emptyTextIsTriviallyGrounded() {
        assertTrue(GroundingCheck.isGrounded("", GroundingCheck.factsOf("anything")))
    }

    // ---- sentence-opener regressions (must NOT be rejected) ----

    @Test
    fun capitalisedSentenceOpenersAreNotRejectedOnTheReadSurface() {
        // The multi-sentence read (skipSentenceInitial=true) opens sentences with
        // ordinary words that are always capitalised there; none is a product name.
        // Robust beyond the stoplist: "Around" is not stoplisted yet still passes.
        val facts = GroundingCheck.factsOf(
            "Recognizability: 40 out of 100.",
            "Bluetooth: your paired devices reveal a name.",
        )
        fun read(text: String) =
            GroundingCheck.isGrounded(text, facts, checkEntities = true, skipSentenceInitial = true)
        assertTrue(read("None of this leaves your phone."))
        assertTrue(read("Overall, you're fairly recognizable."))
        assertTrue(read("Together, these readings single you out."))
        assertTrue(read("Around here, little has changed."))
        assertTrue(read("Instead, your Bluetooth name does the work."))
    }

    @Test
    fun aSentenceInitialFabricatedAppIsCaughtOnTheStrictRemarkSurface() {
        // The terse remark (skipSentenceInitial=false) keeps checking its first word,
        // where the app name lives; the read surface tolerates it (entities grounded
        // elsewhere). "Spotify" is not stoplisted and not in facts.
        val facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        assertFalse(GroundingCheck.isGrounded("Spotify can now use your microphone.", facts))
        assertTrue(
            GroundingCheck.isGrounded(
                "Spotify can now use your microphone.", facts,
                checkEntities = true, skipSentenceInitial = true,
            ),
        )
    }

    // ---- digits glued inside tokens / idioms (must NOT be rejected) ----

    @Test
    fun digitsGluedIntoIdiomsAndTokensAreNotCheckedAsNumbers() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        // 24/7 and 10:30 are not word-shaped, so they clear both surfaces.
        assertTrue(GroundingCheck.isGrounded("Mink watches 24/7, all on your phone.", facts))
        assertTrue(GroundingCheck.isGrounded("It stayed quiet at 10:30 today.", facts))
        // On chat (entities off) glued alphanumerics like IPv6 / 2FA leave no
        // standalone number to reject.
        assertTrue(GroundingCheck.isGrounded("It uses IPv6 and stays local.", facts, checkEntities = false))
        assertTrue(GroundingCheck.isGrounded("Turn on 2FA for safety.", facts, checkEntities = false))
    }

    @Test
    fun aStandaloneFabricatedNumberIsStillRejectedAmongIdioms() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        // 24/7 is ignored, but a bare 73 is a real fabricated figure.
        assertFalse(
            GroundingCheck.isGrounded("Mink watched 73 apps 24/7.", facts, checkEntities = false),
        )
        assertTrue(
            GroundingCheck.ungroundedClaims("Mink watched 73 apps 24/7.", facts, checkEntities = false)
                .contains("73"),
        )
    }

    @Test
    fun aTrailingSeparatorCommaIsNotSwallowedIntoTheClaim() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        val claims = GroundingCheck.ungroundedClaims("You have 73, which is high.", facts, checkEntities = false)
        assertTrue(claims.contains("73"))
        assertFalse(claims.contains("73,"))
    }

    // ---- internal-caps brand names (leak: must be CHECKED) ----

    @Test
    fun aSwappedLowercaseInitialBrandIsUngrounded() {
        val facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        // iMessage's lowercase initial must not let it slip past the entity check.
        assertFalse(GroundingCheck.isGrounded("iMessage can now reach your camera.", facts))
        assertTrue(
            GroundingCheck.ungroundedClaims("iMessage can now reach your camera.", facts, checkEntities = true)
                .contains("iMessage"),
        )
    }

    @Test
    fun aGroundedInternalCapsBrandPasses() {
        val facts = GroundingCheck.factsOf(
            "iMessage gained camera access",
            "The iMessage app can now use the camera.",
        )
        assertTrue(GroundingCheck.isGrounded("iMessage can now reach your camera.", facts))
    }

    // ---- bidirectional singular/plural bridging (must NOT be rejected) ----

    @Test
    fun factsPluralGroundsCapitalisedSingularAndViceVersa() {
        val bluetooth = GroundingCheck.factsOf("Your paired devices reveal a name.")
        assertTrue(GroundingCheck.isGrounded("Device names make you stand out.", bluetooth))

        val weather = GroundingCheck.factsOf("The Weather app can now use the camera.")
        assertTrue(GroundingCheck.isGrounded("Cameras stay off until an app asks.", weather))
    }

    @Test
    fun esPluralOfAnSFinalRootIsGrounded() {
        // trimEnd('s') used to over-strip "Addresses" to "addresse"; the stem now
        // reduces it to the grounded root "address".
        val facts = GroundingCheck.factsOf("Your address is exposed to nearby apps.")
        assertTrue(GroundingCheck.isGrounded("Addresses can identify you.", facts))
    }
}
