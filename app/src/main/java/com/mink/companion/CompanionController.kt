package com.mink.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.mink.guardian.AlertLevel
import com.mink.guardian.Guardian
import com.mink.guardian.GuardianAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Drives the floating Mink. Owns the companion's observable state, manages the
 * overlay permission and the overlay service, and lets the guardian speak
 * through the companion by watching [Guardian.alerts] and voicing new warnings.
 *
 * Constructed by [com.mink.data.ServiceWiring] as:
 *   CompanionController(context, guardian, scope)
 */
class CompanionController(
    private val context: Context,
    private val guardian: Guardian,
    private val scope: CoroutineScope,
) : Companion {

    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mood = MutableStateFlow(CompanionMood.IDLE)
    override val mood: StateFlow<CompanionMood> = _mood.asStateFlow()

    private val _utterance = MutableStateFlow<CompanionUtterance?>(null)
    override val utterance: StateFlow<CompanionUtterance?> = _utterance.asStateFlow()

    private val announcedAlertIds = mutableSetOf<String>()
    private var clearBubbleJob: Job? = null

    /** Guards the timer-job cancel+assign swaps that run from several dispatchers. */
    private val jobLock = Any()

    /** The calm engine that gates speech; mood and animation are not gated. */
    private val speechPolicy = CompanionSpeechPolicy()

    /** The quiet-timer that eases the sprite to sleep after a lull. */
    private var sleepJob: Job? = null

    private val appContext = context.applicationContext

    init {
        // Treat everything already on the board as seen, so enabling the
        // companion never replays a backlog of old warnings. Key by id and level
        // so a persisted alert re-graded upward can still be re-admitted.
        guardian.alerts.value.forEach { announcedAlertIds += "${it.id}|${it.level}" }

        // Restore the persisted opt-in so the overlay and the controller agree
        // after a process-death restart, mirroring how the guardian restores.
        scope.launch(Dispatchers.IO) {
            val wasEnabled = runCatching {
                appContext.companionDataStore.data.first()[KEY_ENABLED]
            }.getOrNull() ?: false
            if (wasEnabled && canDrawOverlay()) enable()
        }

        scope.launch {
            guardian.alerts.collect { alerts ->
                val fresh = alerts.filter {
                    "${it.id}|${it.level}" !in announcedAlertIds && isSpeakable(it.level)
                }
                alerts.forEach { announcedAlertIds += "${it.id}|${it.level}" }
                val richest = richestAlert(fresh) ?: return@collect

                // Rules pick the mood; the sprite animates for every fresh
                // finding and the quiet-timer restarts. Speech is gated
                // separately, so the sprite stays lively while the voice is rare.
                setMood(CompanionRemark.moodForAlert(richest.level, richest.categoryId))
                scheduleSleep()

                val now = System.currentTimeMillis()
                val toSpeak = speechPolicy.chooseToSpeak(fresh, now)
                if (toSpeak != null && _enabled.value) speakAlert(toSpeak, now)
            }
        }
    }

    override fun canDrawOverlay(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        } else {
            true
        }

    override fun enable() {
        if (!canDrawOverlay()) {
            _enabled.value = false
            return
        }
        _enabled.value = true
        persistEnabled(true)
        setMood(CompanionMood.IDLE)
        CompanionOverlayService.start(context)
        scheduleSleep()
    }

    override fun disable() {
        _enabled.value = false
        persistEnabled(false)
        clearBubbleJob?.cancel()
        sleepJob?.cancel()
        _utterance.value = null
        CompanionLink.utterance.value = null
        CompanionLink.bubbleVisible.value = false
        CompanionOverlayService.stop(context)
    }

    private fun persistEnabled(enabled: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                appContext.companionDataStore.edit { it[KEY_ENABLED] = enabled }
            }
        }
    }

    override fun say(utterance: CompanionUtterance) {
        _utterance.value = utterance
        CompanionLink.utterance.value = utterance
        CompanionLink.bubbleVisible.value = true
        setMood(utterance.mood)

        synchronized(jobLock) {
            clearBubbleJob?.cancel()
            clearBubbleJob = scope.launch {
                delay(BUBBLE_VISIBLE_MS)
                CompanionLink.bubbleVisible.value = false
            }
        }
        scheduleSleep()
    }

    override fun setMood(mood: CompanionMood) {
        _mood.value = mood
        CompanionLink.mood.value = mood
    }

    /**
     * Compose and voice a remark for [alert] without blocking the collector. The
     * mood is already set; here the model writes the sentence, falling back to
     * the alert title when no model is loaded or the call fails.
     */
    private fun speakAlert(alert: GuardianAlert, nowMs: Long) {
        scope.launch {
            val text = guardian.composeRemark(alert) ?: alert.title
            // composeRemark is slow; if the user disabled the companion while it
            // ran, stay silent rather than pop a bubble for a disabled companion.
            if (!_enabled.value) return@launch
            say(
                CompanionUtterance(
                    text = text,
                    mood = CompanionRemark.moodForAlert(alert.level, alert.categoryId),
                    epochMs = nowMs,
                    actionLabel = "See details",
                    actionRoute = CompanionLink.ROUTE_GUARDIAN,
                ),
            )
        }
    }

    /**
     * Restart the quiet-timer: after a lull with no utterance the sprite eases to
     * sleep. Any say() or fresh alert restarts it; disabling cancels it. Kept
     * simple and lifecycle-safe — the enabled check runs again when it fires.
     */
    private fun scheduleSleep() {
        synchronized(jobLock) {
            sleepJob?.cancel()
            if (!_enabled.value) return
            sleepJob = scope.launch {
                delay(SLEEP_AFTER_MS)
                if (_enabled.value) setMood(CompanionMood.SLEEPING)
            }
        }
    }

    /**
     * The richest of [alerts]: highest severity (critical over warning),
     * tie-broken by the longest body then the newest finding, matching the
     * speech policy's burst-merge so the mood and the voiced line agree. Null
     * when there is nothing fresh to react to.
     */
    private fun richestAlert(alerts: List<GuardianAlert>): GuardianAlert? =
        alerts.maxWithOrNull(
            compareBy<GuardianAlert> { if (it.level == AlertLevel.CRITICAL) 1 else 0 }
                .thenBy { it.body.length }
                .thenBy { it.createdAtEpochMs },
        )

    private fun isSpeakable(level: AlertLevel): Boolean =
        level == AlertLevel.WARNING || level == AlertLevel.CRITICAL

    private companion object {
        const val BUBBLE_VISIBLE_MS = 9_000L

        /** Ease the sprite to sleep after this quiet stretch. Tunable. */
        const val SLEEP_AFTER_MS = 5L * 60L * 1000L
        val KEY_ENABLED = booleanPreferencesKey("companion_enabled")
    }
}

private val Context.companionDataStore: DataStore<Preferences> by preferencesDataStore(name = "companion")
