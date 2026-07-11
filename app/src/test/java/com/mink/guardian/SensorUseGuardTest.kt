package com.mink.guardian

import com.mink.monitor.SensorUseSession
import com.mink.monitor.TrackedSession
import com.mink.monitor.WatchedSensor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [sensorSessionToGuardian]: observation wording and
 * duration formats, best-guess attribution phrasing, the screen-off alert
 * rules with the microphone duration floor, and injected id/time/category
 * metadata. Sessions are built by hand and ids/time injected, so every branch
 * is asserted deterministically.
 */
class SensorUseGuardTest {

    /** Deterministic id source so ids can be traced back to the factory. */
    private fun sequentialIds(): () -> String {
        var n = 0
        return { "gen-${n++}" }
    }

    private fun session(
        sensor: WatchedSensor = WatchedSensor.CAMERA,
        durationMs: Long = 12_000L,
        screenWasOff: Boolean = false,
        likelyApp: String? = null,
        sourceHint: String? = null,
    ): SensorUseSession = SensorUseSession(
        sensor = sensor,
        startMs = 100_000L,
        endMs = 100_000L + durationMs,
        durationMs = durationMs,
        screenWasOff = screenWasOff,
        likelyApp = likelyApp,
        sourceHint = sourceHint,
    )

    private fun map(
        session: SensorUseSession,
        alsoSuppressed: Int = 0,
        nowMs: Long = 7_000L,
    ): SensorUseGuardResult =
        sensorSessionToGuardian(TrackedSession(session, alsoSuppressed), nowMs, sequentialIds())

    // ---- observation wording ----

    @Test
    fun cameraObservationStatesSensorAndDuration() {
        val result = map(session(durationMs = 12_000L))

        assertEquals("The camera was in use for 12s.", result.observation.summary)
        assertNull(result.alert)
    }

    @Test
    fun durationFormatsAsSecondsUnderAMinuteThenWholeMinutes() {
        assertEquals("The camera was in use for 1m.", map(session(durationMs = 60_000L)).observation.summary)
        // Round down: ninety seconds is still "1m".
        assertEquals("The camera was in use for 1m.", map(session(durationMs = 90_000L)).observation.summary)
        assertEquals("The camera was in use for 3m.", map(session(durationMs = 180_000L)).observation.summary)
        // Never below one second, even for an instant session.
        assertEquals("The camera was in use for 1s.", map(session(durationMs = 0L)).observation.summary)
    }

    @Test
    fun likelyAppIsPhrasedAsAGuessAndOmittedWhenUnknown() {
        val named = map(session(likelyApp = "Camera")).observation.summary
        assertEquals("The camera was in use for 12s. Likely Camera, going by what was on screen.", named)

        val anonymous = map(session(likelyApp = null)).observation.summary
        assertFalse(anonymous.contains("Likely"))
    }

    @Test
    fun micSourceHintIsWovenIntoTheSentence() {
        val result = map(
            session(sensor = WatchedSensor.MICROPHONE, durationMs = 180_000L, sourceHint = "a voice call"),
        )

        assertEquals("The microphone was in use for 3m during a voice call.", result.observation.summary)
    }

    @Test
    fun suppressedSessionsAreCountedInTheSummary() {
        val result = map(session(durationMs = 12_000L), alsoSuppressed = 3)

        assertEquals(
            "The camera was in use for 12s. It was used 3 more times since Mink's last note.",
            result.observation.summary,
        )
    }

    @Test
    fun suppressedSentenceCountsSinceTheLastNoteNotThePastHour() {
        // Suppressions can be older than an hour when the sensor goes idle, so
        // the sentence must not claim "the past hour".
        val summary = map(session(durationMs = 12_000L), alsoSuppressed = 2).observation.summary

        assertTrue(summary.endsWith(" It was used 2 more times since Mink's last note."))
        assertFalse(summary.contains("in the past hour"))
    }

    // ---- alert rules ----

    @Test
    fun cameraWhileScreenOffRaisesCalmHonestWarning() {
        val result = map(session(screenWasOff = true, likelyApp = "Camera"))

        val alert = result.alert
        assertNotNull(alert)
        assertEquals(AlertLevel.WARNING, alert!!.level)
        assertEquals("The camera was used while the screen was off", alert.title)
        assertTrue(alert.body.contains("while the screen was off"))
        assertTrue(alert.body.contains("Likely Camera, going by what was on screen."))
        assertTrue(
            alert.body.contains(
                "Android does not tell apps which app was using it; Mink can only see that it happened.",
            ),
        )
        assertFalse(alert.title.contains("!"))
        assertFalse(alert.body.contains("!"))
        // The observation still notes the screen state.
        assertTrue(result.observation.summary.contains("The screen was off at the time."))
    }

    @Test
    fun shortScreenOffMicSessionStaysObservationOnly() {
        // Thirty seconds is under the duration floor; hotword blips must not alert.
        val result = map(session(sensor = WatchedSensor.MICROPHONE, durationMs = 30_000L, screenWasOff = true))

        assertNull(result.alert)
        assertTrue(result.observation.summary.contains("The screen was off at the time."))
    }

    @Test
    fun micScreenOffAlertFloorIsInclusiveAtExactlySixtySeconds() {
        // The floor is >= 60s: exactly sixty seconds alerts, one millisecond under does not.
        assertNotNull(
            map(session(sensor = WatchedSensor.MICROPHONE, durationMs = 60_000L, screenWasOff = true)).alert,
        )
        assertNull(
            map(session(sensor = WatchedSensor.MICROPHONE, durationMs = 59_999L, screenWasOff = true)).alert,
        )
    }

    @Test
    fun longScreenOffMicSessionRaisesWarningWithSourceHint() {
        val result = map(
            session(
                sensor = WatchedSensor.MICROPHONE,
                durationMs = 90_000L,
                screenWasOff = true,
                sourceHint = "a voice call",
            ),
        )

        val alert = result.alert
        assertNotNull(alert)
        assertEquals(AlertLevel.WARNING, alert!!.level)
        assertEquals("The microphone was used while the screen was off", alert.title)
        assertTrue(alert.body.contains("during a voice call"))
        assertFalse(alert.body.contains("!"))
    }

    @Test
    fun screenOnSessionsNeverAlert() {
        assertNull(map(session(sensor = WatchedSensor.CAMERA, durationMs = 600_000L)).alert)
        assertNull(map(session(sensor = WatchedSensor.MICROPHONE, durationMs = 600_000L)).alert)
    }

    // ---- metadata ----

    @Test
    fun observationAndAlertCarryInjectedIdTimeKindAndCategory() {
        val now = 42_000L
        val result = map(session(screenWasOff = true), nowMs = now)

        val observation = result.observation
        assertEquals(now, observation.epochMs)
        assertEquals(ObservationKind.CHANGE, observation.kind)
        assertEquals(SENSOR_USE_CATEGORY, observation.categoryId)
        assertTrue(observation.id.startsWith("gen-"))

        val alert = result.alert
        assertNotNull(alert)
        assertEquals(now, alert!!.createdAtEpochMs)
        assertEquals(SENSOR_USE_CATEGORY, alert.categoryId)
        assertTrue(alert.id.startsWith("gen-"))
        // Ids are unique across observation and alert, and the category string is pinned.
        assertFalse(observation.id == alert.id)
        assertEquals("sensor_use", SENSOR_USE_CATEGORY)
    }
}
