package com.mink.companion

import androidx.compose.ui.graphics.Color

/**
 * The retro 8-bit blue Mink, expressed as pixel grids in code (no image asset).
 *
 * Each frame is a list of equal-intent rows of single-character cells; a
 * character maps to a palette colour (or transparent). Rows may be authored at
 * different lengths for readability and are padded to a common width by
 * [toGrid], which is the one piece of pure logic worth testing.
 *
 * Palette (from the build contract):
 *   B blue 0xFF2E6BFF, D dark 0xFF1B3A8A, C cyan 0xFF39C6E0,
 *   W white 0xFFEEF3FF, K ink 0xFF0A0E17, '.' transparent.
 */
object MinkSpriteArt {

    val Blue = Color(0xFF2E6BFF)
    val Dark = Color(0xFF1B3A8A)
    val Cyan = Color(0xFF39C6E0)
    val White = Color(0xFFEEF3FF)
    val Ink = Color(0xFF0A0E17)

    private val palette: Map<Char, Color> = mapOf(
        'B' to Blue,
        'D' to Dark,
        'C' to Cyan,
        'W' to White,
        'K' to Ink,
    )

    /** Every character that renders as a solid cell; anything else is transparent. */
    val opaqueChars: Set<Char> = palette.keys

    /** One frame of the sprite plus how it sits and how long it holds. */
    data class Frame(
        val rows: List<String>,
        /** Vertical bob in sprite-pixel units (negative lifts the sprite up). */
        val bob: Int = 0,
        /** How long this frame shows before advancing, in milliseconds. */
        val holdMs: Long = 420L,
    )

    /** A rectangular grid of optional colours, ready for the canvas renderer. */
    class Grid internal constructor(
        val width: Int,
        val height: Int,
        private val cells: Array<Array<Color?>>,
    ) {
        /** The colour at row [r], column [c], or null when transparent. */
        fun colorAt(r: Int, c: Int): Color? = cells[r][c]
    }

    /**
     * Pad every row to the widest row with transparent cells and resolve each
     * character to a palette colour. Unknown characters resolve to transparent
     * so authoring can never crash the renderer.
     */
    fun toGrid(rows: List<String>): Grid {
        val height = rows.size
        val width = rows.maxOfOrNull { it.length } ?: 0
        val cells = Array(height) { r ->
            val row = rows[r]
            Array<Color?>(width) { c ->
                if (c < row.length) palette[row[c]] else null
            }
        }
        return Grid(width = width, height = height, cells = cells)
    }

    // --- Body parts, composed into full frames so expressions stay in sync. ---

    private val ears = listOf(
        "..D........D....",
        ".DBD......DBD...",
        ".DBBD....DBBD...",
        ".DBBBDDDDBBBBD..",
        "..DBBBBBBBBBBD..",
        ".DBBBBBBBBBBBBD.",
        ".DBBBBBBBBBBBBD.",
    )

    private val lower = listOf(
        ".DBBBBBBBBBBBBD.",
        ".DBBBBCCCCBBBBD.",
        ".DBBBCWWWWCBBBD.",
        "..DBBCWKKWCBBD..",
        "..DBBBCWWCBBBD..",
        "...DBBBBBBBBD...",
        "....DD....DD....",
    )

    // Three-row eye bands; the middle row carries the pupils.
    private val eyesOpen = listOf(
        "DBBWWBBBBBBWWBBD",
        "DBBWKBBBBBBWKBBD",
        "DBBCWBBBBBBCWBBD",
    )
    private val eyesBlink = listOf(
        "DBBBBBBBBBBBBBBD",
        "DBBKKBBBBBBKKBBD",
        "DBBBBBBBBBBBBBBD",
    )
    private val eyesHappy = listOf(
        "DBBBBBBBBBBBBBBD",
        "DBWKKWBBBBWKKWBD",
        "DBBBBBBBBBBBBBBD",
    )
    private val eyesAlert = listOf(
        "DBWWWBBBBBBWWWBD",
        "DBWKWBBBBBBWKWBD",
        "DBWWWBBBBBBWWWBD",
    )
    private val eyesCurious = listOf(
        "DBBWWBBBBBBWWBBD",
        "DBBBWKBBBBBBWKD.",
        "DBBWWBBBBBBWWBBD",
    )
    private val eyesThinking = listOf(
        "DBBWKBBBBBBWKBBD",
        "DBBWWBBBBBBWWBBD",
        "DBBBBBBBBBBBBBBD",
    )
    // Closed, gently drooping lids so sleep reads as asleep, not as glowing eyes;
    // distinct from the flat momentary blink and carried by the slow sleep bob.
    private val eyesSleep = listOf(
        "DBBBBBBBBBBBBBBD",
        "DBBKBBBBBBBBKBBD",
        "DBBBKKBBBBKKBBBD",
    )

    private fun frame(eyes: List<String>, bob: Int = 0, holdMs: Long = 420L): Frame =
        Frame(rows = ears + eyes + lower, bob = bob, holdMs = holdMs)

    /**
     * The animation loop for a mood. Frames cycle in order using each frame's
     * [Frame.holdMs]; a single-frame list renders static.
     */
    fun framesFor(mood: CompanionMood): List<Frame> = when (mood) {
        CompanionMood.IDLE -> listOf(
            frame(eyesOpen, holdMs = 2600L),
            frame(eyesBlink, holdMs = 140L),
        )
        CompanionMood.HAPPY -> listOf(
            frame(eyesHappy, bob = 0, holdMs = 220L),
            frame(eyesHappy, bob = -1, holdMs = 220L),
        )
        CompanionMood.CURIOUS -> listOf(
            frame(eyesCurious, holdMs = 1100L),
            frame(eyesOpen, holdMs = 420L),
        )
        CompanionMood.ALERT -> listOf(
            frame(eyesAlert, bob = -1, holdMs = 170L),
            frame(eyesAlert, bob = 0, holdMs = 170L),
        )
        CompanionMood.THINKING -> listOf(
            frame(eyesThinking, holdMs = 700L),
            frame(eyesOpen, holdMs = 700L),
        )
        CompanionMood.SLEEPING -> listOf(
            frame(eyesSleep, bob = 0, holdMs = 1600L),
            frame(eyesSleep, bob = 1, holdMs = 1600L),
        )
    }
}
