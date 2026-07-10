package com.mink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mink.data.MinkServices

/**
 * Root composable and navigation host for the whole app.
 *
 * NOTE: This is a scaffold stub. The UI workstream replaces this file with the
 * full navigation graph (home, category detail, summary, guardian chat,
 * onboarding, settings, about, export). The signature is the integration
 * contract and must be preserved: `MinkRoot(services: MinkServices)`.
 */
@Composable
fun MinkRoot(services: MinkServices) {
    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text("Mink")
        }
    }
}
