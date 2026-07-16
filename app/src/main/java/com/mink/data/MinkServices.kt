package com.mink.data

import com.mink.companion.Companion
import com.mink.guardian.Guardian
import com.mink.monitor.AppAccessMonitor
import com.mink.monitor.NetworkUsageMonitor
import kotlinx.coroutines.CoroutineScope

/**
 * Process-wide service graph. Held by [com.mink.MinkApplication] and passed
 * down to the UI. Guardian and companion are nullable so the app degrades
 * gracefully if those subsystems are unavailable (e.g. a device where the
 * native model bridge failed to load, or overlay support is missing).
 *
 * [appAccess] and [networkUsage] are non-null: they need no model and cannot
 * fail to construct.
 */
class MinkServices(
    val store: SignalStore,
    val permissions: PermissionController,
    val appScope: CoroutineScope,
    val guardian: Guardian?,
    val companion: Companion?,
    val appAccess: AppAccessMonitor,
    val networkUsage: NetworkUsageMonitor,
)
