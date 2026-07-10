package com.mink.companion

import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry for the floating companion: where a dragged overlay should
 * settle. Kept free of any Android type so it can be unit tested directly.
 *
 * The companion snaps to whichever side edge its centre is closest to, and
 * stays fully on screen with a small inset from every edge.
 */
object SnapMath {

    /** Result of a snap: the top-left the window should move to. */
    data class Placement(
        val x: Int,
        val y: Int,
        val snappedToRight: Boolean,
    )

    /**
     * Snap a window of [viewWidth] x [viewHeight] whose current top-left is
     * ([rawX], [rawY]) to the nearest side edge of a [screenWidth] x
     * [screenHeight] screen, keeping a [margin] inset. Never returns an
     * off-screen position, even when the view is larger than the screen.
     */
    fun snap(
        rawX: Int,
        rawY: Int,
        viewWidth: Int,
        viewHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        margin: Int = 0,
    ): Placement {
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)

        val leftX = clamp(margin, 0, maxX)
        val rightX = clamp(maxX - margin, 0, maxX)

        val centre = rawX + viewWidth / 2
        val snappedToRight = centre >= screenWidth / 2
        val x = if (snappedToRight) rightX else leftX

        val y = clamp(rawY, margin, maxY - margin)

        return Placement(x = x, y = y, snappedToRight = snappedToRight)
    }

    /**
     * Clamp [value] into the range spanned by [a] and [b] regardless of their
     * order. When the usable range collapses (view bigger than screen) this
     * still yields a valid, on-screen coordinate rather than throwing.
     */
    private fun clamp(value: Int, a: Int, b: Int): Int {
        val lo = min(a, b)
        val hi = max(a, b)
        return value.coerceIn(lo, hi)
    }
}
