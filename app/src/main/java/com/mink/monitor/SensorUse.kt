package com.mink.monitor

/**
 * How many sensor-use sessions per sensor may become observations in one hour.
 * A deterministic rule threshold, tunable in a future release by design — this
 * is NOT a lane 5 immutable rule (see docs/memory-architecture.md). A chatty
 * camera app or a hotword assistant can legitimately open a sensor many times
 * an hour; the cap keeps the timeline readable without hiding that it happened
 * (suppressed sessions are counted and reported on the next emission).
 */
const val MAX_SENSOR_SESSIONS_PER_HOUR = 6

/**
 * Minimum duration before a microphone session with the screen off raises a
 * WARNING. A deterministic rule threshold, tunable in a future release by
 * design — NOT a lane 5 immutable rule, and deliberately so: hotword
 * assistants make short screen-off microphone blips legitimate, which is
 * exactly why this rule has a duration floor at all.
 */
const val MIC_SCREEN_OFF_ALERT_MIN_MS = 60_000L

/** A hardware sensor whose in-use state the guardian watches. */
enum class WatchedSensor(val label: String) {
    CAMERA("camera"),
    MICROPHONE("microphone"),
}

/**
 * One completed stretch of a sensor being in use, from the first device going
 * busy to the last device going idle. [startMs] and [endMs] are wall-clock
 * timestamps kept for display; [durationMs] is computed from the monotonic
 * clock, so it stays honest when the wall clock jumps. [likelyApp] is a
 * best-effort foreground correlation, never a platform fact — every string
 * built from it must say "likely".
 */
data class SensorUseSession(
    val sensor: WatchedSensor,
    val startMs: Long,
    val endMs: Long,           // may lag startMs after a backwards wall jump
    val durationMs: Long,      // monotonic, never negative
    val screenWasOff: Boolean, // display not STATE_ON at session start
    val likelyApp: String?,    // foreground-app label at session start, or null
    val sourceHint: String?,   // mic only: "a voice call" etc., else null
)

/**
 * A session the tracker decided to emit. [alsoSuppressed] counts the sessions
 * swallowed by the hourly cap since the last emission for that sensor.
 */
data class TrackedSession(val session: SensorUseSession, val alsoSuppressed: Int)

/**
 * Turns raw per-device busy/idle edges into [SensorUseSession]s and applies
 * the hourly emission cap. Pure JVM and fully deterministic: the clock is
 * injected through method parameters, and the class is single-threaded by
 * contract — the caller confines every call to one thread, so there are no
 * locks here.
 *
 * Sessions overlapping across device ids merge: the session opens when the
 * first id for a sensor goes busy and closes when the last one goes idle.
 * A busy=false edge for an id that was never seen busy is ignored, which
 * absorbs the camera availability callback's registration-time replay of
 * idle cameras. Sessions that will raise a WARNING always emit; the cap must
 * never hide the alert-worthy case.
 */
class SensorSessionTracker(private val maxPerHour: Int = MAX_SENSOR_SESSIONS_PER_HOUR) {

    private class SensorState {
        val busyIds = mutableSetOf<String>()
        var startMs = 0L
        var startElapsedMs = 0L
        var screenWasOff = false
        var likelyApp: String? = null
        var sourceHint: String? = null
        var windowStartElapsedMs = 0L
        var emittedCount = 0
        var suppressedCount = 0
    }

    private val states: Map<WatchedSensor, SensorState> =
        WatchedSensor.entries.associateWith { SensorState() }

    /**
     * Record one busy/idle edge for a device of [sensor]. Returns the finished
     * session when this edge closes one and the cap lets it through, else null.
     * [nowMs] is the wall clock and is only used for display timestamps;
     * [elapsedMs] is a monotonic reading (SystemClock.elapsedRealtime in
     * production) used for durations and the hourly window, mirroring the
     * SweepTime wall/monotonic split in Baseline.kt — a wall-clock jump must
     * not fabricate or suppress a WARNING. [screenOff], [likelyApp], and
     * [sourceHint] are only captured on the edge that opens a session; later
     * edges leave them untouched.
     */
    fun deviceBusy(
        sensor: WatchedSensor,
        deviceId: String,
        busy: Boolean,
        nowMs: Long,
        elapsedMs: Long,
        screenOff: Boolean,
        likelyApp: String?,
        sourceHint: String? = null,
    ): TrackedSession? {
        val state = states.getValue(sensor)

        if (busy) {
            if (deviceId in state.busyIds) return null
            if (state.busyIds.isEmpty()) {
                state.startMs = nowMs
                state.startElapsedMs = elapsedMs
                state.screenWasOff = screenOff
                state.likelyApp = likelyApp
                state.sourceHint = sourceHint
            }
            state.busyIds += deviceId
            return null
        }

        if (deviceId !in state.busyIds) return null
        state.busyIds -= deviceId
        if (state.busyIds.isNotEmpty()) return null

        val session = SensorUseSession(
            sensor = sensor,
            startMs = state.startMs,
            endMs = nowMs,
            durationMs = (elapsedMs - state.startElapsedMs).coerceAtLeast(0L),
            screenWasOff = state.screenWasOff,
            likelyApp = state.likelyApp,
            sourceHint = state.sourceHint,
        )

        // The bypass exists so the cap can never hide a session that will
        // alert (SensorUseGuard's WARNING rule, mirrored via the shared
        // constant — keep them consistent); everything else is timeline noise
        // management and flows through the normal cap path, so sub-60s
        // screen-off mic blips from hotword assistants cannot flood the
        // observation store. Alert-worthy sessions do not consume the budget.
        val alertWorthy = session.screenWasOff &&
            (session.sensor == WatchedSensor.CAMERA || session.durationMs >= MIC_SCREEN_OFF_ALERT_MIN_MS)
        if (alertWorthy) {
            return emit(state, session)
        }

        // The hour window starts at the first emission; roll it over before
        // deciding whether this session fits. elapsedRealtime restarts at 0 on
        // reboot, which can defer one rollover by up to an hour — acceptable.
        if (state.windowStartElapsedMs != 0L && elapsedMs - state.windowStartElapsedMs >= HOUR_MS) {
            state.windowStartElapsedMs = elapsedMs
            state.emittedCount = 0
        }
        if (state.emittedCount < maxPerHour) {
            if (state.windowStartElapsedMs == 0L) state.windowStartElapsedMs = elapsedMs
            state.emittedCount += 1
            return emit(state, session)
        }
        state.suppressedCount += 1
        return null
    }

    private fun emit(state: SensorState, session: SensorUseSession): TrackedSession {
        val tracked = TrackedSession(session, alsoSuppressed = state.suppressedCount)
        state.suppressedCount = 0
        return tracked
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
