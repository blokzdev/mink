package com.mink.companion

/**
 * The companion's emotional/animation state, driving which sprite frames play.
 * Lives in guardian-core because the RULES pick the mood ([CompanionRemark
 * .moodForAlert] — deterministic, model-free); the sprite in the app layer only
 * renders it.
 */
enum class CompanionMood {
    IDLE,
    HAPPY,
    CURIOUS,
    ALERT,
    THINKING,
    SLEEPING;

    val label: String
        get() = name.lowercase().replaceFirstChar { it.uppercase() }
}
