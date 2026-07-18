package com.mink.data

import android.content.Context
import android.util.Log
import com.mink.companion.Companion
import com.mink.guardian.Guardian
import com.mink.monitor.AppAccessMonitor
import com.mink.monitor.DnsFlowMonitor
import com.mink.monitor.NetworkUsageMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Builds the concrete service graph and wires the guardian and companion to
 * the store. This is the single integration seam between the independently
 * developed subsystems; keeping it in one file means the rest of the app never
 * names a concrete controller directly.
 *
 * The guardian and companion factories are injected so the base graph builds
 * on its own and integration only has to supply two lambdas.
 */
object ServiceWiring {

    /** Supplies the guardian implementation, given its dependencies. */
    var guardianFactory: ((Context, SignalStore, CoroutineScope) -> Guardian)? = null

    /** Supplies the companion implementation, given its dependencies. */
    var companionFactory: ((Context, Guardian, CoroutineScope) -> Companion)? = null

    fun build(context: Context): MinkServices {
        val appContext = context.applicationContext
        // A background guardian must degrade, not crash. The SupervisorJob keeps one
        // subsystem's failure from cancelling its siblings; the handler is the safety
        // net for an *uncaught* throw in a launched job (e.g. a sweep computation the
        // per-step runCatching does not cover) — without it that reaches the process's
        // default uncaught handler and takes the app down. It logs to device logcat
        // only (never egress) so a real bug is still visible while the app survives.
        val appScope = CoroutineScope(
            SupervisorJob() +
                CoroutineExceptionHandler { _, throwable ->
                    Log.e(LOG_TAG, "Uncaught exception in the guardian scope; degrading", throwable)
                },
        )
        val permissions = PermissionController(appContext)
        val store = SignalStore(appContext, appScope, permissions)

        val guardian = runCatching {
            guardianFactory?.invoke(appContext, store, appScope)
        }.getOrNull()
        val companion = guardian?.let { g ->
            runCatching { companionFactory?.invoke(appContext, g, appScope) }.getOrNull()
        }

        // App access, data use, and DNS flow are independent of guardian/companion and cannot fail to construct.
        val appAccess = AppAccessMonitor(appContext, appScope)
        val networkUsage = NetworkUsageMonitor(appContext, appScope)
        val dnsFlow = DnsFlowMonitor(appContext, appScope)

        return MinkServices(
            store = store,
            permissions = permissions,
            appScope = appScope,
            guardian = guardian,
            companion = companion,
            appAccess = appAccess,
            networkUsage = networkUsage,
            dnsFlow = dnsFlow,
        )
    }

    fun tearDown(services: MinkServices) {
        services.guardian?.disable()
        services.companion?.disable()
        services.appScope.cancel()
    }

    private const val LOG_TAG = "Mink"
}
