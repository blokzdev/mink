package com.mink.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices

/**
 * A single place to tune how Mink behaves: the alertness dial and per-source
 * mutes (the same control as on the guardian dashboard, shared so both stay in
 * step), a note on keeping the opt-in Network activity monitor on across
 * reboots, and quick links to permissions, export, and about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    services: MinkServices,
    onBack: () -> Unit,
    onOpenNetworkActivity: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val guardian = services.guardian
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (guardian != null) {
                item {
                    val state by guardian.state.collectAsStateWithLifecycle()
                    AlertnessCard(
                        alertness = state.alertness,
                        mutedSources = state.mutedSources,
                        onAlertnessChange = { guardian.setAlertness(it) },
                        onSourceMutedChange = { source, muted -> guardian.setSourceMuted(source, muted) },
                    )
                }
            }

            item {
                NetworkActivitySection(
                    supported = services.dnsFlow.isSupported,
                    onOpen = onOpenNetworkActivity,
                )
            }

            item {
                LinkRow("Permissions", "What Mink can and cannot read", onOpenPermissions)
            }
            item {
                LinkRow("Export", "Save a copy of what your phone reveals", onOpenExport)
            }
            item {
                LinkRow("About", "What Mink is, and how it works", onOpenAbout)
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun NetworkActivitySection(supported: Boolean, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Network activity monitor",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (supported) {
                    // Honest about the two ways the monitor can end without the user
                    // stopping it, and about what always-on takes away in exchange.
                    "The opt-in monitor runs until you stop it or another app takes the " +
                        "VPN slot, and Mink tries to resume it after a restart. For a resume " +
                        "across every reboot, set Mink as an always-on VPN in your system " +
                        "settings (Network and internet, VPN) — note that Android then " +
                        "restarts the monitor even after you tap Stop, so you would turn it " +
                        "off from that same screen."
                } else {
                    "Per-app network activity needs Android 10 or newer, so the monitor is " +
                        "unavailable on this device."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Open Network activity",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}
