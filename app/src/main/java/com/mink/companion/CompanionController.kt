package com.mink.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.mink.guardian.AlertLevel
import com.mink.guardian.Guardian
import com.mink.guardian.GuardianAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    init {
        // Treat everything already on the board as seen, so enabling the
        // companion never replays a backlog of old warnings.
        guardian.alerts.value.forEach { announcedAlertIds += it.id }
        scope.launch {
            guardian.alerts.collect { alerts ->
                val fresh = alerts.filter {
                    it.id !in announcedAlertIds && isSpeakable(it.level)
                }
                alerts.forEach { announcedAlertIds += it.id }
                if (_enabled.value) {
                    fresh.maxByOrNull { it.createdAtEpochMs }?.let { speakAlert(it) }
                }
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
        setMood(CompanionMood.IDLE)
        CompanionOverlayService.start(context)
    }

    override fun disable() {
        _enabled.value = false
        clearBubbleJob?.cancel()
        _utterance.value = null
        CompanionLink.utterance.value = null
        CompanionLink.bubbleVisible.value = false
        CompanionOverlayService.stop(context)
    }

    override fun say(utterance: CompanionUtterance) {
        _utterance.value = utterance
        CompanionLink.utterance.value = utterance
        CompanionLink.bubbleVisible.value = true
        setMood(utterance.mood)

        clearBubbleJob?.cancel()
        clearBubbleJob = scope.launch {
            delay(BUBBLE_VISIBLE_MS)
            CompanionLink.bubbleVisible.value = false
        }
    }

    override fun setMood(mood: CompanionMood) {
        _mood.value = mood
        CompanionLink.mood.value = mood
    }

    private fun speakAlert(alert: GuardianAlert) {
        val mood = if (alert.level == AlertLevel.CRITICAL) {
            CompanionMood.ALERT
        } else {
            CompanionMood.CURIOUS
        }
        say(
            CompanionUtterance(
                text = alert.title,
                mood = mood,
                epochMs = alert.createdAtEpochMs,
                actionLabel = "See details",
                actionRoute = CompanionLink.ROUTE_GUARDIAN,
            ),
        )
    }

    private fun isSpeakable(level: AlertLevel): Boolean =
        level == AlertLevel.WARNING || level == AlertLevel.CRITICAL

    private companion object {
        const val BUBBLE_VISIBLE_MS = 9_000L
    }
}
