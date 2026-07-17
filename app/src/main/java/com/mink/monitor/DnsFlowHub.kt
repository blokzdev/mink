package com.mink.monitor

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-global bridge between [FlowMonitorService] (created by the system, so
 * it cannot be handed dependencies) and the app graph that reads its results.
 * Mirrors the `GuardianServiceHost` pattern: the service writes, the UI reads.
 *
 * State is in-memory only in this first cut — nothing is persisted yet. Lookups
 * are capped so a long session cannot grow without bound; the oldest entries are
 * evicted first.
 */
object DnsFlowHub {

    /** Most a single session keeps in memory before evicting the oldest. */
    private const val MAX_LOOKUPS = 500

    private val lookups = ConcurrentHashMap<Key, DnsLookup>()

    private val _report = MutableStateFlow(DnsFlowReport(emptyList(), 0L))
    val report: StateFlow<DnsFlowReport> = _report.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _alwaysOn = MutableStateFlow(false)

    /**
     * Whether the running session is under the system's always-on VPN setting —
     * in that mode Android restarts the monitor after any stop, so the UI must
     * say the off switch lives in system settings, not over-promise its own.
     */
    val alwaysOn: StateFlow<Boolean> = _alwaysOn.asStateFlow()

    private data class Key(val uid: Int, val host: String)

    /** Called by the service on each observed DNS query. Upserts and republishes. */
    fun record(
        uid: Int,
        packageName: String,
        label: String,
        isSystem: Boolean,
        host: String,
        nowMs: Long,
    ) {
        if (host.isEmpty()) return
        val key = Key(uid, host)
        // Atomic upsert: several forwarder threads can record the same (uid, host)
        // at once (an app's parallel A/AAAA lookups), so the increment must happen
        // inside the map or counts are lost.
        lookups.compute(key) { _, prior ->
            if (prior == null) {
                DnsLookup(uid, packageName, label, isSystem, host, nowMs, nowMs, 1)
            } else {
                prior.copy(lastSeenMs = nowMs, count = prior.count + 1)
            }
        }
        evictIfNeeded()
        publish(nowMs)
    }

    /**
     * Restore persisted lookups at startup. Existing in-memory entries win (a live
     * session is fresher than the last save), so this only fills gaps. Publishes
     * once so the screen shows history immediately.
     */
    fun seed(restored: List<DnsLookup>) {
        if (restored.isEmpty()) return
        var latest = _report.value.generatedAtMs
        for (e in restored) {
            lookups.putIfAbsent(Key(e.uid, e.host), e)
            if (e.lastSeenMs > latest) latest = e.lastSeenMs
        }
        evictIfNeeded()
        publish(latest)
    }

    /** The service reports its own lifecycle so the UI and the VPN self-check follow it. */
    fun setRunning(running: Boolean) {
        _running.value = running
    }

    /** The service reports whether the session runs under system always-on VPN. */
    fun setAlwaysOn(alwaysOn: Boolean) {
        _alwaysOn.value = alwaysOn
    }

    /** Forget everything observed (user-invoked from the screen). */
    fun clear() {
        lookups.clear()
        publish(_report.value.generatedAtMs)
    }

    private fun evictIfNeeded() {
        if (lookups.size <= MAX_LOOKUPS) return
        // Drop the least-recently-seen entries down to the cap.
        val overflow = lookups.size - MAX_LOOKUPS
        lookups.entries
            .sortedBy { it.value.lastSeenMs }
            .take(overflow)
            .forEach { lookups.remove(it.key) }
    }

    private fun publish(nowMs: Long) {
        val sorted = lookups.values.sortedWith(
            compareByDescending<DnsLookup> { it.lastSeenMs }.thenBy { it.host },
        )
        _report.value = DnsFlowReport(sorted, nowMs)
    }
}
