package com.mink.signals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the permissioned provider helpers. Coordinate coarsening
 * is arithmetic only, so it runs on the plain JVM under testDebugUnitTest with
 * no Android framework involved.
 */
class PermissionedFormatTest {

    @Test
    fun coarseCoordinate_roundsToTwoDecimalsByDefault() {
        assertEquals("37.42", PermissionedFormat.coarseCoordinate(37.42199))
        assertEquals("-122.08", PermissionedFormat.coarseCoordinate(-122.08403))
    }

    @Test
    fun coarseCoordinate_roundsHalfUpAwayFromDetail() {
        // 37.425 -> 37.43 (rounded, not the exact reading).
        assertEquals("37.43", PermissionedFormat.coarseCoordinate(37.425))
    }

    @Test
    fun coarseCoordinate_honoursDecimalArgument() {
        assertEquals("37.422", PermissionedFormat.coarseCoordinate(37.42199, decimals = 3))
        assertEquals("37.0", PermissionedFormat.coarseCoordinate(37.42199, decimals = 0))
    }

    @Test
    fun coarseCoordinate_dropsPrecisionSoExactFixNeverShows() {
        val exact = 51.5073219
        val shown = PermissionedFormat.coarseCoordinate(exact)
        assertTrue("coarsened value should be shorter than the exact reading",
            shown.length < exact.toString().length)
        assertEquals("51.51", shown)
    }
}
