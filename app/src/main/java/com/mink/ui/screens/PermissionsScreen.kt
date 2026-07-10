package com.mink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.core.model.PermissionKind
import com.mink.data.MinkServices
import com.mink.data.PermissionStatus
import com.mink.ui.nav.LocalPermissionRequester

/**
 * A single place to review and grant the permission gates. Each permissioned
 * category sits behind one of these; granting one unlocks its readings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    val statuses by services.permissions.statuses.collectAsStateWithLifecycle()
    val request = LocalPermissionRequester.current

    // Only the kinds actually used by a category are worth showing here.
    val kinds = remember(services) {
        com.mink.core.model.SignalCategory.entries.mapNotNull { it.permission }.distinct()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Android prompts you the first time an app asks for one of these. Granting a " +
                        "permission here lets Mink read that surface and show you what it exposes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            items(kinds, key = { it.name }) { kind ->
                val granted = services.permissions.isGranted(kind) ||
                    statuses[kind] == PermissionStatus.GRANTED
                PermissionCard(kind = kind, granted = granted, onGrant = { request(kind) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun PermissionCard(
    kind: PermissionKind,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    kind.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (granted) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Granted",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                kind.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onGrant) { Text("Grant ${kind.title}") }
            }
        }
    }
}
