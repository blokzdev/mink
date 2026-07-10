package com.mink.signals

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider

/**
 * The Wi-Fi around you, read through [WifiManager]. The set of access points in
 * range is often unique to your home or office, so it doubles as a location. The
 * network name and hardware addresses are redacted by the OS; Mink shows the
 * count, signal strength, and a coarse sample only.
 */
// Wi-Fi scan reads are gated by the NEARBY_WIFI_DEVICES / location grant at
// runtime and wrapped in runCatching; lint cannot trace that indirection.
@SuppressLint("MissingPermission")
class NearbyWifiProvider(
    private val ctx: ProviderContext,
) : SignalProvider {

    override val category: SignalCategory = SignalCategory.NEARBY_WIFI

    override suspend fun collect(): List<FingerprintSignal> {
        if (!PermissionedFormat.isGranted(ctx, permission)) {
            return PermissionedFormat.gateClosed(category)
        }

        val wifi = runCatching {
            ctx.appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return unavailable()

        val signals = mutableListOf<FingerprintSignal>()

        val enabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
        signals += FingerprintSignal.make(
            key = "state",
            category = category,
            name = "Wi-Fi radio",
            value = if (enabled) "on" else "off",
            rationale = "Whether the Wi-Fi radio is currently on.",
        )

        val info = runCatching { wifi.connectionInfo }.getOrNull()
        if (info != null && runCatching { info.networkId }.getOrDefault(-1) != -1) {
            val link = runCatching { info.linkSpeed }.getOrDefault(-1)
            if (link > 0) {
                signals += FingerprintSignal.make(
                    key = "linkSpeed",
                    category = category,
                    name = "Link speed",
                    value = "$link Mbps",
                    rationale = "The negotiated speed of your current Wi-Fi connection.",
                )
            }
            val freq = runCatching { info.frequency }.getOrDefault(-1)
            if (freq > 0) {
                val band = if (freq >= 5000) "5 GHz" else "2.4 GHz"
                signals += FingerprintSignal.make(
                    key = "frequency",
                    category = category,
                    name = "Channel band",
                    value = "$freq MHz ($band)",
                    rationale = "The radio band of the access point you are connected to.",
                )
            }
            val rssi = runCatching { info.rssi }.getOrNull()
            if (rssi != null) {
                signals += FingerprintSignal.make(
                    key = "rssi",
                    category = category,
                    name = "Signal strength",
                    value = "$rssi dBm",
                    rationale = "How strong your connection is, which tracks how close the router sits.",
                )
            }
        }

        val scan: List<ScanResult> = runCatching { wifi.scanResults }.getOrNull().orEmpty()
        signals += FingerprintSignal.make(
            key = "scanCount",
            category = category,
            name = "Access points in range",
            value = scan.size.toString(),
            rationale =
                "How many Wi-Fi networks your phone can see right now. That set of networks is " +
                    "often unique to a single place and works as a location.",
        )

        if (scan.isNotEmpty()) {
            val sample = scan
                .sortedByDescending { runCatching { it.level }.getOrDefault(Int.MIN_VALUE) }
                .take(SAMPLE_SIZE)
                .mapIndexed { index, result ->
                    val level = runCatching { result.level }.getOrDefault(0)
                    SignalEntry("Network ${index + 1}", "$level dBm")
                }
            signals += FingerprintSignal.make(
                key = "sample",
                category = category,
                name = "Strongest access points",
                value = sample.joinToString("\n") { "${it.label}: ${it.value}" },
                rationale =
                    "A sample of the nearby networks by signal strength, with names and hardware " +
                        "addresses redacted. The pattern of strengths still helps place you.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = sample,
            )
        }

        val bandSummary = bandSummary()
        if (bandSummary != null) {
            signals += FingerprintSignal.make(
                key = "bands",
                category = category,
                name = "Supported bands",
                value = bandSummary,
                rationale = "The Wi-Fi bands this phone's radio supports, a hardware trait.",
                displayHint = DisplayHint.TAGS,
            )
        }

        return signals
    }

    private fun bandSummary(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val wifi = runCatching {
            ctx.appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }.getOrNull() ?: return null
        val bands = mutableListOf<String>()
        runCatching { if (wifi.is5GHzBandSupported) bands += "5 GHz" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { if (wifi.is6GHzBandSupported) bands += "6 GHz" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { if (wifi.is60GHzBandSupported) bands += "60 GHz" }
        }
        // 2.4 GHz is universal on any Wi-Fi radio.
        bands.add(0, "2.4 GHz")
        return bands.joinToString(", ")
    }

    private fun unavailable(): List<FingerprintSignal> = listOf(
        FingerprintSignal.make(
            key = "unavailable",
            category = category,
            name = "Wi-Fi service",
            value = "Unavailable",
            rationale = "This phone did not return a Wi-Fi service to read from.",
        ),
    )

    private companion object {
        const val SAMPLE_SIZE = 8
    }
}
