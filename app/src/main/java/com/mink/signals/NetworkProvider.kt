package com.mink.signals

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Reads the network surface any app can see with no prompt: which transports
 * are active, the bandwidth the system reports, whether a VPN is in place, the
 * private DNS mode, and the list of network interfaces. Full addresses and MAC
 * addresses are never shown. Mink reports what is exposed, not the identifiers
 * themselves: counts, scopes, and transports.
 */
class NetworkProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.NETWORK
    override val permission: PermissionKind? = null

    override suspend fun collect(): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        val connectivity = runCatching {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

        if (connectivity != null) {
            addActiveNetwork(connectivity, signals)
            addLinkProperties(connectivity, signals)
        }

        addPrivateDns(signals)
        addInterfaces(signals)

        return signals
    }

    private fun addActiveNetwork(
        connectivity: ConnectivityManager,
        signals: MutableList<FingerprintSignal>,
    ) {
        runCatching {
            val active = connectivity.activeNetwork ?: return@runCatching
            val caps = connectivity.getNetworkCapabilities(active) ?: return@runCatching

            val transports = transportNames(caps)
            signals += FingerprintSignal.make(
                key = "transports",
                category = category,
                name = "Active transports",
                value = if (transports.isEmpty()) "(none)" else transports.joinToString(", "),
                rationale = "Which network types carry your traffic right now, such as Wi-Fi, " +
                    "cellular, or a VPN tunnel.",
                displayHint = if (transports.isEmpty()) DisplayHint.PLAIN else DisplayHint.TAGS,
                entries = transports.takeIf { it.isNotEmpty() }?.map { SignalEntry(it, "") },
            )

            val vpnActive = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            signals += FingerprintSignal.make(
                key = "vpn",
                category = category,
                name = "VPN active",
                value = vpnActive.toString(),
                rationale = "Whether a VPN is routing your traffic. Any app can detect the tunnel " +
                    "without seeing its address.",
            )

            signals += FingerprintSignal.make(
                key = "bandwidth",
                category = category,
                name = "Reported bandwidth",
                value = "down ${formatKbps(caps.linkDownstreamBandwidthKbps)}, " +
                    "up ${formatKbps(caps.linkUpstreamBandwidthKbps)}",
                rationale = "The link speed the system estimates. It hints at your connection " +
                    "type and quality.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = listOf(
                    SignalEntry("Downstream", formatKbps(caps.linkDownstreamBandwidthKbps)),
                    SignalEntry("Upstream", formatKbps(caps.linkUpstreamBandwidthKbps)),
                ),
            )

            val metered = runCatching { connectivity.isActiveNetworkMetered }.getOrDefault(false)
            signals += FingerprintSignal.make(
                key = "metered",
                category = category,
                name = "Metered connection",
                value = metered.toString(),
                rationale = "Whether the system treats this connection as metered, which usually " +
                    "means cellular.",
            )
        }
    }

    private fun addLinkProperties(
        connectivity: ConnectivityManager,
        signals: MutableList<FingerprintSignal>,
    ) {
        runCatching {
            val active = connectivity.activeNetwork ?: return@runCatching
            val link: LinkProperties = connectivity.getLinkProperties(active) ?: return@runCatching

            val addresses = link.linkAddresses.map { it.address }
            val scopes = addresses.map { classifyAddress(it) }
            val summary = scopes.groupingBy { it }.eachCount()
            signals += FingerprintSignal.make(
                key = "linkAddresses",
                category = category,
                name = "Link addresses",
                value = "${addresses.size} present",
                rationale = "How many addresses the active link holds and their scope. The " +
                    "addresses themselves are redacted.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = summary.map { (scope, count) -> SignalEntry(scope, count.toString()) }
                    .ifEmpty { listOf(SignalEntry("Addresses", "0")) },
            )

            val dnsCount = runCatching { link.dnsServers.size }.getOrDefault(0)
            signals += FingerprintSignal.make(
                key = "dnsServers",
                category = category,
                name = "DNS servers",
                value = "$dnsCount configured",
                rationale = "How many DNS resolvers the link uses. The count is shown; the " +
                    "addresses are redacted.",
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val privateDnsName = link.privateDnsServerName
                if (!privateDnsName.isNullOrBlank()) {
                    signals += FingerprintSignal.make(
                        key = "privateDnsServer",
                        category = category,
                        name = "Private DNS host",
                        value = privateDnsName,
                        rationale = "The private DNS provider you chose. It is a configuration " +
                            "value, so Mink reveals it.",
                    )
                }
            }
        }
    }

    private fun addPrivateDns(signals: MutableList<FingerprintSignal>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val mode = Settings.Global.getString(appContext.contentResolver, "private_dns_mode")
            signals += FingerprintSignal.make(
                key = "privateDnsMode",
                category = category,
                name = "Private DNS mode",
                value = mode ?: "unknown",
                rationale = "Whether private DNS is off, automatic, or set to a specific host. A " +
                    "custom setting stands out.",
            )
        }
    }

    private fun addInterfaces(signals: MutableList<FingerprintSignal>) {
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()

            signals += FingerprintSignal.make(
                key = "interfaceCount",
                category = category,
                name = "Network interfaces",
                value = interfaces.size.toString(),
                rationale = "How many interfaces the device has. The lineup is fairly stable per " +
                    "model.",
            )

            for (iface in interfaces) {
                runCatching {
                    val addresses = iface.interfaceAddresses.orEmpty()
                    val scopes = addresses.mapNotNull { it.address?.let(::classifyAddress) }
                    val hasMac = runCatching { iface.hardwareAddress != null }.getOrDefault(false)
                    signals += FingerprintSignal.make(
                        key = "iface.${iface.name}",
                        category = category,
                        name = iface.name,
                        value = buildString {
                            append(if (iface.isUp) "up" else "down")
                            if (iface.isLoopback) append(", loopback")
                            append(", ${addresses.size} addr")
                        },
                        rationale = "One interface and what it exposes. Addresses and the MAC are " +
                            "redacted; Mink shows only presence, scope, and counts.",
                        displayHint = DisplayHint.KEY_VALUE,
                        entries = buildList {
                            add(SignalEntry("Up", iface.isUp.toString()))
                            add(SignalEntry("Loopback", iface.isLoopback.toString()))
                            add(SignalEntry("MTU", runCatching { iface.mtu.toString() }.getOrDefault("unknown")))
                            add(SignalEntry("Addresses", addresses.size.toString()))
                            add(SignalEntry("Hardware address", if (hasMac) "present (redacted)" else "not exposed"))
                            if (scopes.isNotEmpty()) {
                                add(SignalEntry("Scopes", scopes.distinct().joinToString(", ")))
                            }
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Lists the transport names present in [caps]. Kept small and defensive. */
        fun transportNames(caps: NetworkCapabilities): List<String> {
            val names = mutableListOf<String>()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names += "Wi-Fi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names += "Cellular"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names += "Ethernet"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) names += "Bluetooth"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names += "VPN"
            return names
        }

        /** Formats a kbps figure as a rough human-readable rate. Pure and testable. */
        fun formatKbps(kbps: Int): String = when {
            kbps <= 0 -> "unknown"
            kbps >= 1_000_000 -> "%.1f Gbps".format(kbps / 1_000_000.0)
            kbps >= 1_000 -> "%.1f Mbps".format(kbps / 1_000.0)
            else -> "$kbps kbps"
        }

        /**
         * Classifies an address into a family and scope without revealing it.
         * Pure (java.net only) and testable.
         */
        fun classifyAddress(address: InetAddress): String {
            val family = when (address) {
                is Inet6Address -> "IPv6"
                is Inet4Address -> "IPv4"
                else -> "IP"
            }
            val scope = when {
                address.isLoopbackAddress -> "loopback"
                address.isLinkLocalAddress -> "link-local"
                address.isSiteLocalAddress -> "private"
                address.isAnyLocalAddress -> "wildcard"
                address.isMulticastAddress -> "multicast"
                else -> "global"
            }
            return "$family $scope"
        }
    }
}
