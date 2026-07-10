package com.mink.signals

import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The original fingerprint. The raw hardware strings pin down the exact model
 * and SoC variant a device shipped with, and ANDROID_ID follows you across
 * every install signed by the same key. Nothing here needs a permission.
 */
class DeviceIdentityProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.DEVICE_IDENTITY

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        signals += FingerprintSignal.make(
            key = "model",
            category = category,
            name = "Model",
            value = "${Build.MANUFACTURER} ${Build.MODEL}",
            rationale =
                "The make and model of your phone. It narrows you to one product line " +
                    "before any other signal is read.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Manufacturer", Build.MANUFACTURER),
                SignalEntry("Model", Build.MODEL),
            ),
        )

        signals += FingerprintSignal.make(
            key = "hardware",
            category = category,
            name = "Hardware identifiers",
            value = "${Build.BRAND} / ${Build.DEVICE} / ${Build.PRODUCT}",
            rationale =
                "The internal brand, device, and product codes. Together they name the " +
                    "exact hardware variant, which is far more specific than the model alone.",
            displayHint = DisplayHint.KEY_VALUE,
            entries = listOf(
                SignalEntry("Brand", Build.BRAND),
                SignalEntry("Device", Build.DEVICE),
                SignalEntry("Product", Build.PRODUCT),
                SignalEntry("Hardware", Build.HARDWARE),
                SignalEntry("Board", Build.BOARD),
                SignalEntry("Bootloader", Build.BOOTLOADER),
            ),
        )

        signals += FingerprintSignal.make(
            key = "fingerprint",
            category = category,
            name = "Build fingerprint",
            value = Build.FINGERPRINT,
            rationale =
                "A single string that encodes the brand, device, build, and signing keys " +
                    "of your firmware. Two phones on the same update rarely differ.",
        )

        val androidId = readAndroidId()
        if (androidId != null) {
            signals += FingerprintSignal.make(
                key = "androidId",
                category = category,
                name = "Android ID",
                value = androidId,
                rationale =
                    "A stable identifier scoped to the app signing key since Android 8. It " +
                        "stays the same across launches, so it can link your sessions together. " +
                        "Read from Settings.Secure.",
            )
        }

        val serial = readSerial()
        signals += FingerprintSignal.make(
            key = "serial",
            category = category,
            name = "Serial number",
            value = serial,
            rationale =
                "The hardware serial number. Modern Android blocks apps from reading it, so " +
                    "this usually shows as unavailable. Read from Build.getSerial.",
        )

        return signals
    }

    @SuppressLint("HardwareIds")
    private fun readAndroidId(): String? = runCatching {
        Settings.Secure.getString(
            ctx.appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
    }.getOrNull()

    @SuppressLint("HardwareIds")
    private fun readSerial(): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION", "MissingPermission")
                Build.getSerial()
            } else {
                @Suppress("DEPRECATION")
                Build.SERIAL
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            ?: "unavailable (blocked by the system)"
    }
}
