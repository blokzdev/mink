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
    fun capitalisedSentenceOpenersAreNotRejected() {
        // A warm, factual read opens sentences with ordinary words that are always
        // capitalised there; each is in the stoplist, so none flags — the check is
        // strict on every token (no sentence-position exemption).
        val facts = GroundingCheck.factsOf(
            "Recognizability: 40 out of 100.",
            "Bluetooth: your paired devices reveal a name.",
        )
        assertTrue(GroundingCheck.isGrounded("None of this leaves your phone.", facts))
        assertTrue(GroundingCheck.isGrounded("Overall, you're fairly recognizable.", facts))
        assertTrue(GroundingCheck.isGrounded("Together, these readings single you out.", facts))
        assertTrue(GroundingCheck.isGrounded("Around here, little has changed.", facts))
        assertTrue(GroundingCheck.isGrounded("Instead, your Bluetooth name does the work.", facts))
    }

    @Test
    fun aFabricatedAppNameIsCaughtEvenWhenItOpensASentence() {
        // Regression guard: a plain-capitalised fabricated app name must be rejected
        // wherever it appears, including the first word of a sentence (an earlier
        // sentence-initial exemption let these leak on the read surface).
        val facts = GroundingCheck.factsOf(
            "Weather gained camera access",
            "The Weather app can now use the camera.",
        )
        // Sentence-initial (subject) fabrication.
        assertFalse(GroundingCheck.isGrounded("Spotify can now use your microphone.", facts))
        assertFalse(GroundingCheck.isGrounded("Facebook has been quiet this week. Overall, you're fine.", facts))
        // Mid-sentence fabrication after a grounded opener.
        assertFalse(GroundingCheck.isGrounded("Overall, Spotify is the loudest app.", facts))
        // Sentence-initial internal-caps fabrication is still caught too.
        assertFalse(GroundingCheck.isGrounded("iMessage led the pack. Nothing else came close.", facts))
    }

    // ---- number extraction: idioms vs magnitudes ----

    @Test
    fun ratioAndClockIdiomsAreNotCheckedAsNumbers() {
        val facts = GroundingCheck.factsOf("Recognizability: 40 out of 100.")
        // 24/7 (ratio) and 10:30 (clock) are glued by '/' or ':', not figures.
        assertTrue(GroundingCheck.isGrounded("Mink watches 24/7, all on your phone.", facts))
        assertTrue(GroundingCheck.isGrounded("It stayed quiet at 10:30 today.", facts))
    }

    @Test
    fun aMagnitudeGluedToAUnitIsStillCheckedAndGroundedVsFabricated() {
        val facts = GroundingCheck.factsOf("Maps used 1.2 GB in the background.")
        // The grounded size, whether spaced or glued, must pass — and must not be
        // truncated to its integer part (an earlier regex read "1.2GB" as "1").
        assertTrue(GroundingCheck.isGrounded("It used 1.2GB overnight.", facts, checkEntities = false))
        assertTrue(GroundingCheck.isGrounded("It used 1.2 GB overnight.", facts, checkEntities = false))
        // A fabricated magnitude glued to a unit is still caught.
        assertFalse(GroundingCheck.isGrounded("It used 10GB overnight.", facts, checkEntities = false))
        assertFalse(GroundingCheck.isGrounded("It used 3.6GB overnight.", facts, checkEntities = false))
    }

    @Test
    fun bothEndpointsOfAFabricatedHyphenRangeAreChecked() {
        val facts = GroundingCheck.factsOf("You have 12 apps.")
        assertFalse(GroundingCheck.isGrounded("You have 50-100 apps.", facts, checkEntities = false))
        val claims = GroundingCheck.ungroundedClaims("You have 50-100 apps.", facts, checkEntities = false)
        assertTrue(claims.contains("50"))
        assertTrue(claims.contains("100"))
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

    @Test
    fun stemBridgesTheIesAndEsPluralArms() {
        assertTrue(
            GroundingCheck.isGrounded("Batteries drain faster.", GroundingCheck.factsOf("Your battery drains faster.")),
        )
        assertTrue(
            GroundingCheck.isGrounded("Watches can pair.", GroundingCheck.factsOf("Your watch can pair.")),
        )
        assertTrue(
            GroundingCheck.isGrounded("Classes group your apps.", GroundingCheck.factsOf("Each class groups your apps.")),
        )
    }

    @Test
    fun stemLeavesSsAndUsAndIsRootsIntact() {
        // These endings are singular, not plural markers — over-stripping them
        // (access->acce, status->statu) would break cross-form matching.
        assertTrue(
            GroundingCheck.isGrounded("Access stays limited.", GroundingCheck.factsOf("Camera access is limited.")),
        )
        assertTrue(
            GroundingCheck.isGrounded("Status looks normal.", GroundingCheck.factsOf("The status is normal.")),
        )
    }
}
