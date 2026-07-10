package com.mink.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MinkSpriteArtTest {

    @Test
    fun toGridPadsRaggedRowsToWidestRow() {
        val grid = MinkSpriteArt.toGrid(listOf("BB", "BBBB", "B"))
        assertEquals(4, grid.width)
        assertEquals(3, grid.height)
        // Short rows are padded with transparent cells, not clipped.
        assertNull(grid.colorAt(0, 3))
        assertNull(grid.colorAt(2, 1))
        assertNotNull(grid.colorAt(1, 3))
    }

    @Test
    fun dotIsTransparentAndKnownCharsAreOpaque() {
        val grid = MinkSpriteArt.toGrid(listOf(".BDCWK"))
        assertNull(grid.colorAt(0, 0))
        assertNotNull(grid.colorAt(0, 1)) // B
        assertNotNull(grid.colorAt(0, 2)) // D
        assertNotNull(grid.colorAt(0, 3)) // C
        assertNotNull(grid.colorAt(0, 4)) // W
        assertNotNull(grid.colorAt(0, 5)) // K
    }

    @Test
    fun unknownCharsResolveToTransparentRatherThanCrash() {
        val grid = MinkSpriteArt.toGrid(listOf("B?z B"))
        assertNotNull(grid.colorAt(0, 0))
        assertNull(grid.colorAt(0, 1))
        assertNull(grid.colorAt(0, 2))
        assertNull(grid.colorAt(0, 3))
        assertNotNull(grid.colorAt(0, 4))
    }

    @Test
    fun everyMoodProducesRectangularNonEmptyFrames() {
        for (mood in CompanionMood.values()) {
            val frames = MinkSpriteArt.framesFor(mood)
            assertTrue("mood $mood has no frames", frames.isNotEmpty())
            for (frame in frames) {
                val grid = MinkSpriteArt.toGrid(frame.rows)
                assertTrue(grid.width > 0)
                assertTrue(grid.height > 0)
                // Every cell in the padded grid is addressable.
                for (r in 0 until grid.height) {
                    for (c in 0 until grid.width) {
                        grid.colorAt(r, c) // must not throw
                    }
                }
            }
        }
    }

    @Test
    fun idleAnimatesWithMoreThanOneFrame() {
        // A blink needs at least two frames to cycle between.
        assertTrue(MinkSpriteArt.framesFor(CompanionMood.IDLE).size >= 2)
    }

    @Test
    fun opaqueCharsCoverThePaletteLetters() {
        assertTrue(MinkSpriteArt.opaqueChars.containsAll(listOf('B', 'D', 'C', 'W', 'K')))
    }
}
