package com.mink.signals

import com.mink.signals.AppTaxonomy.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the installed-app lifestyle categoriser. No Android
 * framework is touched, so these run on the plain JVM under testDebugUnitTest.
 */
class AppTaxonomyTest {

    @Test
    fun categorize_matchesKnownBasePackages() {
        assertEquals(setOf(AppCategory.DATING), AppTaxonomy.categorize("com.tinder"))
        assertEquals(setOf(AppCategory.VPN), AppTaxonomy.categorize("com.nordvpn.android"))
        assertEquals(setOf(AppCategory.CRYPTO), AppTaxonomy.categorize("io.metamask"))
        assertEquals(setOf(AppCategory.SOCIAL), AppTaxonomy.categorize("com.instagram.android"))
    }

    @Test
    fun categorize_matchesSubPackagesButNotSiblings() {
        assertEquals(setOf(AppCategory.DATING), AppTaxonomy.categorize("com.tinder.debug"))
        // A sibling that merely shares a prefix must not match.
        assertTrue(AppTaxonomy.categorize("com.tinderbox.app").isEmpty())
    }

    @Test
    fun categorize_isCaseInsensitiveAndTrims() {
        assertEquals(setOf(AppCategory.FINANCE), AppTaxonomy.categorize("  COM.Venmo  "))
    }

    @Test
    fun categorize_unknownPackageYieldsEmpty() {
        assertTrue(AppTaxonomy.categorize("com.example.unknown").isEmpty())
        assertTrue(AppTaxonomy.categorize("").isEmpty())
        assertTrue(AppTaxonomy.categorize("   ").isEmpty())
    }

    @Test
    fun profile_countsPerCategoryAcrossList() {
        val packages = listOf(
            "com.tinder",
            "com.bumble.app",
            "com.nordvpn.android",
            "com.coinbase.android",
            "com.example.notepad",
            "com.instagram.android",
        )
        val profile = AppTaxonomy.profile(packages)

        assertEquals(2, profile[AppCategory.DATING])
        assertEquals(1, profile[AppCategory.VPN])
        assertEquals(1, profile[AppCategory.CRYPTO])
        assertEquals(1, profile[AppCategory.SOCIAL])
        assertFalse(profile.containsKey(AppCategory.TRAVEL))
    }

    @Test
    fun profile_ordersByCategoryDeclaration() {
        // Supplied out of declaration order; result should follow enum order:
        // FINANCE before SOCIAL before DATING before VPN.
        val packages = listOf(
            "com.nordvpn.android",
            "com.tinder",
            "com.instagram.android",
            "com.paypal.android.p2pmobile",
        )
        val order = AppTaxonomy.profile(packages).keys.toList()
        assertEquals(
            listOf(AppCategory.FINANCE, AppCategory.SOCIAL, AppCategory.DATING, AppCategory.VPN),
            order,
        )
    }

    @Test
    fun profile_emptyInputYieldsEmptyMap() {
        assertTrue(AppTaxonomy.profile(emptyList()).isEmpty())
    }
}
