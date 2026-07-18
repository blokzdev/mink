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
}
