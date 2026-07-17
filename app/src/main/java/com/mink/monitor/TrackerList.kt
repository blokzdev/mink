package com.mink.monitor

import android.content.Context

/**
 * A bundled, offline set of known tracker / analytics / advertising domains.
 * Matching is on-device only and conservative: a host is a tracker if it equals
 * a listed registrable domain or is a subdomain of one. The list is curated to
 * well-known SDKs (see `assets/trackers.txt`) so a match is meaningful.
 *
 * Pure and testable: the matching lives in [classify], which takes the parsed
 * set, and [fromLines] parses the asset text. [load] is the only Android touch.
 */
class TrackerList private constructor(private val domains: Set<String>) {

    /** How many domains the list holds; used only for diagnostics/tests. */
    val size: Int get() = domains.size

    /**
     * Whether [host] is a known tracker: an exact match, or any parent domain is
     * listed (so `x.y.doubleclick.net` matches `doubleclick.net`). Case- and
     * trailing-dot-insensitive.
     */
    fun isTracker(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (h in domains) return true
        var i = h.indexOf('.')
        while (i in 0 until h.length - 1) {
            if (h.substring(i + 1) in domains) return true
            i = h.indexOf('.', i + 1)
        }
        return false
    }

    companion object {
        /** Parse asset text: trim, drop blanks and `#` comments, lowercase. */
        fun fromLines(text: String): TrackerList {
            val set = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.trimEnd('.').lowercase() }
                .toHashSet()
            return TrackerList(set)
        }

        /** Load the bundled list; an empty list on any failure (matching nothing). */
        fun load(context: Context): TrackerList = runCatching {
            context.assets.open(ASSET).bufferedReader().use { fromLines(it.readText()) }
        }.getOrDefault(TrackerList(emptySet()))

        private const val ASSET = "trackers.txt"
    }
}
