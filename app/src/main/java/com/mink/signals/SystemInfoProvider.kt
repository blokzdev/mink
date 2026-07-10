package com.mink.signals

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The OS build and kernel state. This is one of the most potent passive
 * surfaces: the security patch level, kernel build string, and time since boot
 * rarely collide across devices, so together they read almost like an
 * identifier. Nothing here needs a permission.
 */
class SystemInfoProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.SYSTEM_INFO

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        signals += FingerprintSignal.make(
            key = "release",
            category = category,
            name = "Android version",
            value = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            rationale =
                "The Android release and API level you run. It sorts you into the slice of " +
                    "users on the same version.",
            displayHint = DisplayHint.COMPOUND,
            entries = listOf(
                SignalEntry("Release", Build.VERSION.RELEASE ?: "unknown"),
                SignalEntry("SDK", Build.VERSION.SDK_INT.toString()),
            ),
        )

        val patch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (patch != null) {
            signals += FingerprintSignal.make(
                key = "securityPatch",
                category = category,
                name = "Security patch level",
                value = patch,
                rationale =
                    "The date of your last security update. Combined with the model it narrows " +
                        "you to the phones patched on the same schedule.",
            )
        }

        signals += FingerprintSignal.make(
            key = "incremental",
            category = category,
            name = "Build increment",
            value = Build.VERSION.INCREMENTAL,
            rationale =
                "The internal build number of your firmware. It is more specific than the " +
                    "public version and often unique to a carrier or region.",
        )

        signals += FingerprintSignal.make(
            key = "buildTime",
            category = category,
            name = "Build time",
            value = formatTimestamp(Build.TIME),
            rationale = "When your system image was built. Read from Build.TIME.",
        )

        signals += FingerprintSignal.make(
            key = "arch",
            category = category,
            name = "Architecture",
            value = System.getProperty("os.arch") ?: "unknown",
            rationale = "The processor architecture apps compile against. Read from os.arch.",
        )

        signals += FingerprintSignal.make(
            key = "kernel",
            category = category,
            name = "Kernel version",
            value = readKernelVersion(),
            rationale =
                "The kernel build string, with the compiler and timestamp that produced it. " +
                    "It is close to unique across devices. Read from /proc/version.",
        )

        val uptimeMs = SystemClock.elapsedRealtime()
        signals += FingerprintSignal.make(
            key = "uptime",
            category = category,
            name = "Uptime",
            value = PassiveFormat.formatDuration(uptimeMs),
            rationale =
                "How long since your phone last booted. It changes only on restart, so a page " +
                    "revisited in the same session sees a matching value. Read from " +
                    "SystemClock.elapsedRealtime.",
        )

        signals += FingerprintSignal.make(
            key = "bootTime",
            category = category,
            name = "Running since",
            value = formatTimestamp(System.currentTimeMillis() - uptimeMs),
            rationale =
                "The moment your phone booted. Two apps that compute it agree to the second, " +
                    "which links them together.",
        )

        val bootCount = readBootCount()
        if (bootCount != null) {
            signals += FingerprintSignal.make(
                key = "bootCount",
                category = category,
                name = "Boot count",
                value = bootCount.toString(),
                rationale =
                    "How many times this phone has booted since setup. A slowly rising counter " +
                        "that individuates you over time. Read from Settings.Global.",
            )
        }

        return signals
    }

    private fun readKernelVersion(): String {
        runCatching {
            val fromProc = File("/proc/version").takeIf { it.canRead() }?.readText()?.trim()
            if (!fromProc.isNullOrBlank()) return fromProc
        }
        return System.getProperty("os.version") ?: "unavailable"
    }

    private fun readBootCount(): Int? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Settings.Global.getInt(
                ctx.appContext.contentResolver,
                Settings.Global.BOOT_COUNT,
            )
        } else {
            null
        }
    }.getOrNull()

    private fun formatTimestamp(millis: Long): String = runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.format(Date(millis))
    }.getOrDefault(millis.toString())
}
