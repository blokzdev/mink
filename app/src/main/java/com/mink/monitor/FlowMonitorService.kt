package com.mink.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A DNS-only local [VpnService]. It routes *only* its sentinel DNS address
 * ([SENTINEL_DNS]) into the tunnel, so every other packet the device sends
 * bypasses Mink entirely — the phone's connectivity and battery are barely
 * touched. For each DNS query it:
 *  1. reads the requested host name,
 *  2. attributes it to the requesting app via
 *     [ConnectivityManager.getConnectionOwnerUid] (Android 10+),
 *  3. records the (app, host) pair in [DnsFlowHub], and
 *  4. forwards the query unchanged to the network's real resolver and writes the
 *     answer back, so nothing the user does breaks. The resolver is tracked live
 *     ([watchUpstreamResolver]); only while no network has advertised one yet do
 *     queries fall back to a public resolver ([FALLBACK_DNS]).
 *
 * It never inspects payloads, never proxies non-DNS traffic, and never sends
 * anything off-device except the same DNS queries the resolver would have sent
 * anyway. It holds the single system VPN slot while active (the unavoidable cost
 * of any VpnService) and shows an ongoing notification with a Stop action. It is
 * also the single writer of the persisted enabled flag ([DnsFlowStore]), so what
 * boot resume reads always reflects a transition that actually happened.
 */
class FlowMonitorService : VpnService() {

    @Volatile private var running = false
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var forwarders: ExecutorService? = null
    private val writeLock = Any()
    private var out: FileOutputStream? = null
    @Volatile private var foreground = false
    @Volatile private var upstream: InetAddress = InetAddress.getByName(FALLBACK_DNS)
    private var upstreamWatcher: ConnectivityManager.NetworkCallback? = null
    private lateinit var pm: PackageManager

    /**
     * The service is the single writer of the persisted enabled flag, so it can
     * only ever record transitions that really happened: `false` on an explicit
     * stop command, `true` only after a tunnel is actually up. Launches from the
     * main thread land on DataStore in call order, so a fast stop-after-start
     * can never persist stale state. Never cancelled: a final write must land
     * even while the service is being destroyed.
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { DnsFlowStore(applicationContext) }

    private fun persistEnabled(value: Boolean) {
        persistScope.launch { store.saveEnabled(value) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // An explicit user stop (notification action or the in-app toggle):
            // record the choice so a reboot or app update never resurrects a
            // monitor the user turned off. stopSelf() here also covers the case
            // where the stop command spun up a fresh idle instance that
            // stopEverything()'s early-return would otherwise leave started.
            persistEnabled(false)
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        pm = packageManager
        upstream = discoverUpstreamResolver()

        // Promote to foreground BEFORE establishing. The specialUse FGS type does
        // not require being the active VPN, and going foreground first satisfies
        // startForegroundService's start-within-timeout contract even when this was
        // launched from the boot receiver (a background start). Guarded: on a
        // START_STICKY restart the system may deny the promotion
        // (ForegroundServiceStartNotAllowedException); fail into a clean stop
        // rather than an uncaught crash.
        val promoted = runCatching { startAsForeground() }
        if (promoted.isFailure) {
            Log.w(TAG, "foreground promotion denied; stopping: ${promoted.exceptionOrNull()}")
            stopEverything()
            stopSelf()
            return START_NOT_STICKY
        }

        val pfd = runCatching {
            Builder()
                .setSession("Mink DNS monitor")
                .addAddress(TUN_ADDR, 32)
                .addDnsServer(SENTINEL_DNS)          // apps resolve via this address...
                .addRoute(SENTINEL_DNS, 32)          // ...and only that address enters the tunnel
                .setBlocking(true)
                .setMtu(MTU)
                .establish()
        }.getOrNull()
        if (pfd == null) {
            Log.w(TAG, "establish() failed or was denied; stopping")
            stopEverything()
            return START_NOT_STICKY
        }

        running = true
        tunnel = pfd
        out = FileOutputStream(pfd.fileDescriptor)
        // Bounded queue with a discard policy: under a burst against a slow upstream
        // the backlog cannot grow without limit; excess queries are dropped and the
        // app simply retries, rather than piling up past its own DNS timeout.
        forwarders = ThreadPoolExecutor(
            FORWARDER_THREADS, FORWARDER_THREADS, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(MAX_QUEUED_QUERIES),
            ThreadPoolExecutor.DiscardPolicy(),
        )
        watchUpstreamResolver()
        DnsFlowHub.setRunning(true)
        DnsFlowHub.setAlwaysOn(readAlwaysOn())
        // Only a real, established session arms boot resume — a denied consent
        // dialog or a failed start can never leave a stale enabled=true behind.
        persistEnabled(true)
        thread(name = "dns-flow-reader") { readLoop(pfd) }
        return START_STICKY
    }

    /** Whether the system's always-on VPN setting points at Mink (API 29+, like the feature). */
    private fun readAlwaysOn(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { isAlwaysOn }.getOrDefault(false)

    /**
     * The network's own resolver, captured before we establish (afterwards ours
     * would shadow it). Any address family is honoured — forwarding sends over a
     * dual-stack socket and the app-facing reply is rebuilt as IPv4 regardless —
     * so an IPv6-only network keeps using its real resolver rather than silently
     * being redirected to a public one. The public fallback is a genuine last
     * resort for a network that advertises no resolver at all; unlike the first
     * cut it is no longer frozen for the session — [watchUpstreamResolver] swaps
     * in the real resolver the moment one is known.
     */
    private fun discoverUpstreamResolver(): InetAddress {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val servers = runCatching {
            cm?.getLinkProperties(cm.activeNetwork)?.dnsServers.orEmpty()
        }.getOrDefault(emptyList())
        val real = servers.firstOrNull { it.hostAddress != SENTINEL_DNS }
        return real ?: InetAddress.getByName(FALLBACK_DNS)
    }

    /**
     * Keep [upstream] pointed at the resolver the current underlying network
     * actually chose. The one-shot discovery above can miss: a boot-resume start
     * often runs before any network is up, and the initial network can change
     * (wifi to cellular) mid-session. Without this, every DNS query on the device
     * would keep flowing to the public fallback — or a dead resolver — for as
     * long as the monitor runs, which breaks both connectivity and the promise
     * that queries go to the network's own resolver. The request asks for real
     * internet transports (a NetworkRequest excludes VPNs by default), so our own
     * tunnel's sentinel never shadows the answer.
     */
    private fun watchUpstreamResolver() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val real = linkProperties.dnsServers.firstOrNull { it.hostAddress != SENTINEL_DNS }
                if (real != null && real != upstream) {
                    upstream = real
                    Log.i(TAG, "upstream resolver now ${real.hostAddress}")
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { upstreamWatcher = callback }
            .onFailure { Log.w(TAG, "resolver watcher unavailable: $it") }
    }

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(MTU)
        try {
            while (running) {
                val n = try { input.read(buf) } catch (e: Exception) { break }
                if (n <= 0) continue
                val pkt = parseIpv4Udp(buf, n) ?: continue
                if (pkt.dstPort != 53) continue
                // Copy the datagram out of the shared read buffer before handing to a worker.
                val datagram = buf.copyOf(n)
                try {
                    forwarders?.execute { handleQuery(datagram, pkt) }
                } catch (e: RejectedExecutionException) {
                    break                                            // pool shut down concurrently: we are stopping
                }
            }
        } finally {
            runCatching { input.close() }
            stopEverything()
        }
    }

    private fun handleQuery(datagram: ByteArray, pkt: Ipv4Udp) {
        val now = System.currentTimeMillis()
        val host = parseDnsQuestionName(datagram, pkt.payloadOffset, datagram.size)
        if (host.isNotEmpty()) {
            val uid = ownerUid(pkt)
            val id = identify(uid)
            DnsFlowHub.record(uid, id.packageName, id.label, id.isSystem, host, now)
        }
        forward(datagram, pkt)
    }

    /** Forward the query to the real resolver over the underlying network and reply to the app. */
    private fun forward(datagram: ByteArray, pkt: Ipv4Udp) {
        val payload = datagram.copyOfRange(pkt.payloadOffset, pkt.payloadOffset + pkt.payloadLength)
        runCatching {
            DatagramSocket().use { sock ->
                protect(sock)
                sock.soTimeout = UPSTREAM_TIMEOUT_MS
                sock.send(DatagramPacket(payload, payload.size, upstream, 53))
                // Cap the reply so the rebuilt IPv4+UDP packet (20+8+payload) can
                // never exceed the TUN MTU. Real DNS-over-UDP answers are far smaller.
                val reply = ByteArray(MTU - 28)
                val dp = DatagramPacket(reply, reply.size)
                sock.receive(dp)
                // Build a response addressed back to the app: sentinel:53 -> app:srcPort.
                val response = buildIpv4UdpPacket(
                    srcIp = pkt.dstIp, srcPort = 53,
                    dstIp = pkt.srcIp, dstPort = pkt.srcPort,
                    payload = reply, payloadLen = dp.length,
                )
                synchronized(writeLock) { out?.write(response) }
            }
        }
    }

    private fun ownerUid(pkt: Ipv4Udp): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return -1
        return runCatching {
            cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(pkt.srcIp), pkt.srcPort),
                InetSocketAddress(InetAddress.getByAddress(pkt.dstIp), pkt.dstPort),
            )
        }.getOrDefault(-1)
    }

    private class AppId(val packageName: String, val label: String, val isSystem: Boolean)

    /** Resolve a uid to a human label, mirroring NetworkUsageScanner.resolve's spirit. */
    private fun identify(uid: Int): AppId {
        if (uid < 0) return AppId("", "Unknown app", false)
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull()?.toList().orEmpty()
        return when {
            packages.isEmpty() -> {
                val name = runCatching { pm.getNameForUid(uid) }.getOrNull() ?: "uid:$uid"
                AppId(name, name, true)
            }
            packages.size == 1 -> {
                val p = packages.first()
                val info = runCatching { pm.getApplicationInfo(p, 0) }.getOrNull()
                val label = info?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: p
                AppId(p, label, info?.isSystemApp() ?: false)
            }
            else -> {
                // Shared uid: keep a stable, deterministic package ref for consistency.
                val ref = packages.sorted().first()
                AppId(ref, "${packages.size} apps sharing a user ID", true)
            }
        }
    }

    private fun ApplicationInfo.isSystemApp(): Boolean =
        (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system or another app")
        stopEverything()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    private fun stopEverything() {
        if (!running && tunnel == null && !foreground) return
        running = false
        DnsFlowHub.setRunning(false)
        DnsFlowHub.setAlwaysOn(false)
        upstreamWatcher?.let { watcher ->
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(watcher)
            }
        }
        upstreamWatcher = null
        runCatching { forwarders?.shutdownNow() }
        forwarders = null
        synchronized(writeLock) { runCatching { out?.close() }; out = null }
        runCatching { tunnel?.close() }
        tunnel = null
        if (foreground) {
            foreground = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
        stopSelf()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "DNS monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Active while Mink is watching which servers your apps look up."
            },
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, FlowMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Watching DNS activity")
            .setContentText("Mink is noting which servers your apps look up. Records stay on your phone.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        foreground = true
    }

    companion object {
        private const val TAG = "FlowMonitor"
        private const val CHANNEL = "dns-flow-monitor"
        private const val NOTIF_ID = 0x0D45
        const val ACTION_STOP = "com.mink.monitor.action.STOP_FLOW_MONITOR"

        /** Sentinel resolver address handed to apps; the only address routed into the tunnel. */
        const val SENTINEL_DNS = "10.111.222.1"
        private const val TUN_ADDR = "10.111.222.2"
        private const val FALLBACK_DNS = "8.8.8.8"
        private const val MTU = 32767
        private const val FORWARDER_THREADS = 4
        private const val MAX_QUEUED_QUERIES = 256
        private const val UPSTREAM_TIMEOUT_MS = 3000

        /**
         * Start the monitor. VPN consent ([VpnService.prepare]) must already be
         * granted. Uses `startForegroundService` so it also works from the boot
         * receiver's background context (the service promotes itself immediately).
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FlowMonitorService::class.java),
            )
        }

        /** Ask the running monitor to stop. */
        fun stop(context: Context) {
            context.startService(Intent(context, FlowMonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
