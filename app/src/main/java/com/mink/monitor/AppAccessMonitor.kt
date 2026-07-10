package com.mink.monitor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the latest [AppAccessReport] as observable state and refreshes it on
 * demand. Read-only and on-device: the app list never leaves the phone.
 */
class AppAccessMonitor(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val scanner: AppAccessScanner = AppAccessScanner(appContext),
) {
    private val _report = MutableStateFlow<AppAccessReport?>(null)

    /** The latest report, or null until the first scan completes. */
    val report: StateFlow<AppAccessReport?> = _report.asStateFlow()

    private val _scanning = MutableStateFlow(false)

    /** True while a scan is in flight. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Launch a scan on [scope]; ignored if one is already running. */
    fun refresh() {
        if (_scanning.value) return
        _scanning.value = true
        scope.launch {
            try {
                _report.value = scanner.scan()
            } finally {
                _scanning.value = false
            }
        }
    }
}
