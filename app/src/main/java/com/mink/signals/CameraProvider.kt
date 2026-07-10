package com.mink.signals

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.SizeF
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The camera lineup, read through Camera2 [CameraManager]. The set of lenses,
 * their focal lengths, apertures, and sensor sizes is close to a serial number
 * for the phone model. Metadata only; nothing here opens a camera.
 */
class CameraProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.CAMERA

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }
        val manager = runCatching {
            ctx.appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        }.getOrNull() ?: return unavailable()

        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: emptyArray()

        val signals = mutableListOf<FingerprintSignal>()
        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Camera count",
            value = ids.size.toString(),
            rationale =
                "The number of cameras this phone exposes. The count and their capabilities " +
                    "together often pinpoint the exact model.",
        )

        for ((index, id) in ids.withIndex()) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = facing(chars)
            val entries = mutableListOf<SignalEntry>()
            entries += SignalEntry("Facing", facing)

            physicalSize(chars)?.let { entries += SignalEntry("Sensor size", it) }
            pixelArray(chars)?.let { entries += SignalEntry("Resolution", it) }
            focalLengths(chars)?.let { entries += SignalEntry("Focal lengths", it) }
            apertures(chars)?.let { entries += SignalEntry("Apertures", it) }
            entries += SignalEntry("Flash", if (hasFlash(chars)) "yes" else "no")
            hardwareLevel(chars)?.let { entries += SignalEntry("Hardware level", it) }
            afModes(chars)?.let { entries += SignalEntry("Autofocus modes", it) }

            signals += FingerprintSignal.make(
                key = "cam.$index.$id",
                category = category,
                name = "Camera $id ($facing)",
                value = entries.joinToString("  |  ") { "${it.label}: ${it.value}" },
                rationale =
                    "The optics and sensor of this camera. Focal length and sensor size are fixed " +
                        "by the hardware, so they are stable across every reading.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }

        return signals
    }

    private fun facing(chars: CameraCharacteristics): String =
        when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
            else -> "unspecified"
        }

    private fun physicalSize(chars: CameraCharacteristics): String? {
        val size: SizeF = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return "%.1f x %.1f mm".format(size.width, size.height)
    }

    private fun pixelArray(chars: CameraCharacteristics): String? {
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val mp = (size.width.toLong() * size.height.toLong()) / 1_000_000.0
        return "${size.width} x ${size.height} (%.1f MP)".format(mp)
    }

    private fun focalLengths(chars: CameraCharacteristics): String? {
        val lengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        if (lengths == null || lengths.isEmpty()) return null
        return lengths.joinToString(", ") { "%.1f mm".format(it) }
    }

    private fun apertures(chars: CameraCharacteristics): String? {
        val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        if (apertures == null || apertures.isEmpty()) return null
        return apertures.joinToString(", ") { "f/%.1f".format(it) }
    }

    private fun hasFlash(chars: CameraCharacteristics): Boolean =
        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    private fun hardwareLevel(chars: CameraCharacteristics): String? =
        when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level 3"
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL
            ) "external" else null
        }

    private fun afModes(chars: CameraCharacteristics): String? {
        val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        if (modes == null || modes.isEmpty()) return null
        return modes.size.toString()
    }

    private fun unavailable(): List<FingerprintSignal> = listOf(
        FingerprintSignal.make(
            key = "unavailable",
            category = category,
            name = "Camera service",
            value = "Unavailable",
            rationale = "This phone did not return a camera service to read from.",
        ),
    )
}
