package com.mink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices
import com.mink.monitor.AppRecord
import com.mink.monitor.CapabilityHolders

/**
 * A read-only, on-device map of which installed apps currently hold each
 * sensitive runtime capability (granted permissions), grouped capability →
 * apps. Refreshes on first display via [MinkServices.appAccess]; nothing is
 * persisted or logged, and the app list never leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppAccessScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    val monitor = services.appAccess

    LaunchedEffect(Unit) { monitor.refresh() }

    val report by monitor.report.collectAsStateWithLifecycle()
    val scanning by monitor.scanning.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App access") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroBlock() }

            val current = report
            when {
                current == null -> item { ScanningBlock() }
                current.byCapability.isEmpty() -> item { NothingToShow() }
                else -> items(current.byCapability, key = { it.capability.name }) { holders ->
                    CapabilityCard(holders)
                }
            }

            if (scanning && report != null) {
                item { RescanLine() }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IntroBlock() {
    Column {
        Text(
            "Who can reach your phone",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "These are the apps that can reach sensitive parts of your phone right now — " +
                "your location, camera, microphone and more. Read from the system on your " +
                "device; nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun CapabilityCard(holders: CapabilityHolders) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                holders.capability.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                holderSummary(holders),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(10.dp))
            holders.apps.forEach { app ->
                AppLine(app)
            }
        }
    }
}

/** "3 you installed · 5 system", or one side alone when the other is empty. */
private fun holderSummary(holders: CapabilityHolders): String {
    val user = holders.userAppCount
    val system = holders.systemAppCount
    val parts = buildList {
        if (user > 0) add("$user you installed")
        if (system > 0) add("$system system")
    }
    return parts.joinToString(" · ")
}

@Composable
private fun AppLine(app: AppRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            app.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (app.isSystem) {
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
            ) {
                Text(
                    "system",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanningBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text("Reading installed apps...", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RescanLine() {
    Text(
        "Refreshing...",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun NothingToShow() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Nothing to show", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "No app currently holds a sensitive permission, or the list could not be read.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
