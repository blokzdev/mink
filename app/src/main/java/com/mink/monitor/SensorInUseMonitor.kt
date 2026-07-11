package com.mink.monitor

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Display

/**
 * Watches, near real-time, when ANY app turns the camera or the microphone on,
 * and reports each finished stretch of use as a [TrackedSession] through
 * [onSession].
 *
 * What it can and cannot see:
 * - The camera side rides [CameraManager]'s availability callback and the mic
 *   side rides [AudioManager]'s recording callback. Neither needs the CAMERA
 *   permission nor RECORD_AUDIO — a deliberate trust win — and neither names
 *   the app responsible: the platform anonymises both signals for third
 *   parties, so Mink only ever knows that a sensor was in use, never by whom.
 * - The "likely app" attribution is a best-effort foreground correlation via
 *   UsageStats (only when the user granted usage access in Settings). It is a
 *   guess: it names whatever was on screen, and it is blind to background
 *   services actually holding the sensor.
 * - Mink itself never opens a camera (CameraProvider only reads
 *   characteristics) and never records audio. If Mink ever starts doing
 *   either, this monitor needs an ignore path for its own sessions.
 *
 * Every start()..stop() span owns a [Watch]: one dedicated [HandlerThread]
 * with both platform callbacks registered on it, so each watch's
 * [SensorSessionTracker] is confined to a single thread and needs no locks.
 * The monitor runs only while the guardian is enabled and its process lives;
 * sensor use while Mink is not running is invisible to it.
 */
class SensorInUseMonitor(
    context: Context,
    private val onSession: (TrackedSession) -> Unit,
) {
    private val appContext = context.applicationContext

    private var watch: Watch? = null

    /**
     * The state of one start()..stop() span. The tracker, the seen-recordings
     * set, and both platform callbacks belong to exactly one watch, so a
     * callback still draining on a stopped watch's thread can only touch that
     * watch's state, never a newer watch's.
     */
    private inner class Watch {
        val thread = HandlerThread("mink-sensor-monitor").apply { start() }
        val handler = Handler(thread.looper)
        val tracker = SensorSessionTracker()

        /** Recording session ids seen in the last callback; handler-thread confined. */
        val seenRecordingIds = mutableSetOf<String>()

        /** Cleared by stop(); a stale callback on a dying watch must not emit. */
        @Volatile
        var active = true

        val cameraCallback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                deviceBusy(WatchedSensor.CAMERA, cameraId, busy = true)
            }

            override fun onCameraAvailable(cameraId: String) {
                deviceBusy(WatchedSensor.CAMERA, cameraId, busy = false)
            }
        }

        val recordingCallback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                diffRecordingConfigs(configs)
            }
        }

        /**
         * Diff [configs] against the last seen set and feed the edges to the
         * tracker. Shared between the recording callback and the start()-time
         * seed of already-active recordings; runs on the handler thread.
         */
        fun diffRecordingConfigs(configs: List<AudioRecordingConfiguration>) {
            // A silenced client receives zeros — it is not actually capturing —
            // so it does not open a session. The callback fires again on the
            // silence transition, so a client that becomes unsilenced opens its
            // session then. Below Q the platform gives no silencing signal.
            val capturing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                configs.filter { !it.isClientSilenced }
            } else {
                configs
            }
            val current = capturing.associateBy { it.clientAudioSessionId.toString() }
            for ((id, config) in current) {
                if (id !in seenRecordingIds) {
                    deviceBusy(WatchedSensor.MICROPHONE, id, busy = true, sourceHint = sourceHint(config))
                }
            }
            for (id in seenRecordingIds - current.keys) {
                deviceBusy(WatchedSensor.MICROPHONE, id, busy = false)
            }
            seenRecordingIds.clear()
            seenRecordingIds += current.keys
        }

        /** Feed one busy/idle edge to the tracker; runs on the handler thread. */
        fun deviceBusy(
            sensor: WatchedSensor,
            deviceId: String,
            busy: Boolean,
            sourceHint: String? = null,
        ) {
            val tracked = tracker.deviceBusy(
                sensor = sensor,
                deviceId = deviceId,
                busy = busy,
                nowMs = System.currentTimeMillis(),
                elapsedMs = SystemClock.elapsedRealtime(),
                screenOff = screenOff(),
                likelyApp = if (busy) foregroundAppLabel() else null,
                sourceHint = sourceHint,
            )
            if (tracked != null && active) onSession(tracked)
        }
    }

    /**
     * Begin watching. Idempotent. Registration replays the current camera
     * availability: idle cameras arrive as busy=false edges the tracker
     * ignores, and a camera already in use starts its session at "now", which
     * is honest — that is when Mink first saw it. Each start() begins from
     * clean state (a fresh tracker, an empty seen-recordings set), so a
     * session in flight across a disable is deliberately dropped: Mink was
     * told to stop watching, and it must not fabricate what it did not see.
     */
    @Synchronized
    fun start() {
        if (watch != null) return
        val next = Watch()
        runCatching {
            val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameras?.registerAvailabilityCallback(next.cameraCallback, next.handler)
        }
        runCatching {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio != null) {
                audio.registerAudioRecordingCallback(next.recordingCallback, next.handler)
                // Unlike the camera callback, the recording callback delivers
                // nothing at registration, so seed the mic state through the
                // same diff path: a recording already in progress opens its
                // session at "now" — again, when Mink first saw it.
                next.handler.post {
                    if (!next.active) return@post
                    runCatching { next.diffRecordingConfigs(audio.activeRecordingConfigurations) }
                }
            }
        }
        watch = next
    }

    /** Stop watching and release the watch's handler thread. Idempotent. */
    @Synchronized
    fun stop() {
        val current = watch ?: return
        watch = null
        current.active = false
        runCatching {
            val cameras = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameras?.unregisterAvailabilityCallback(current.cameraCallback)
        }
        runCatching {
            val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.unregisterAudioRecordingCallback(current.recordingCallback)
        }
        current.thread.quitSafely()
    }

    /** Map a recording config's audio source to a human hint, or null. */
    private fun sourceHint(config: AudioRecordingConfiguration): String? =
        when (config.clientAudioSource) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "a voice call"
            MediaRecorder.AudioSource.CAMCORDER -> "video recording"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice recognition"
            else -> null
        }

    /**
     * True when the default display is not STATE_ON (off, dozing, or
     * suspended). A missing display reads as "screen on" — the monitor never
     * fabricates the suspicious case.
     */
    private fun screenOff(): Boolean = runCatching {
        val displays = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching false
        display.state != Display.STATE_ON
    }.getOrDefault(false)

    /**
     * The label of the app most recently resumed to the foreground, or null.
     * Best-effort only: needs usage access, is a guess about who is using the
     * sensor, and any failure quietly yields null.
     */
    private fun foregroundAppLabel(): String? = runCatching {
        if (!hasUsageAccess(appContext)) return@runCatching null
        val usage = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@runCatching null
        val nowMs = System.currentTimeMillis()
        // queryEvents can return null (e.g. locked device on API 30+).
        val events = usage.queryEvents(nowMs - ATTRIBUTION_LOOKBACK_MS, nowMs)
            ?: return@runCatching null
        val resumedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            @Suppress("DEPRECATION")
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumedType) lastPackage = event.packageName
        }
        lastPackage?.let { packageName ->
            val pm = appContext.packageManager
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
        }
    }.getOrNull()

    companion object {
        private const val ATTRIBUTION_LOOKBACK_MS = 60_000L

        /** True when the user granted Mink usage access in system settings. */
        fun hasUsageAccess(context: Context): Boolean = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }
}
