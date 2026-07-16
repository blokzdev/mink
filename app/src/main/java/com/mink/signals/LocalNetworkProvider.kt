package com.mink.signals

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.core.model.SignalEntry
import com.mink.core.provider.ProviderContext
import com.mink.core.provider.SignalProvider
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Browses the local Wi-Fi for devices that advertise themselves over mDNS /
 * DNS-SD (Chromecasts, AirPlay speakers, printers, HomeKit and Matter gear, and
 * so on). The set of devices on a home network is fairly stable and often
 * unique, and any app can read it with no runtime prompt, so it is a genuine
 * fingerprinting surface Loupe exercises on iOS.
 *
 * Platform reality this leans on:
 * - [NsdManager] discovery works API 26-35 with no runtime permission; only
 *   INTERNET is required, and it is already declared. A [WifiManager.MulticastLock]
 *   is acquired defensively (it needs the install-time CHANGE_WIFI_MULTICAST_STATE
 *   permission, which prompts for nothing) so multicast replies are not filtered.
 * - Each [NsdManager.discoverServices] call browses exactly one service type, so
 *   there is one fresh [NsdManager.DiscoveryListener] per type. Discovery is
 *   continuous, so every started listener is stopped again before [collect]
 *   returns.
 * - The name and type are already present at onServiceFound, so no resolve step
 *   is needed. Device names are shown to the user but never written to the log.
 * - On an emulator (and on any device with no Wi-Fi) discovery returns nothing;
 *   that is expected and reported as a count of zero, never as an error.
 */
class LocalNetworkProvider(ctx: ProviderContext) : SignalProvider {

    private val appContext: Context = ctx.appContext

    override val category: SignalCategory = SignalCategory.LOCAL_NETWORK
    override val permission: PermissionKind? = null

    /**
     * Discover once and report what was seen. Fully exception-safe: any failure
     * yields an empty list, and a successful run with nothing found still emits
     * the count-of-zero signal rather than an error.
     */
    override suspend fun collect(): List<FingerprintSignal> = withContext(Dispatchers.IO) {
        val nsd = runCatching {
            appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
        }.getOrNull() ?: return@withContext emptyList()

        val discovered = Collections.synchronizedSet(mutableSetOf<Discovered>())
        val lock = acquireMulticastLock()
        try {
            discover(nsd, discovered)
        } finally {
            releaseMulticastLock(lock)
        }

        val devices = synchronized(discovered) { discovered.toList() }
        buildSignals(devices)
    }

    /**
     * Start a fresh listener for every curated type in waves, keep them all
     * running for a bounded window, then stop every listener that was registered.
     * A listener is tracked the moment [NsdManager.discoverServices] returns
     * (before its async onDiscoveryStarted fires), so a cancellation during the
     * wave loop or the window still stops it and nothing leaks. A listener whose
     * start later failed throws "not registered" from stopServiceDiscovery, which
     * the runCatching swallows.
     */
    private suspend fun discover(nsd: NsdManager, sink: MutableSet<Discovered>) {
        val registered = Collections.synchronizedSet(mutableSetOf<NsdManager.DiscoveryListener>())
        try {
            for (wave in SERVICE_TYPES.chunked(WAVE_SIZE)) {
                for (type in wave) {
                    val listener = newListener(type, sink)
                    val ok = runCatching {
                        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    }.isSuccess
                    if (ok) registered.add(listener)
                }
                // Stagger the waves so a burst of starts does not trip the
                // platform's concurrent-discovery cap (FAILURE_MAX_LIMIT).
                delay(WAVE_STAGGER_MS)
            }
            delay(DISCOVERY_WINDOW_MS)
        } finally {
            val toStop = synchronized(registered) { registered.toList() }
            for (listener in toStop) {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }

    /**
     * A listener for one service type; it tags each found service with the type
     * it was browsing so the caller can label it. Lifecycle callbacks are no-ops:
     * the caller tracks and stops every listener it registered.
     */
    private fun newListener(
        type: String,
        sink: MutableSet<Discovered>,
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = runCatching { serviceInfo.serviceName }.getOrNull()?.trim().orEmpty()
            if (name.isNotEmpty()) sink.add(Discovered(type, name))
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            // A single browse is a point-in-time read; a lost service is ignored.
        }
    }

    /** Acquire a reference-counted multicast lock, or null if it cannot be had. */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@runCatching null
        wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
    }.getOrNull()

    /** Release the multicast lock if it is held; never throws. */
    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        runCatching {
            if (lock != null && lock.isHeld) lock.release()
        }
    }

    /** Turn the deduplicated set of devices into the category's signals. */
    private fun buildSignals(devices: List<Discovered>): List<FingerprintSignal> {
        val signals = mutableListOf<FingerprintSignal>()

        // Collapse to distinct (name, friendly-label) AFTER labelling: one printer
        // advertising several print protocols is one device, not four, so the count
        // and the list are not inflated by the type-family labels.
        val shown = devices
            .map { SignalEntry(it.name, serviceLabel(it.type)) }
            .distinct()

        signals += FingerprintSignal.make(
            key = "count",
            category = category,
            name = "Discovered devices",
            value = shown.size.toString(),
            rationale = "The set of devices on your Wi-Fi is fairly stable and often unique to " +
                "your home. Any app can browse them over the network with no prompt.",
        )

        if (shown.isNotEmpty()) {
            val entries = shown
                .sortedWith(compareBy({ it.value }, { it.label }))
            signals += FingerprintSignal.make(
                key = "nearby",
                category = category,
                name = "Nearby devices",
                value = entries.joinToString(", ") { "${it.label}: ${it.value}" },
                rationale = "These devices announce themselves on the same Wi-Fi. Mink reads only " +
                    "what they broadcast, and nothing here leaves your phone.",
                displayHint = DisplayHint.KEY_VALUE,
                entries = entries,
            )
        }

        return signals
    }

    /** One discovered service, deduplicated by its type and advertised name. */
    private data class Discovered(val type: String, val name: String)

    companion object {
        /** How many discoveries to launch per wave; kept under the platform cap. */
        private const val WAVE_SIZE = 6

        /** Small gap between waves so a burst of starts does not hit the cap. */
        private const val WAVE_STAGGER_MS = 300L

        /** How long to let discovery run before stopping every listener. */
        private const val DISCOVERY_WINDOW_MS = 4000L

        /** Tag for the defensive multicast lock; never shown to the user. */
        private const val MULTICAST_LOCK_TAG = "mink-mdns"

        /**
         * The curated DNS-SD service types Mink browses. Chosen for the everyday
         * home devices a person recognizes; the current HomeKit type is _hap._tcp
         * and Matter commissioning is _matterc._udp.
         */
        private val SERVICE_TYPES: List<String> = listOf(
            "_googlecast._tcp",
            "_airplay._tcp",
            "_raop._tcp",
            "_sonos._tcp",
            "_spotify-connect._tcp",
            "_hap._tcp",
            "_hue._tcp",
            "_matterc._udp",
            "_matter._tcp",
            "_ipp._tcp",
            "_ipps._tcp",
            "_printer._tcp",
            "_pdl-datastream._tcp",
            "_uscan._tcp",
            "_uscans._tcp",
            "_scanner._tcp",
            "_smb._tcp",
            "_afpovertcp._tcp",
            "_ssh._tcp",
            "_sftp-ssh._tcp",
            "_amzn-wplay._tcp",
            "_nvstream._tcp",
            "_companion-link._tcp",
            "_apple-mobdev2._tcp",
            "_http._tcp",
            "_https._tcp",
            "_workstation._tcp",
        )

        /** Friendly label for each browsed type; the raw fallback map lives here. */
        private val SERVICE_LABELS: Map<String, String> = mapOf(
            "_googlecast._tcp" to "Chromecast or Google TV",
            "_airplay._tcp" to "AirPlay",
            "_raop._tcp" to "AirPlay",
            "_sonos._tcp" to "Sonos",
            "_spotify-connect._tcp" to "Spotify Connect",
            "_hap._tcp" to "HomeKit accessory",
            "_hue._tcp" to "Philips Hue",
            "_matterc._udp" to "Matter device",
            "_matter._tcp" to "Matter device",
            "_ipp._tcp" to "Printer",
            "_ipps._tcp" to "Printer",
            "_printer._tcp" to "Printer",
            "_pdl-datastream._tcp" to "Printer",
            "_uscan._tcp" to "Scanner",
            "_uscans._tcp" to "Scanner",
            "_scanner._tcp" to "Scanner",
            "_smb._tcp" to "File share",
            "_afpovertcp._tcp" to "File share",
            "_ssh._tcp" to "SSH host",
            "_sftp-ssh._tcp" to "SSH host",
            "_amzn-wplay._tcp" to "Fire TV",
            "_nvstream._tcp" to "NVIDIA Shield",
            "_companion-link._tcp" to "Apple device",
            "_apple-mobdev2._tcp" to "Apple device",
            "_http._tcp" to "Web device",
            "_https._tcp" to "Web device",
            "_workstation._tcp" to "Computer",
        )

        /**
         * Map a DNS-SD service type to a friendly device label, falling back to
         * the type itself for anything unrecognized. Pure and testable: the type
         * is trimmed of any trailing dot and lowercased before lookup.
         */
        internal fun serviceLabel(type: String): String {
            val cleaned = type.trim().trimEnd('.')
            val key = cleaned.lowercase(Locale.US)
            return SERVICE_LABELS[key] ?: cleaned
        }
    }
}
