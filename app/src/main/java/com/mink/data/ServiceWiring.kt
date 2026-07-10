package com.mink.data

import android.content.Context
import com.mink.companion.Companion
import com.mink.guardian.Guardian
import com.mink.monitor.AppAccessMonitor
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
        val appScope = CoroutineScope(SupervisorJob())
        val permissions = PermissionController(appContext)
        val store = SignalStore(appContext, appScope, permissions)

        val guardian = runCatching {
            guardianFactory?.invoke(appContext, store, appScope)
        }.getOrNull()
        val companion = guardian?.let { g ->
            runCatching { companionFactory?.invoke(appContext, g, appScope) }.getOrNull()
        }

        // App access is independent of guardian/companion and cannot fail to construct.
        val appAccess = AppAccessMonitor(appContext, appScope)

        return MinkServices(
            store = store,
            permissions = permissions,
            appScope = appScope,
            guardian = guardian,
            companion = companion,
            appAccess = appAccess,
        )
    }

    fun tearDown(services: MinkServices) {
        services.guardian?.disable()
        services.companion?.disable()
        services.appScope.cancel()
    }
}
