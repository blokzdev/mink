package com.mink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mink.data.MinkServices
import com.mink.ui.MinkRoot
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
        setContent {
            MinkTheme {
                MinkRoot(services = services)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        services.permissions.refresh()
    }
}
