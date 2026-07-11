package com.mink

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.ui.OnboardingStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * The app's first instrumentation smoke suite. Drives [MainActivity] through the
 * real Compose graph on a device/emulator and asserts on user-visible text and
 * content descriptions (the UI exposes no test tags yet).
 *
 * These tests pre-seed the onboarding "seen" flag before the Activity launches
 * so every case lands on the home screen. Onboarding itself is exercised by
 * [OnboardingSmokeTest]. Everything here stays hermetic: no network, no
 * permission grants, no guardian model interaction.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    private val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Marks onboarding as seen before the Activity is launched by [composeRule],
     * so the start destination resolves to home rather than the pager. Runs in
     * the outer position of the chain so the write completes before launch.
     */
    private val seedOnboarding = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking { OnboardingStore.markSeen(ApplicationProvider.getApplicationContext()) }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(seedOnboarding).around(composeRule)

    @Test
    fun appLaunches_showsHomeWithCategoryList() {
        awaitText("What your phone reveals")

        // The guardian entry banner and a few known passive category titles from
        // SignalCategory, scrolled into view so the LazyColumn composes them.
        composeRule.onNodeWithText("Meet Mink, your guardian").assertIsDisplayed()
        scrollToText("Device Identity")
        composeRule.onNodeWithText("Device Identity").assertIsDisplayed()
        scrollToText("System Info")
        composeRule.onNodeWithText("System Info").assertIsDisplayed()
        scrollToText("Battery & Power")
        composeRule.onNodeWithText("Battery & Power").assertIsDisplayed()
    }

    @Test
    fun tappingPassiveCategory_opensDetail() {
        awaitText("What your phone reveals")

        // Device Identity is PASSIVE: no runtime permission, so tapping it opens
        // the detail screen directly with no permission gate.
        scrollToText("Device Identity")
        composeRule.onNodeWithText("Device Identity").performClick()

        // The detail screen has a back arrow; the home screen does not.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Device Identity").assertIsDisplayed()
        composeRule
            .onNodeWithText("Any app on your phone can read these", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun guardianDashboard_showsTierCardAndSwitchWithoutEnabling() {
        awaitText("What your phone reveals")

        composeRule.onNodeWithText("Meet Mink, your guardian").performClick()

        // Wait for the dashboard's own action, then assert the tier/model card and
        // the enable switch are present. No switch is ever toggled, so nothing
        // downloads and no service starts. The alertness card adds per-source
        // mute switches, so the screen legitimately has several toggleables now;
        // the enable switch is the first in the tree (inside the tier card).
        awaitText("Sweep now")
        composeRule.onNodeWithText("Guardian").assertIsDisplayed()
        composeRule.onNodeWithText("Talk to Mink").assertIsDisplayed()
        composeRule.onAllNodes(isToggleable()).onFirst().assertIsDisplayed()
    }

    @Test
    fun backNavigation_returnsHome() {
        awaitText("What your phone reveals")

        composeRule.onNodeWithText("Meet Mink, your guardian").performClick()
        awaitText("Sweep now")

        composeRule.onNodeWithContentDescription("Back").performClick()

        // Back on home: the hero copy is unique to the home screen.
        awaitText("What your phone reveals")
        composeRule.onNodeWithText("What your phone reveals").assertIsDisplayed()
    }

    // ---- helpers ----

    /** Wait until a node with [text] exists, tolerating the async start spinner. */
    private fun awaitText(text: String) {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Scroll the home list until the row bearing [text] is composed. */
    private fun scrollToText(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
