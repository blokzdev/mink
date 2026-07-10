package com.mink

import android.app.Application
import com.mink.data.MinkServices
import com.mink.data.ServiceWiring

/**
 * Application entry point. Builds the process-wide service graph once and holds
 * it for the lifetime of the process. Activities read [services].
 */
class MinkApplication : Application() {

    lateinit var services: MinkServices
        private set

    override fun onCreate() {
        super.onCreate()
        // Register the subsystem factories, then build the graph. Keeping the
        // registration here (rather than inside ServiceWiring) is what lets the
        // signals layer stay independent of guardian/companion internals.
        ServiceWiring.guardianFactory = { ctx, store, scope ->
            com.mink.guardian.GuardianController(ctx, store, scope)
        }
        ServiceWiring.companionFactory = { ctx, guardian, scope ->
            com.mink.companion.CompanionController(ctx, guardian, scope)
        }
        services = ServiceWiring.build(this)
    }
}
