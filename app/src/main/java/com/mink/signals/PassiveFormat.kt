package com.mink.signals

import java.util.concurrent.TimeUnit

/**
 * Small pure formatting helpers shared by the passive providers. Kept free of
 * any Android dependency so the branching logic can be unit tested off device.
 */
internal object PassiveFormat {

    /** Renders a millisecond duration as a compact "Nd Nh Nm" string. */
    fun formatDuration(millis: Long): String {
        val clamped = if (millis < 0) 0 else millis
        val days = TimeUnit.MILLISECONDS.toDays(clamped)
        val hours = TimeUnit.MILLISECONDS.toHours(clamped) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(clamped) % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${minutes}m"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** Renders a battery level/scale pair as a whole-number percentage string. */
    fun batteryPercent(level: Int, scale: Int): String {
        if (level < 0 || scale <= 0) return "unknown"
        return "${level * 100 / scale}%"
    }

    /** Formats raw signature bytes as an upper-case colon-separated hex digest. */
    fun hexDigest(bytes: ByteArray): String =
        bytes.joinToString(":") { "%02X".format(it) }
}
