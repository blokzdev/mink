package com.mink.guardian

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * A read of the device's memory, CPU, and ABI, mapped to the [GuardianTier]
 * the guardian can safely run at. The tier decides which MiniCPM5-1B quant (if
 * any) the model manager downloads and loads.
 *
 * The pure mapping lives in [selectTier] so it can be unit tested without an
 * Android [Context].
 */
data class DeviceCapability(
    val totalRamBytes: Long,
    val cores: Int,
    val primaryAbi: String,
    val is64Bit: Boolean,
    val nativeAvailable: Boolean,
) {
    val tier: GuardianTier
        get() = selectTier(totalRamBytes, is64Bit, nativeAvailable)

    /** Human readable RAM, e.g. "8 GB". */
    val ramLabel: String
        get() {
            val gb = totalRamBytes.toDouble() / GIB
            return if (gb >= 10) "${gb.toInt()} GB" else String.format("%.1f GB", gb)
        }

    companion object {
        /** One gibibyte in bytes. Thresholds are expressed in these units. */
        const val GIB: Long = 1024L * 1024L * 1024L

        /** FULL needs at least this much RAM (7 GiB, comfortably an 8 GB device). */
        const val FULL_MIN_BYTES: Long = 7L * GIB

        /** LITE needs at least 4 GiB. */
        const val LITE_MIN_BYTES: Long = 4L * GIB

        /** MINIMAL needs at least 3 GiB. */
        const val MINIMAL_MIN_BYTES: Long = 3L * GIB

        /**
         * Pure tier selection. Kept free of Android types so it is directly
         * unit-testable. A device that is not 64-bit, or whose native bridge
         * failed to load, always falls back to [GuardianTier.RULES_ONLY].
         */
        fun selectTier(
            totalRamBytes: Long,
            is64Bit: Boolean,
            nativeAvailable: Boolean,
        ): GuardianTier {
            if (!nativeAvailable || !is64Bit) return GuardianTier.RULES_ONLY
            return when {
                totalRamBytes >= FULL_MIN_BYTES -> GuardianTier.FULL
                totalRamBytes >= LITE_MIN_BYTES -> GuardianTier.LITE
                totalRamBytes >= MINIMAL_MIN_BYTES -> GuardianTier.MINIMAL
                else -> GuardianTier.RULES_ONLY
            }
        }

        /**
         * Read the live device capability. Never throws: unreadable fields
         * degrade to conservative defaults that push the tier downward.
         */
        fun detect(context: Context, nativeAvailable: Boolean): DeviceCapability {
            val totalRam = runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val info = ActivityManager.MemoryInfo()
                am.getMemoryInfo(info)
                info.totalMem
            }.getOrDefault(0L)

            val cores = runCatching { Runtime.getRuntime().availableProcessors() }
                .getOrDefault(1)

            val abis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
            val primaryAbi = abis.firstOrNull().orEmpty()
            val is64Bit = runCatching {
                Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            }.getOrDefault(false) ||
                primaryAbi == "arm64-v8a" || primaryAbi == "x86_64"

            return DeviceCapability(
                totalRamBytes = totalRam,
                cores = cores,
                primaryAbi = primaryAbi,
                is64Bit = is64Bit,
                nativeAvailable = nativeAvailable,
            )
        }
    }
}
