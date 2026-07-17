package com.mink.monitor

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

/**
 * App-graph handle for the DNS-flow monitor, constructed in `ServiceWiring` and
 * carried on `MinkServices`. It owns no state of its own — it exposes
 * [DnsFlowHub]'s flows and drives the [FlowMonitorService] lifecycle — so the
 * screen reads `services.dnsFlow` exactly like `services.networkUsage`.
 *
 * The monitor is opt-in and off by default: nothing starts until the user grants
 * VPN consent and taps enable on the screen. It requires Android 10+, where
 * per-app attribution ([android.net.ConnectivityManager.getConnectionOwnerUid])
 * is available; on older devices [isSupported] is false and the screen offers no
 * opt-in.
 */
class DnsFlowMonitor(private val appContext: Context) {

    /** Live view of observed (app, host) lookups; empty until the monitor runs. */
    val report: StateFlow<DnsFlowReport> get() = DnsFlowHub.report

    /** Whether the VPN monitor is currently active. */
    val running: StateFlow<Boolean> get() = DnsFlowHub.running

    /** Per-app DNS attribution needs API 29+; below that the feature is unavailable. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Start capture. VPN consent must already be granted by the caller. */
    fun start() = FlowMonitorService.start(appContext)

    /** Stop capture and release the VPN slot. */
    fun stop() = FlowMonitorService.stop(appContext)

    /** Forget everything observed so far. */
    fun clear() = DnsFlowHub.clear()
}
