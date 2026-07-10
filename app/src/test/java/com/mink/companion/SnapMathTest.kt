package com.mink.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapMathTest {

    private val screenW = 1080
    private val screenH = 1920
    private val viewW = 200
    private val viewH = 260

    @Test
    fun snapsToLeftWhenCentreOnLeftHalf() {
        val p = SnapMath.snap(
            rawX = 40,
            rawY = 500,
            viewWidth = viewW,
            viewHeight = viewH,
            screenWidth = screenW,
            screenHeight = screenH,
            margin = 12,
        )
        assertFalse(p.snappedToRight)
        assertEquals(12, p.x)
    }

    @Test
    fun snapsToRightWhenCentreOnRightHalf() {
        val p = SnapMath.snap(
            rawX = 800,
            rawY = 500,
            viewWidth = viewW,
            viewHeight = viewH,
            screenWidth = screenW,
            screenHeight = screenH,
            margin = 12,
        )
        assertTrue(p.snappedToRight)
        // Flush against the right edge, inset by the margin.
        assertEquals(screenW - viewW - 12, p.x)
    }

    @Test
    fun clampsVerticalIntoScreen() {
        val tooHigh = SnapMath.snap(0, -500, viewW, viewH, screenW, screenH, margin = 10)
        assertEquals(10, tooHigh.y)

        val tooLow = SnapMath.snap(0, 99999, viewW, viewH, screenW, screenH, margin = 10)
        assertEquals(screenH - viewH - 10, tooLow.y)
    }

    @Test
    fun neverGoesOffScreenWhenViewLargerThanScreen() {
        val p = SnapMath.snap(
            rawX = -50,
            rawY = -50,
            viewWidth = screenW + 500,
            viewHeight = screenH + 500,
            screenWidth = screenW,
            screenHeight = screenH,
            margin = 8,
        )
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun centreExactlyOnMidlineSnapsRight() {
        // Centre == screenWidth / 2 counts as the right half.
        val rawX = screenW / 2 - viewW / 2
        val p = SnapMath.snap(rawX, 100, viewW, viewH, screenW, screenH, margin = 0)
        assertTrue(p.snappedToRight)
    }
}
