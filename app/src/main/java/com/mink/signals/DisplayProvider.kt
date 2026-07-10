package com.mink.signals

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * Screen specs and rendering capabilities. Physical resolution, density, the
 * refresh ceiling, and HDR support together sketch the exact chassis and panel
 * a device shipped with, much like a browser's screen fingerprint. Nothing here
 * needs a permission.
 */
class DisplayProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.DISPLAY

    @Suppress("DEPRECATION")
    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()
        val appContext = ctx.appContext

        val display: Display? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Context.getDisplay() throws for a non-visual context such as the
                // application context, so read the default display from DisplayManager,
                // which works from any context.
                val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                dm?.getDisplay(Display.DEFAULT_DISPLAY)
            } else {
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.defaultDisplay
            }
        }.getOrNull()

        val metrics = DisplayMetrics()
        runCatching {
            if (display != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                display.getRealMetrics(metrics)
            } else {
                metrics.setTo(appContext.resources.displayMetrics)
                display?.getRealMetrics(metrics)
            }
        }

        signals += FingerprintSignal.make(
            key = "resolution",
            category = category,
            name = "Resolution",
            value = "${metrics.widthPixels} x ${metrics.heightPixels}",
            rationale = "The pixel dimensions of your screen. Read from DisplayMetrics.",
            displayHint = DisplayHint.KEY_VALUE,
            entries = listOf(
                SignalEntry("Width", "${metrics.widthPixels} px"),
                SignalEntry("Height", "${metrics.heightPixels} px"),
            ),
        )

        // Real size across SDK levels.
        val realSize = runCatching {
            val point = Point()
            display?.getRealSize(point)
            point
        }.getOrNull()
        if (realSize != null && (realSize.x != 0 || realSize.y != 0)) {
            signals += FingerprintSignal.make(
                key = "realSize",
                category = category,
                name = "Real size",
                value = "${realSize.x} x ${realSize.y}",
                rationale =
                    "The full panel size including system bars. It pins the physical chassis.",
            )
        }

        signals += FingerprintSignal.make(
            key = "density",
            category = category,
            name = "Density",
            value = "${metrics.densityDpi} dpi (x${"%.2f".format(metrics.density)})",
            rationale =
                "The screen density bucket and scaling factor. It varies by panel and model. " +
                    "Read from DisplayMetrics.",
            displayHint = DisplayHint.KEY_VALUE,
            entries = listOf(
                SignalEntry("Density DPI", metrics.densityDpi.toString()),
                SignalEntry("Scale", "%.2f".format(metrics.density)),
                SignalEntry("xdpi", "%.1f".format(metrics.xdpi)),
                SignalEntry("ydpi", "%.1f".format(metrics.ydpi)),
            ),
        )

        if (display != null) {
            signals += FingerprintSignal.make(
                key = "refreshRate",
                category = category,
                name = "Refresh rate",
                value = "%.1f Hz".format(runCatching { display.refreshRate }.getOrDefault(60f)),
                rationale =
                    "The current refresh rate. 60 Hz on most panels, higher on smooth-motion " +
                        "displays. Read from Display.getRefreshRate.",
            )

            val modes = runCatching { display.supportedModes }.getOrNull()
            if (!modes.isNullOrEmpty()) {
                val entries = modes.map { mode ->
                    SignalEntry(
                        "Mode ${mode.modeId}",
                        "${mode.physicalWidth}x${mode.physicalHeight} @ ${"%.0f".format(mode.refreshRate)}Hz",
                    )
                }
                signals += FingerprintSignal.make(
                    key = "supportedModes",
                    category = category,
                    name = "Supported modes",
                    value = "${modes.size} modes",
                    rationale =
                        "The full set of resolution and refresh combinations the panel offers. " +
                            "The exact lineup is model specific.",
                    displayHint = DisplayHint.KEY_VALUE,
                    entries = entries,
                )
            }

            signals += FingerprintSignal.make(
                key = "rotation",
                category = category,
                name = "Rotation",
                value = rotationName(runCatching { display.rotation }.getOrDefault(Surface.ROTATION_0)),
                rationale = "How the screen is currently rotated.",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val isHdr = runCatching { display.isHdr }.getOrDefault(false)
                signals += FingerprintSignal.make(
                    key = "hdr",
                    category = category,
                    name = "HDR",
                    value = if (isHdr) hdrTypes(display) else "not supported",
                    rationale =
                        "Whether the display supports high dynamic range, and which formats. " +
                            "This is model specific. Read from Display HDR capabilities.",
                )

                val wideGamut = runCatching { display.isWideColorGamut }.getOrDefault(false)
                signals += FingerprintSignal.make(
                    key = "wideGamut",
                    category = category,
                    name = "Wide color gamut",
                    value = if (wideGamut) "supported" else "not supported",
                    rationale = "Whether the panel can render a wide color range such as P3.",
                )
            }
        }

        if (signals.isEmpty()) {
            signals += FingerprintSignal.make(
                key = "unavailable",
                category = category,
                name = "Display",
                value = "unavailable",
                rationale = "No active display was attached to read from.",
            )
        }

        return signals
    }

    private fun rotationName(rotation: Int): String = when (rotation) {
        Surface.ROTATION_0 -> "0 degrees (portrait)"
        Surface.ROTATION_90 -> "90 degrees"
        Surface.ROTATION_180 -> "180 degrees"
        Surface.ROTATION_270 -> "270 degrees"
        else -> "unknown"
    }

    private fun hdrTypes(display: Display): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "supported"
        val caps = runCatching { display.hdrCapabilities }.getOrNull() ?: return "supported"
        val names = caps.supportedHdrTypes.map { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                else -> "type $type"
            }
        }
        return if (names.isEmpty()) "supported" else names.joinToString(", ")
    }
}
