package com.mink.companion

import kotlinx.coroutines.flow.StateFlow

/** A line the companion is currently speaking, shown in its bubble. */
data class CompanionUtterance(
    val text: String,
    val mood: CompanionMood,
    val epochMs: Long,
    val actionLabel: String? = null,
    val actionRoute: String? = null,
)

/**
 * Controls the on-screen floating Mink companion (a retro 8-bit blue sprite in
 * a system overlay window). The UI toggles it; the guardian speaks through it.
 *
 * Contract for the implementation constructor (wired in ServiceWiring):
 *   CompanionController(context: Context, guardian: Guardian, scope: CoroutineScope)
 */
interface Companion {
    val enabled: StateFlow<Boolean>
    val mood: StateFlow<CompanionMood>
    val utterance: StateFlow<CompanionUtterance?>

    /** Whether the OS overlay permission (SYSTEM_ALERT_WINDOW) is granted. */
    fun canDrawOverlay(): Boolean

    /** Start the overlay service and show the companion. Requires overlay grant. */
    fun enable()

    /** Hide the companion and stop the overlay service. */
    fun disable()

    /** Make the companion speak a line (and optionally surface an action). */
    fun say(utterance: CompanionUtterance)

    fun setMood(mood: CompanionMood)
}
