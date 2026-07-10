package com.mink.signals

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Reads the system configuration flags any app can query with no prompt:
 * developer options, ADB, the animation scales, automatic time, the device
 * name, the default input method, and a few interaction settings. None of these
 * is an identifier on its own, but the exact combination is a config fingerprint
 * that individuates you, and the device name is often literally your own name.
 */
class SystemSettingsProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.SYSTEM_SETTINGS
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val resolver = appContext.contentResolver
        val signals = mutableListOf<FingerprintSignal>()

        val deviceName = readGlobalString(resolver, Settings.Global.DEVICE_NAME)
        if (!deviceName.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "deviceName",
                category = category,
                name = "Device name",
                value = deviceName,
                rationale = "The name you gave this phone. It often contains your own name and " +
                    "follows you across Bluetooth and Wi-Fi. Read from Settings.Global.",
            )
        }

        addFlag(
            signals, "developer", "Developer options",
            readGlobalInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
            "Whether developer options are turned on. Most people leave it off, so on stands out.",
        )
        addFlag(
            signals, "adb", "USB debugging",
            readGlobalInt(resolver, Settings.Global.ADB_ENABLED),
            "Whether ADB debugging is enabled. An uncommon setting that marks a developer device.",
        )
        addFlag(
            signals, "autoTime", "Automatic time",
            readGlobalInt(resolver, Settings.Global.AUTO_TIME),
            "Whether the clock is set automatically. A configuration flag that adds to the " +
                "profile.",
        )

        val animations = readAnimationScales(resolver)
        if (animations.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "animationScales",
                category = category,
                name = "Animation scales",
                value = animations.joinToString(", ") { "${it.label} ${it.value}" },
                rationale = "The animator, transition, and window animation scales. People who " +
                    "trim them for speed carry a distinctive set. Read from Settings.Global.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = animations.map { SignalEntry(it.label, it.value) },
            )
        }

        val inputMethod = readSecureString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (!inputMethod.isNullOrBlank()) {
            signals += FingerprintSignal.make(
                key = "inputMethod",
                category = category,
                name = "Default keyboard",
                value = shortenComponent(inputMethod),
                rationale = "The keyboard you type with. A third-party keyboard narrows you down. " +
                    "Read from Settings.Secure.",
            )
        }

        addFlag(
            signals, "accessibility", "Accessibility enabled",
            readSecureInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED),
            "Whether an accessibility service is active. A meaningful, fairly rare setting.",
        )

        val brightnessMode = readSystemInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        if (brightnessMode != null) {
            signals += FingerprintSignal.make(
                key = "brightnessMode",
                category = category,
                name = "Brightness mode",
                value = if (brightnessMode == 1) "automatic" else "manual",
                rationale = "Whether screen brightness is automatic or manual. A small config bit " +
                    "that adds up.",
            )
        }

        val haptic = readSystemInt(resolver, "haptic_feedback_enabled")
        if (haptic != null) {
            signals += FingerprintSignal.make(
                key = "haptics",
                category = category,
                name = "Haptic feedback",
                value = (haptic != 0).toString(),
                rationale = "Whether touch haptics are on. One more configuration flag in the " +
                    "combined fingerprint.",
            )
        }

        return signals
    }

    private fun addFlag(
        signals: MutableList<FingerprintSignal>,
        key: String,
        name: String,
        raw: Int?,
        rationale: String,
    ) {
        if (raw == null) return
        signals += FingerprintSignal.make(
            key = key,
            category = category,
            name = name,
            value = (raw != 0).toString(),
            rationale = rationale,
        )
    }

    private fun readAnimationScales(resolver: ContentResolver): List<Scale> {
        val scales = mutableListOf<Scale>()
        readGlobalFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)?.let {
            scales += Scale("Animator", formatScale(it))
        }
        readGlobalFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE)?.let {
            scales += Scale("Transition", formatScale(it))
        }
        readGlobalFloat(resolver, Settings.Global.WINDOW_ANIMATION_SCALE)?.let {
            scales += Scale("Window", formatScale(it))
        }
        return scales
    }

    private fun readGlobalInt(resolver: ContentResolver, key: String): Int? = runCatching {
        Settings.Global.getInt(resolver, key)
    }.getOrNull()

    private fun readGlobalFloat(resolver: ContentResolver, key: String): Float? = runCatching {
        Settings.Global.getFloat(resolver, key)
    }.getOrNull()

    private fun readGlobalString(resolver: ContentResolver, key: String): String? = runCatching {
        Settings.Global.getString(resolver, key)
    }.getOrNull()

    private fun readSecureInt(resolver: ContentResolver, key: String): Int? = runCatching {
        Settings.Secure.getInt(resolver, key)
    }.getOrNull()

    private fun readSecureString(resolver: ContentResolver, key: String): String? = runCatching {
        Settings.Secure.getString(resolver, key)
    }.getOrNull()

    private fun readSystemInt(resolver: ContentResolver, key: String): Int? = runCatching {
        Settings.System.getInt(resolver, key)
    }.getOrNull()

    private data class Scale(val label: String, val value: String)

    companion object {
        /** Formats an animation scale float compactly (1.0 -> "1x"). Pure and testable. */
        fun formatScale(scale: Float): String {
            val text = if (scale == scale.toLong().toFloat()) {
                scale.toLong().toString()
            } else {
                "%.2f".format(scale).trimEnd('0').trimEnd('.')
            }
            return "${text}x"
        }

        /**
         * Shortens a flattened component name (pkg/.Service) to something
         * readable, keeping the package. Pure and testable.
         */
        fun shortenComponent(flattened: String): String {
            val pkg = flattened.substringBefore('/').trim()
            return pkg.ifBlank { flattened }
        }
    }
}
