package com.mink.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure routing logic behind the companion bubble deep link: which
 * routes may be opened, and that the relay only holds known destinations and
 * clears once consumed.
 */
class CompanionDeepLinkTest {

    @Test
    fun guardianRouteIsDeepLinkable() {
        assertTrue(MinkRoute.isDeepLinkable(MinkRoute.GUARDIAN))
    }

    @Test
    fun nullAndUnknownRoutesAreNotDeepLinkable() {
        assertFalse(MinkRoute.isDeepLinkable(null))
        assertFalse(MinkRoute.isDeepLinkable("not-a-route"))
        // A parameterised route is not a bare destination we can navigate to.
        assertFalse(MinkRoute.isDeepLinkable(MinkRoute.CATEGORY))
    }

    @Test
    fun offerKeepsOnlyKnownRoutes() {
        CompanionDeepLink.consume()

        CompanionDeepLink.offer("not-a-route")
        assertNull(CompanionDeepLink.pendingRoute.value)

        CompanionDeepLink.offer(MinkRoute.GUARDIAN)
        assertEquals(MinkRoute.GUARDIAN, CompanionDeepLink.pendingRoute.value)

        CompanionDeepLink.consume()
        assertNull(CompanionDeepLink.pendingRoute.value)
    }

    @Test
    fun offeringNullDoesNotOverwriteAPendingRoute() {
        CompanionDeepLink.consume()
        CompanionDeepLink.offer(MinkRoute.GUARDIAN)
        CompanionDeepLink.offer(null)
        assertEquals(MinkRoute.GUARDIAN, CompanionDeepLink.pendingRoute.value)
        CompanionDeepLink.consume()
    }
}
