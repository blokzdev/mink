package com.mink.monitor

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-graph handle for the DNS-flow monitor, constructed in `ServiceWiring` and
 * carried on `MinkServices`. It exposes [DnsFlowHub]'s flows, drives the
 * [FlowMonitorService] lifecycle, and owns persistence: on construction it
 * restores the last saved rollup (pruned to the retention window) into the hub,
 * and thereafter debounce-saves the rollup as it changes. The screen reads
 * `services.dnsFlow` exactly like `services.networkUsage`.
 *
 * Opt-in and off by default: nothing captures until the user grants VPN consent
 * and taps enable. Requires Android 10+, where per-app attribution
 * ([android.net.ConnectivityManager.getConnectionOwnerUid]) exists; below that
 * [isSupported] is false and the screen offers no opt-in.
 */
class DnsFlowMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val store: DnsFlowStore = DnsFlowStore(appContext),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Serialises every write so a clear can never be undone by a concurrent autosave. */
    private val saveMutex = Mutex()

    init {
        // Restore first, THEN start autosaving: autosave's drop(1) then drops the
        // seeded state (not the empty default), so a just-loaded rollup is never
        // immediately re-written on boot.
        scope.launch {
            restore()
            autosave()
        }
    }

    /** Live view of observed (app, host) lookups; seeded from disk, then live. */
    val report: StateFlow<DnsFlowReport> get() = DnsFlowHub.report

    /** Whether the VPN monitor is currently active. */
    val running: StateFlow<Boolean> get() = DnsFlowHub.running

    /** Per-app DNS attribution needs API 29+; below that the feature is unavailable. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Start capture. VPN consent must already be granted by the caller. */
    fun start() = FlowMonitorService.start(appContext)

    /** Stop capture and release the VPN slot. */
    fun stop() = FlowMonitorService.stop(appContext)

    /** Forget everything observed so far, on disk and in memory. */
    fun clear() {
        DnsFlowHub.clear()
        scope.launch { persist() }   // live state is now empty, so this writes empty last
    }

    private suspend fun restore() {
        runCatching {
            val snapshot = store.load() ?: return
            val cutoff = nowMs() - RETENTION_MS
            val fresh = snapshot.entries.filter { it.lastSeenMs >= cutoff }.map { it.toLookup() }
            DnsFlowHub.seed(fresh)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun autosave() {
        // The current value here is the seeded state; drop it and persist only real
        // subsequent changes, debounced so a burst of lookups is a single write.
        DnsFlowHub.report.drop(1).debounce(SAVE_DEBOUNCE_MS).collect { persist() }
    }

    /**
     * The single writer. Reads the LIVE hub state inside [saveMutex] rather than a
     * captured snapshot, so if a clear lands between an autosave trigger and its
     * write, this persists the cleared (empty) state — a clear can never be undone.
     */
    private suspend fun persist() {
        saveMutex.withLock {
            runCatching {
                val cutoff = nowMs() - RETENTION_MS
                val entries = DnsFlowHub.report.value.lookups
                    .filter { it.lastSeenMs >= cutoff }
                    .take(MAX_PERSISTED)
                    .map { it.toEntry() }
                store.save(DnsRollupSnapshot(DNS_ROLLUP_SCHEMA_VERSION, entries))
            }
        }
    }

    private companion object {
        /** Keep observations for a week; older entries are pruned on load and save. */
        const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Most entries written to disk (the hub is already newest-first). */
        const val MAX_PERSISTED = 500

        /** Coalesce a burst of lookups into a single write. */
        const val SAVE_DEBOUNCE_MS = 3000L
    }
}
