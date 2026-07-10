package com.mink

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mink.companion.CompanionOverlayService
import com.mink.data.MinkServices
import com.mink.ui.MinkRoot
import com.mink.ui.nav.CompanionDeepLink
import com.mink.ui.theme.MinkTheme

/**
 * The single Activity. Hosts the Compose navigation graph and refreshes
 * permission state on resume so the UI reflects grants made in Settings.
 */
class MainActivity : ComponentActivity() {

    private val services: MinkServices
        get() = (application as MinkApplication).services

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A route carried by the launch intent (e.g. a companion bubble action)
        // is relayed to the navigation host, which opens it once. Only on a
        // genuine fresh start, so a rotation does not replay the jump.
        if (savedInstanceState == null) {
            CompanionDeepLink.offer(intent?.getStringExtra(CompanionOverlayService.EXTRA_ROUTE))
        }
        setContent {
            MinkTheme {
                MinkRoot(services = services)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The bubble launches us with FLAG_ACTIVITY_SINGLE_TOP, so a running
        // instance receives the route here rather than in a fresh onCreate.
        setIntent(intent)
        CompanionDeepLink.offer(intent.getStringExtra(CompanionOverlayService.EXTRA_ROUTE))
    }

    override fun onResume() {
        super.onResume()
        services.permissions.refresh()
    }
}
