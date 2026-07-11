package com.mink.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SensorSessionTracker]: session assembly across device
 * ids, absorption of the camera registration-time replay, the hourly emission
 * cap with suppression counting and window rollover, the alert-worthy
 * always-emit rule, per-sensor budget independence, and monotonic durations
 * under wall-clock jumps. The clocks are injected through method parameters,
 * so every branch is asserted deterministically.
 */
class SensorSessionTrackerTest {

    /**
     * One busy/idle edge with the wall and monotonic clocks in step. Tests
     * that need the clocks to diverge call [SensorSessionTracker.deviceBusy]
     * directly.
     */
    private fun SensorSessionTracker.edge(
        sensor: WatchedSensor,
        deviceId: String,
        busy: Boolean,
        nowMs: Long,
        screenOff: Boolean = false,
        likelyApp: String? = null,
        sourceHint: String? = null,
    ): TrackedSession? = deviceBusy(
        sensor, deviceId, busy,
        nowMs = nowMs, elapsedMs = nowMs,
        screenOff = screenOff, likelyApp = likelyApp, sourceHint = sourceHint,
    )

    /** Open then close a single device id, returning whatever the close emits. */
    private fun SensorSessionTracker.openClose(
        sensor: WatchedSensor,
        deviceId: String,
        openMs: Long,
        closeMs: Long,
        screenOff: Boolean = false,
        likelyApp: String? = null,
        sourceHint: String? = null,
    ): TrackedSession? {
        edge(
            sensor, deviceId, busy = true,
            nowMs = openMs, screenOff = screenOff, likelyApp = likelyApp, sourceHint = sourceHint,
        )
        return edge(
            sensor, deviceId, busy = false,
            nowMs = closeMs, screenOff = false, likelyApp = null,
        )
    }

    // ---- session assembly ----

    @Test
    fun singleOpenCloseProducesOneSessionWithOpenEdgeContext() {
        val tracker = SensorSessionTracker()

        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = true,
                nowMs = 1_000L, screenOff = false, likelyApp = "Camera",
            ),
        )
        // The close edge carries different context; the session must keep the open edge's.
        val tracked = tracker.edge(
            WatchedSensor.CAMERA, "0", busy = false,
            nowMs = 13_000L, screenOff = true, likelyApp = "Other",
        )

        assertNotNull(tracked)
        val session = tracked!!.session
        assertEquals(WatchedSensor.CAMERA, session.sensor)
        assertEquals(1_000L, session.startMs)
        assertEquals(13_000L, session.endMs)
        assertEquals(12_000L, session.durationMs)
        assertFalse(session.screenWasOff)
        assertEquals("Camera", session.likelyApp)
        assertNull(session.sourceHint)
        assertEquals(0, tracked.alsoSuppressed)
    }

    @Test
    fun overlappingDeviceIdsMergeIntoOneSession() {
        val tracker = SensorSessionTracker()

        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = true,
                nowMs = 1_000L, screenOff = true, likelyApp = "Camera",
            ),
        )
        // A second id going busy joins the open session without re-capturing context.
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "1", busy = true,
                nowMs = 2_000L, screenOff = false, likelyApp = "Other",
            ),
        )
        // The first id closing does not end the session while the second is busy.
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = false,
                nowMs = 3_000L, screenOff = false, likelyApp = null,
            ),
        )
        val tracked = tracker.edge(
            WatchedSensor.CAMERA, "1", busy = false,
            nowMs = 5_000L, screenOff = false, likelyApp = null,
        )

        assertNotNull(tracked)
        val session = tracked!!.session
        assertEquals(1_000L, session.startMs)
        assertEquals(5_000L, session.endMs)
        assertTrue(session.screenWasOff)
        assertEquals("Camera", session.likelyApp)
    }

    @Test
    fun idleReplayBusyFalseIsIgnoredAndLaterSessionsStillWork() {
        val tracker = SensorSessionTracker()

        // The camera availability callback replays idle cameras on registration.
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = false,
                nowMs = 0L, screenOff = false, likelyApp = null,
            ),
        )
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "1", busy = false,
                nowMs = 0L, screenOff = false, likelyApp = null,
            ),
        )

        val tracked = tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 10_000L, closeMs = 22_000L)
        assertNotNull(tracked)
        assertEquals(10_000L, tracked!!.session.startMs)
        assertEquals(22_000L, tracked.session.endMs)
    }

    @Test
    fun duplicateBusyTrueForSameIdDoesNotDoubleTheSession() {
        val tracker = SensorSessionTracker()

        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = true,
                nowMs = 1_000L, screenOff = false, likelyApp = "Camera",
            ),
        )
        // The duplicate must not restart the session or re-capture context.
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = true,
                nowMs = 2_000L, screenOff = true, likelyApp = "Other",
            ),
        )

        val tracked = tracker.edge(
            WatchedSensor.CAMERA, "0", busy = false,
            nowMs = 3_000L, screenOff = false, likelyApp = null,
        )
        assertNotNull(tracked)
        assertEquals(1_000L, tracked!!.session.startMs)
        assertFalse(tracked.session.screenWasOff)
        assertEquals("Camera", tracked.session.likelyApp)
        // The set is empty again: a further close is the replay case, not a session.
        assertNull(
            tracker.edge(
                WatchedSensor.CAMERA, "0", busy = false,
                nowMs = 4_000L, screenOff = false, likelyApp = null,
            ),
        )
    }

    @Test
    fun micSessionTrackedBySessionIdCarriesSourceHintThrough() {
        val tracker = SensorSessionTracker()

        val tracked = tracker.openClose(
            WatchedSensor.MICROPHONE, "42",
            openMs = 1_000L, closeMs = 181_000L,
            likelyApp = "Phone", sourceHint = "a voice call",
        )

        assertNotNull(tracked)
        val session = tracked!!.session
        assertEquals(WatchedSensor.MICROPHONE, session.sensor)
        assertEquals(1_000L, session.startMs)
        assertEquals(181_000L, session.endMs)
        assertEquals("Phone", session.likelyApp)
        assertEquals("a voice call", session.sourceHint)
    }

    // ---- hourly cap ----

    @Test
    fun hourlyCapEmitsSixSuppressesTheRestAndReportsThemAfterRollover() {
        val tracker = SensorSessionTracker()

        // Six sessions emit; the hour window opens at the first emission (11_000).
        repeat(MAX_SENSOR_SESSIONS_PER_HOUR) { i ->
            val openMs = (i + 1) * 10_000L
            val tracked = tracker.openClose(WatchedSensor.CAMERA, "0", openMs = openMs, closeMs = openMs + 1_000L)
            assertNotNull(tracked)
            assertEquals(0, tracked!!.alsoSuppressed)
        }
        // The seventh and eighth are suppressed.
        assertNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 71_000L, closeMs = 72_000L))
        assertNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 81_000L, closeMs = 82_000L))

        // One hour after the window opened it rolls over; the next session emits
        // and carries the two suppressed ones.
        val rolled = tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_610_000L, closeMs = 3_612_000L)
        assertNotNull(rolled)
        assertEquals(2, rolled!!.alsoSuppressed)

        // The suppression count resets once it has been reported.
        val next = tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_620_000L, closeMs = 3_621_000L)
        assertNotNull(next)
        assertEquals(0, next!!.alsoSuppressed)
    }

    @Test
    fun rolloverBoundaryIsExactlyOneHourOfElapsedTime() {
        val tracker = SensorSessionTracker(maxPerHour = 1)

        // The window opens at the first emission: elapsed W = 10_000.
        val windowStart = 10_000L
        assertNotNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 5_000L, closeMs = windowStart))

        // One millisecond before the boundary the sensor stays suppressed.
        assertNull(
            tracker.openClose(
                WatchedSensor.CAMERA, "0",
                openMs = windowStart + 3_599_000L, closeMs = windowStart + 3_599_999L,
            ),
        )
        // At exactly W + one hour the window rolls over and the session emits.
        val rolled = tracker.openClose(
            WatchedSensor.CAMERA, "0",
            openMs = windowStart + 3_599_999L, closeMs = windowStart + 3_600_000L,
        )
        assertNotNull(rolled)
        assertEquals(1, rolled!!.alsoSuppressed)
    }

    @Test
    fun screenOffSessionEmitsEvenPastTheCap() {
        val tracker = SensorSessionTracker(maxPerHour = 1)

        assertNotNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 1_000L, closeMs = 2_000L))
        // The cap is reached: a screen-on session is suppressed.
        assertNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_000L, closeMs = 4_000L))

        // A screen-off camera session is alert-worthy at any duration, so it
        // always emits, and it reports the suppressed one.
        val tracked = tracker.openClose(
            WatchedSensor.CAMERA, "0",
            openMs = 5_000L, closeMs = 6_000L, screenOff = true,
        )
        assertNotNull(tracked)
        assertTrue(tracked!!.session.screenWasOff)
        assertEquals(1, tracked.alsoSuppressed)
    }

    @Test
    fun alertWorthyBypassDoesNotConsumeTheBudget() {
        val tracker = SensorSessionTracker(maxPerHour = 1)

        // A screen-off camera session emits through the bypass...
        val bypass = tracker.openClose(
            WatchedSensor.CAMERA, "0",
            openMs = 1_000L, closeMs = 2_000L, screenOff = true,
        )
        assertNotNull(bypass)

        // ...and the budget is untouched: a following screen-on session still emits.
        assertNotNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_000L, closeMs = 4_000L))
    }

    @Test
    fun suppressedCountResetsOnceAScreenOffEmissionReportsIt() {
        val tracker = SensorSessionTracker(maxPerHour = 1)

        assertNotNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 1_000L, closeMs = 2_000L))
        assertNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_000L, closeMs = 4_000L))

        // The first screen-off emission carries the suppressed count...
        val first = tracker.openClose(
            WatchedSensor.CAMERA, "0",
            openMs = 5_000L, closeMs = 6_000L, screenOff = true,
        )
        assertNotNull(first)
        assertEquals(1, first!!.alsoSuppressed)

        // ...and the next one starts from zero: nothing is reported twice.
        val second = tracker.openClose(
            WatchedSensor.CAMERA, "0",
            openMs = 7_000L, closeMs = 8_000L, screenOff = true,
        )
        assertNotNull(second)
        assertEquals(0, second!!.alsoSuppressed)
    }

    @Test
    fun shortScreenOffMicBlipsAreCappedButAlertWorthyOnesStillBypass() {
        val tracker = SensorSessionTracker(maxPerHour = 2)

        // Three 5s screen-off mic blips, all below MIC_SCREEN_OFF_ALERT_MIN_MS:
        // not alert-worthy, so they flow through the normal cap path. The first
        // two emit and consume the budget; the third is suppressed.
        assertNotNull(
            tracker.openClose(WatchedSensor.MICROPHONE, "1", openMs = 1_000L, closeMs = 6_000L, screenOff = true),
        )
        assertNotNull(
            tracker.openClose(WatchedSensor.MICROPHONE, "2", openMs = 10_000L, closeMs = 15_000L, screenOff = true),
        )
        assertNull(
            tracker.openClose(WatchedSensor.MICROPHONE, "3", openMs = 20_000L, closeMs = 25_000L, screenOff = true),
        )

        // A 90s screen-off mic session crosses the alert floor and still emits.
        val alertWorthy = tracker.openClose(
            WatchedSensor.MICROPHONE, "4",
            openMs = 30_000L, closeMs = 120_000L, screenOff = true,
        )
        assertNotNull(alertWorthy)
        assertEquals(1, alertWorthy!!.alsoSuppressed)
    }

    @Test
    fun cameraSessionsDoNotConsumeTheMicrophoneBudget() {
        val tracker = SensorSessionTracker(maxPerHour = 1)

        assertNotNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 1_000L, closeMs = 2_000L))
        // The camera budget is exhausted...
        assertNull(tracker.openClose(WatchedSensor.CAMERA, "0", openMs = 3_000L, closeMs = 4_000L))

        // ...but the microphone's is untouched.
        val mic = tracker.openClose(WatchedSensor.MICROPHONE, "7", openMs = 5_000L, closeMs = 6_000L)
        assertNotNull(mic)
        assertEquals(WatchedSensor.MICROPHONE, mic!!.session.sensor)
        assertEquals(0, mic.alsoSuppressed)
    }

    // ---- clock jumps ----

    @Test
    fun backwardsWallJumpNeverCorruptsTheDurationOrTheAlertFloor() {
        val tracker = SensorSessionTracker()

        // The wall clock jumps backwards mid-session while the monotonic clock
        // advances 61 seconds.
        assertNull(
            tracker.deviceBusy(
                WatchedSensor.MICROPHONE, "1", busy = true,
                nowMs = 500_000L, elapsedMs = 1_000L, screenOff = true, likelyApp = null,
            ),
        )
        val tracked = tracker.deviceBusy(
            WatchedSensor.MICROPHONE, "1", busy = false,
            nowMs = 400_000L, elapsedMs = 62_000L, screenOff = false, likelyApp = null,
        )

        assertNotNull(tracked)
        val session = tracked!!.session
        // The duration comes from the monotonic clock: 61s, never negative.
        assertEquals(61_000L, session.durationMs)
        // The wall fields keep what the clocks said, even out of order.
        assertEquals(500_000L, session.startMs)
        assertEquals(400_000L, session.endMs)
        // A screen-off mic session built this way still crosses the alert floor.
        assertTrue(session.durationMs >= MIC_SCREEN_OFF_ALERT_MIN_MS)
    }
}
