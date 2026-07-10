package com.mink.companion

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * Draws the 8-bit blue Mink from [MinkSpriteArt] onto a [Canvas], one solid
 * square per pixel cell (nearest-neighbour blocky scaling, no smoothing). The
 * frames cycle to animate the given [mood].
 */
@Composable
fun MinkSprite(
    mood: CompanionMood,
    modifier: Modifier = Modifier,
) {
    val frames = remember(mood) { MinkSpriteArt.framesFor(mood) }
    var index by remember(mood) { mutableIntStateOf(0) }

    LaunchedEffect(mood) {
        if (frames.size <= 1) return@LaunchedEffect
        var i = 0
        while (true) {
            delay(frames[i].holdMs)
            i = (i + 1) % frames.size
            index = i
        }
    }

    val frame = frames[index.coerceIn(0, frames.lastIndex)]
    val grid = remember(frame) { MinkSpriteArt.toGrid(frame.rows) }

    Canvas(modifier = modifier) {
        val cols = grid.width
        val rows = grid.height
        if (cols == 0 || rows == 0) return@Canvas

        // One shared cell size keeps the pixels square; centre the grid.
        val cell = min(size.width / cols, size.height / rows)
        val drawnWidth = cell * cols
        val drawnHeight = cell * rows
        val originX = (size.width - drawnWidth) / 2f
        val originY = (size.height - drawnHeight) / 2f + frame.bob * cell

        val cellSize = Size(cell, cell)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color: Color = grid.colorAt(r, c) ?: continue
                drawRect(
                    color = color,
                    topLeft = Offset(originX + c * cell, originY + r * cell),
                    size = cellSize,
                )
            }
        }
    }
}
