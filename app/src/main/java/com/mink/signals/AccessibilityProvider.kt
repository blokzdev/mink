package com.mink.signals

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Reads the accessibility flags any app can check with no prompt. Each flag is
 * a boolean with a low chance of being on, so every setting you have turned on
 * adds several distinguishing bits. Stacked together these are one of the
 * strongest passive surfaces on the device.
 */
class AccessibilityProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.ACCESSIBILITY
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val manager = runCatching {
            appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        }.getOrNull()

        if (manager != null) {
            runCatching {
                signals += FingerprintSignal.make(
                    key = "enabled",
                    category = category,
                    name = "Accessibility enabled",
                    value = manager.isEnabled.toString(),
                    rationale = "Whether any accessibility service is running. A quiet yes-or-no " +
                        "that any app can read.",
                )
                signals += FingerprintSignal.make(
                    key = "touchExploration",
                    category = category,
                    name = "Touch exploration",
                    value = manager.isTouchExplorationEnabled.toString(),
                    rationale = "Whether explore-by-touch is on, which suggests a screen reader " +
                        "is in use.",
                )
            }
            runCatching {
                val services = manager
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfoFeedbackAllMask)
                    .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                    .distinct()
                if (services.isNotEmpty()) {
                    signals += FingerprintSignal.make(
                        key = "services",
                        category = category,
                        name = "Enabled services",
                        value = services.joinToString(", "),
                        rationale = "The accessibility services you have granted. The specific set " +
                            "reflects the tools you rely on.",
                        displayHint = DisplayHint.TAGS,
                        entries = services.map { SignalEntry(it, "") },
                    )
                }
            }
        }

        addSecureFlags(signals)
        addAnimationScales(signals)
        addFontScale(signals)

        return signals
    }

    private fun addSecureFlags(signals: MutableList<FingerprintSignal>) {
        val resolver = appContext.contentResolver
        val flags = listOf(
            "High contrast text" to "high_text_contrast_enabled",
            "Color inversion" to Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED,
        )
        val entries = flags.mapNotNull { (label, key) ->
            runCatching {
                val on = Settings.Secure.getInt(resolver, key, 0) == 1
                SignalEntry(label, on.toString())
            }.getOrNull()
        }
        if (entries.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "displayFlags",
                category = category,
                name = "Display accessibility flags",
                value = entries.joinToString(", ") { "${it.label}: ${it.value}" },
                rationale = "Secure display settings any app can read. Each one you enable adds a " +
                    "distinguishing detail.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }
    }

    private fun addAnimationScales(signals: MutableList<FingerprintSignal>) {
        val resolver = appContext.contentResolver
        val scales = listOf(
            "Animator" to Settings.Global.ANIMATOR_DURATION_SCALE,
            "Transition" to Settings.Global.TRANSITION_ANIMATION_SCALE,
            "Window" to Settings.Global.WINDOW_ANIMATION_SCALE,
        )
        val entries = scales.mapNotNull { (label, key) ->
            runCatching {
                SignalEntry(label, Settings.Global.getFloat(resolver, key, 1f).toString())
            }.getOrNull()
        }
        if (entries.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "animationScales",
                category = category,
                name = "Animation scales",
                value = entries.joinToString(", ") { "${it.label} ${it.value}" },
                rationale = "How fast the system animates. People who slow down or turn off " +
                    "animation stand out from the default.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }
    }

    private fun addFontScale(signals: MutableList<FingerprintSignal>) {
        runCatching {
            val scale = appContext.resources.configuration.fontScale
            signals += FingerprintSignal.make(
                key = "fontScale",
                category = category,
                name = "Font scale",
                value = scale.toString(),
                rationale = "Your text size preference. A value away from 1.0 is a small but " +
                    "stable marker.",
            )
        }
    }

    private companion object {
        /** AccessibilityServiceInfo.FEEDBACK_ALL_MASK, inlined to avoid the extra import. */
        const val AccessibilityServiceInfoFeedbackAllMask = -1
    }
}
