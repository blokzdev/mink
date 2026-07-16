package com.mink.monitor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the latest [DataUsageReport] as observable state and refreshes it on
 * demand. Read-only and on-device: the per-app volumes never leave the phone,
 * and they are volumes only — never where any data went.
 */
class NetworkUsageMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val scanner: NetworkUsageScanner = NetworkUsageScanner(appContext),
) {
    private val _report = MutableStateFlow<DataUsageReport?>(null)

    /** The latest report, or null until the first scan completes. */
    val report: StateFlow<DataUsageReport?> = _report.asStateFlow()

    private val _scanning = MutableStateFlow(false)

    /** True while a scan is in flight. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Scan the last [windowDays] on [scope] and publish; ignored if one is already running. */
    fun refresh(windowDays: Int = 7) {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                _report.value = scanner.scan(now - windowDays * DAY_MS, now)
            } finally {
                _scanning.value = false
            }
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
