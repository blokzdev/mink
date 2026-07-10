package com.mink.signals

import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.io.File

/**
 * Reads the processor surface any app can see with no prompt: the core count,
 * the supported ABIs, the hardware and feature strings from /proc/cpuinfo, and
 * the min/max clock range from cpufreq. The specific SoC, its feature flags, and
 * the exact big/little core split narrow you to a small family of devices. Mink
 * dedupes the per-core fields so the reading stays readable.
 */
class CpuProvider(ctx: ProviderContext) : SignalProvider {

    @Suppress("unused")
    private val providerContext = ctx

    override val category: SignalCategory = SignalCategory.CPU
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(0)
        signals += FingerprintSignal.make(
            key = "cores",
            category = category,
            name = "Processor cores",
            value = if (cores > 0) cores.toString() else "unknown",
            rationale = "How many cores your processor exposes. Web pages read the same number " +
                "through hardwareConcurrency. Read from availableProcessors.",
        )

        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.toList().orEmpty()
        } else {
            emptyList()
        }
        if (abis.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "abis",
                category = category,
                name = "Supported ABIs",
                value = abis.joinToString(", "),
                rationale = "The instruction sets your device runs, most specific first. It marks " +
                    "the chip generation. Read from Build.SUPPORTED_ABIS.",
                displayHint = DisplayHint.TAGS,
                entries = abis.map { SignalEntry(it, "") },
            )
        }

        val cpuInfo = parseCpuInfo(readProc("/proc/cpuinfo"))

        cpuInfo.hardware?.let { hardware ->
            signals += FingerprintSignal.make(
                key = "hardware",
                category = category,
                name = "Hardware",
                value = hardware,
                rationale = "The board or SoC name your kernel reports. It points at the exact " +
                    "chip. Read from /proc/cpuinfo.",
            )
        }

        if (cpuInfo.features.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "features",
                category = category,
                name = "CPU features",
                value = "${cpuInfo.features.size} flags",
                rationale = "The instruction-set feature flags the CPU advertises. The exact set " +
                    "fingerprints the chip revision.",
                displayHint = DisplayHint.TAGS,
                entries = cpuInfo.features.map { SignalEntry(it, "") },
            )
        }

        if (cpuInfo.implementerParts.isNotEmpty()) {
            signals += FingerprintSignal.make(
                key = "cores.detail",
                category = category,
                name = "Core types",
                value = "${cpuInfo.implementerParts.size} distinct",
                rationale = "The distinct implementer and part IDs across your cores. A big/little " +
                    "split shows here and marks the SoC layout.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = cpuInfo.implementerParts.map { (id, count) ->
                    SignalEntry(id, "x$count")
                },
            )
        }

        val freq = readFrequencyRange()
        if (freq != null) {
            signals += FingerprintSignal.make(
                key = "freq",
                category = category,
                name = "Clock range",
                value = "${formatMhz(freq.first)} to ${formatMhz(freq.second)}",
                rationale = "The lowest and highest core clocks your device allows. The pair is a " +
                    "steady per-model trait. Read from cpufreq.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Minimum", formatMhz(freq.first)),
                    SignalEntry("Maximum", formatMhz(freq.second)),
                ),
            )
        }

        return signals
    }

    private fun readProc(path: String): String = runCatching {
        File(path).takeIf { it.canRead() }?.readText().orEmpty()
    }.getOrDefault("")

    private fun readFrequencyRange(): Pair<Long, Long>? = runCatching {
        val cpuRoot = File("/sys/devices/system/cpu")
        val cpuDirs = cpuRoot.listFiles { file ->
            file.isDirectory && file.name.matches(Regex("cpu\\d+"))
        }.orEmpty()

        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        for (dir in cpuDirs) {
            val minKhz = readKhz(File(dir, "cpufreq/cpuinfo_min_freq"))
            val maxKhz = readKhz(File(dir, "cpufreq/cpuinfo_max_freq"))
            if (minKhz != null && minKhz < min) min = minKhz
            if (maxKhz != null && maxKhz > max) max = maxKhz
        }
        if (min == Long.MAX_VALUE || max == Long.MIN_VALUE) null else min to max
    }.getOrNull()

    private fun readKhz(file: File): Long? = runCatching {
        file.takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
    }.getOrNull()

    /** Result of parsing /proc/cpuinfo: the fields that individuate a device. */
    data class CpuInfo(
        val hardware: String?,
        val features: List<String>,
        val implementerParts: List<Pair<String, Int>>,
    )

    companion object {

        /** Formats a cpufreq value in kHz as a MHz string. Pure and testable. */
        fun formatMhz(khz: Long): String =
            if (khz <= 0) "unknown" else "${khz / 1000} MHz"

        /**
         * Parses the raw text of /proc/cpuinfo into the individuating fields,
         * deduping the per-core implementer/part pairs into counts. Pure (no
         * Android dependency) so the branching is unit tested off device.
         */
        fun parseCpuInfo(raw: String): CpuInfo {
            if (raw.isBlank()) return CpuInfo(null, emptyList(), emptyList())

            var hardware: String? = null
            var features: List<String> = emptyList()

            var currentImplementer: String? = null
            val partCounts = LinkedHashMap<String, Int>()

            for (line in raw.lineSequence()) {
                val idx = line.indexOf(':')
                if (idx < 0) continue
                val keyText = line.substring(0, idx).trim().lowercase()
                val valueText = line.substring(idx + 1).trim()
                if (valueText.isEmpty()) continue

                when (keyText) {
                    "hardware" -> if (hardware == null) hardware = valueText
                    "features", "flags" ->
                        if (features.isEmpty()) {
                            features = valueText.split(Regex("\\s+")).filter { it.isNotBlank() }
                        }
                    "cpu implementer" -> currentImplementer = valueText
                    "cpu part" -> {
                        val label = buildString {
                            currentImplementer?.let { append("impl $it ") }
                            append("part $valueText")
                        }.trim()
                        partCounts[label] = (partCounts[label] ?: 0) + 1
                        currentImplementer = null
                    }
                }
            }

            return CpuInfo(
                hardware = hardware,
                features = features,
                implementerParts = partCounts.entries.map { it.key to it.value },
            )
        }
    }
}
