package com.mink.companion

import com.mink.guardian.APP_ACCESS_CATEGORY
import com.mink.guardian.AlertLevel
import com.mink.guardian.GuardianAlert
import com.mink.guardian.SENSOR_USE_CATEGORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [CompanionSpeechPolicy]: the calm engine that gates which
 * fresh alert the companion speaks. Covers the warning/critical filter, the
 * burst-merge to the richest finding, the throttle and dedup windows, the
 * immutable-critical bypass, and the timestamps a speak records. Time is
 * injected, so every window boundary is asserted deterministically.
 */
class CompanionSpeechPolicyTest {

    private fun alert(
        id: String = "alert-1",
        level: AlertLevel = AlertLevel.WARNING,
        title: String = "Something changed",
        body: String = "Body.",
        categoryId: String? = "network",
        createdAtEpochMs: Long = 0L,
        fromImmutableRule: Boolean = false,
    ): GuardianAlert = GuardianAlert(
        id = id,
        level = level,
        title = title,
        body = body,
        categoryId = categoryId,
        createdAtEpochMs = createdAtEpochMs,
        fromImmutableRule = fromImmutableRule,
    )

    // ---- filter ----

    @Test
    fun filtersToWarningAndCritical() {
        val policy = CompanionSpeechPolicy()
        assertNull(policy.chooseToSpeak(emptyList(), nowMs = 0L))
        // Nothing above a suggestion is ever spoken.
        assertNull(
            policy.chooseToSpeak(
                listOf(alert(id = "i", level = AlertLevel.INFO), alert(id = "s", level = AlertLevel.SUGGESTION)),
                nowMs = 0L,
            ),
        )
        // A single warning among the noise is the one that surfaces.
        val warning = alert(id = "w", level = AlertLevel.WARNING, title = "Warning")
        assertEquals(
            warning,
            policy.chooseToSpeak(
                listOf(alert(id = "i2", level = AlertLevel.INFO), warning, alert(id = "s2", level = AlertLevel.SUGGESTION)),
                nowMs = 0L,
            ),
        )
    }

    // ---- burst-merge: pick the richest of a sweep ----

    @Test
    fun burstMergePicksHighestSeverity() {
        val policy = CompanionSpeechPolicy()
        val warning = alert(id = "w", level = AlertLevel.WARNING, title = "Camera on")
        val critical = alert(id = "c", level = AlertLevel.CRITICAL, title = "App can see and hear you")
        // Order in the batch must not matter; the critical always wins.
        assertEquals(critical, policy.chooseToSpeak(listOf(warning, critical), nowMs = 0L))
    }

    @Test
    fun burstMergeTieBreaksOnLongerBodyThenNewer() {
        val byBody = CompanionSpeechPolicy()
        val shortBody = alert(id = "short", level = AlertLevel.WARNING, title = "Short", body = "hi")
        val longBody = alert(
            id = "long",
            level = AlertLevel.WARNING,
            title = "Long",
            body = "A much longer body describing camera and microphone use",
        )
        // Same severity: the finding with the richer body wins.
        assertEquals(longBody, byBody.chooseToSpeak(listOf(shortBody, longBody), nowMs = 0L))

        val byTime = CompanionSpeechPolicy()
        val older = alert(id = "old", level = AlertLevel.WARNING, title = "Older", body = "same", createdAtEpochMs = 100L)
        val newer = alert(id = "new", level = AlertLevel.WARNING, title = "Newer", body = "same", createdAtEpochMs = 200L)
        // Same severity and body length: the newer finding wins.
        assertEquals(newer, byTime.chooseToSpeak(listOf(older, newer), nowMs = 0L))
    }

    @Test
    fun burstMergeIsOrderIndependent() {
        // The same batch in any order resolves to the same richest finding.
        val warning = alert(id = "w", level = AlertLevel.WARNING, title = "Camera on", body = "short")
        val critical = alert(id = "c", level = AlertLevel.CRITICAL, title = "See and hear you", body = "richer body")
        val other = alert(id = "o", level = AlertLevel.WARNING, title = "Location used", body = "some body")

        val forward = CompanionSpeechPolicy().chooseToSpeak(listOf(warning, critical, other), nowMs = 0L)
        val reversed = CompanionSpeechPolicy().chooseToSpeak(listOf(other, critical, warning), nowMs = 0L)
        assertEquals(critical, forward)
        assertEquals(forward, reversed)
    }

    @Test
    fun dedupMapDoesNotWedgeOverALongRun() {
        // Speak many distinct findings spaced well beyond the dedup window. The
        // dedup map is evicted as time moves on, so behaviourally the policy keeps
        // working: a brand-new finding still speaks, and a fresh repeat of it is
        // still deduped within the window. Asserts behaviour, not the map itself.
        val policy = CompanionSpeechPolicy(throttleMs = 0L, dedupMs = 5_000L)
        var now = 0L
        repeat(50) { i ->
            val a = alert(id = "k$i", title = "Finding $i", categoryId = SENSOR_USE_CATEGORY)
            assertEquals(a, policy.chooseToSpeak(listOf(a), nowMs = now))
            now += 10_000L
        }
        val fresh = alert(id = "fresh", title = "Fresh finding", categoryId = SENSOR_USE_CATEGORY)
        assertEquals(fresh, policy.chooseToSpeak(listOf(fresh), nowMs = now))
        val repeat = alert(id = "fresh2", title = "Fresh finding", categoryId = SENSOR_USE_CATEGORY)
        assertNull(policy.chooseToSpeak(listOf(repeat), nowMs = now + 1_000L))
    }

    // ---- throttle: at most one remark per window ----

    @Test
    fun throttleSuppressesASecondRemarkWithinTheWindow() {
        val policy = CompanionSpeechPolicy()
        val first = alert(id = "1", level = AlertLevel.WARNING, title = "First finding")
        val second = alert(id = "2", level = AlertLevel.WARNING, title = "Second finding")
        assertEquals(first, policy.chooseToSpeak(listOf(first), nowMs = 0L))
        // A different key one millisecond inside the 10s window is still throttled.
        assertNull(policy.chooseToSpeak(listOf(second), nowMs = 9_999L))
        // Exactly at the window edge it speaks again.
        assertEquals(second, policy.chooseToSpeak(listOf(second), nowMs = 10_000L))
    }

    // ---- dedup: ignore a repeat of the same finding within the window ----

    @Test
    fun dedupSuppressesTheSameKeyWithinTheWindow() {
        // The default 5s dedup window sits inside the default 10s throttle window,
        // so throttle would mask dedup. Shorten the throttle to expose dedup while
        // keeping the real 5s dedup window under test.
        val policy = CompanionSpeechPolicy(throttleMs = 1_000L)
        val cameraA = alert(id = "a", level = AlertLevel.WARNING, title = "Camera on", categoryId = SENSOR_USE_CATEGORY)
        assertEquals(cameraA, policy.chooseToSpeak(listOf(cameraA), nowMs = 0L))

        // The throttle (1s) is satisfied at 2s, but the same key is still deduped.
        val cameraB = alert(id = "b", level = AlertLevel.WARNING, title = "Camera on", categoryId = SENSOR_USE_CATEGORY)
        assertNull(policy.chooseToSpeak(listOf(cameraB), nowMs = 2_000L))

        // A different key at the same moment is not deduped and speaks.
        val mic = alert(id = "m", level = AlertLevel.WARNING, title = "Mic on", categoryId = SENSOR_USE_CATEGORY)
        assertEquals(mic, policy.chooseToSpeak(listOf(mic), nowMs = 2_000L))

        // Once the 5s dedup window from its last speak has passed, the camera key
        // speaks again.
        val cameraC = alert(id = "c", level = AlertLevel.WARNING, title = "Camera on", categoryId = SENSOR_USE_CATEGORY)
        assertEquals(cameraC, policy.chooseToSpeak(listOf(cameraC), nowMs = 5_000L))
    }

    // ---- never mute a real risk ----

    @Test
    fun immutableCriticalBypassesThrottleAndDedup() {
        val policy = CompanionSpeechPolicy()
        val first = alert(id = "1", level = AlertLevel.WARNING, title = "Camera on", categoryId = SENSOR_USE_CATEGORY)
        assertEquals(first, policy.chooseToSpeak(listOf(first), nowMs = 0L))
        // A normal warning right after is throttled into silence.
        assertNull(
            policy.chooseToSpeak(
                listOf(alert(id = "2", level = AlertLevel.WARNING, title = "Location used")),
                nowMs = 100L,
            ),
        )

        // An immutable-rule critical speaks immediately despite the throttle.
        val combo = alert(
            id = "3",
            level = AlertLevel.CRITICAL,
            title = "App can see, hear, and locate you",
            categoryId = APP_ACCESS_CATEGORY,
            fromImmutableRule = true,
        )
        assertEquals(combo, policy.chooseToSpeak(listOf(combo), nowMs = 200L))
        // The very same immutable critical again, well inside both windows, still
        // speaks: a real surveillance combo is never silenced.
        assertEquals(combo.copy(id = "4"), policy.chooseToSpeak(listOf(combo.copy(id = "4")), nowMs = 300L))
    }

    // ---- timestamps recorded on speak ----

    @Test
    fun bypassStillRecordsTimestamps() {
        val policy = CompanionSpeechPolicy()
        val combo = alert(
            id = "c",
            level = AlertLevel.CRITICAL,
            title = "Surveillance combo",
            categoryId = APP_ACCESS_CATEGORY,
            fromImmutableRule = true,
        )
        // The bypass speaks and records lastSpokeMs even though it skipped the checks.
        assertEquals(combo, policy.chooseToSpeak(listOf(combo), nowMs = 0L))
        // So a following normal warning within the throttle window is suppressed.
        val normal = alert(id = "n", level = AlertLevel.WARNING, title = "Something else")
        assertNull(policy.chooseToSpeak(listOf(normal), nowMs = 1_000L))
    }
}
