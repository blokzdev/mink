package com.mink

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mink.ui.screens.OnboardingScreen
import com.mink.ui.theme.MinkTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the onboarding pager through its real buttons, independent of the
 * persisted first-run flag. Hosting [OnboardingScreen] directly keeps the walk
 * deterministic regardless of test ordering, while still tapping the same
 * "Next"/"Get started" controls a first-run user would.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun walkingOnboarding_reachesDone() {
        var done = false
        composeRule.setContent {
            MinkTheme {
                OnboardingScreen(onDone = { done = true })
            }
        }

        // First page copy from OnboardingScreen's PAGES.
        composeRule.onNodeWithText("What your phone gives away").assertIsDisplayed()

        // Four pages: three "Next" taps advance to the last, which shows "Get started".
        repeat(3) {
            composeRule.onNodeWithText("Next").performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Get started").performClick()

        composeRule.waitUntil(TIMEOUT_MS) { done }
        assertTrue("onDone should fire after finishing onboarding", done)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
