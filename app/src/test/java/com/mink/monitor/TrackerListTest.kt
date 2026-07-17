package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the tracker classifier. */
class TrackerListTest {

    private val list = TrackerList.fromLines(
        """
        # a comment
        doubleclick.net
        graph.facebook.com

        Adjust.com
        static.tapjoy.com.
        """.trimIndent(),
    )

    @Test
    fun parsesDroppingCommentsAndBlanks() {
        assertEquals(4, list.size)
    }

    @Test
    fun matchesExactDomain() {
        assertTrue(list.isTracker("doubleclick.net"))
    }

    @Test
    fun matchesSubdomains() {
        assertTrue(list.isTracker("ads.g.doubleclick.net"))
        assertTrue(list.isTracker("stats.graph.facebook.com"))
    }

    @Test
    fun isCaseAndTrailingDotInsensitive() {
        assertTrue(list.isTracker("DoubleClick.NET"))
        assertTrue(list.isTracker("adjust.com."))
        assertTrue(list.isTracker("static.tapjoy.com"))     // list entry had a trailing dot
    }

    @Test
    fun doesNotMatchUnrelatedOrParentOfListedHost() {
        assertFalse(list.isTracker("example.com"))
        assertFalse(list.isTracker("facebook.com"))          // listed entry is graph.facebook.com, not facebook.com
        assertFalse(list.isTracker("notdoubleclick.net"))    // suffix but not a subdomain boundary
        assertFalse(list.isTracker(""))
    }

    @Test
    fun emptyListMatchesNothing() {
        val empty = TrackerList.fromLines("# only comments\n\n")
        assertEquals(0, empty.size)
        assertFalse(empty.isTracker("doubleclick.net"))
    }
}
