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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import com.mink.guardian.APP_ACCESS_CATEGORY
import com.mink.guardian.Observation
import com.mink.monitor.CapabilityHolders

/**
 * Turns "just now" style deltas into a compact label — minutes under an hour,
 * hours under a day, days beyond that, and "just now" under a minute (or for a
 * zero/future timestamp). Kept at file top-level and [internal] so a unit test
 * can reach it directly. Pure.
 */
internal fun relativeTime(epochMs: Long, nowMs: Long): String {
    val delta = nowMs - epochMs
    if (delta < 60_000L) return "just now"
    val minutes = delta / 60_000L
    if (minutes < 60L) return "${minutes}m ago"
    val hours = delta / 3_600_000L
    if (hours < 24L) return "${hours}h ago"
    val days = delta / 86_400_000L
    return "${days}d ago"
}

/**
 * The Watched apps screen: a distinct home for the guardian's app-access change
 * timeline — every time an app gains or loses access to something sensitive —
 * plus a compact "who can reach your phone now" summary and a way into the full
 * App Access screen. Reads [MinkServices.guardian] observations filtered to the
 * app-access category and [MinkServices.appAccess] for current holdings; nothing
 * is persisted here and nothing leaves the phone. Degrades to a calm
 * "unavailable" state when the guardian is null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchedAppsScreen(
    services: MinkServices,
    onBack: () -> Unit,
    onOpenAppAccess: () -> Unit,
) {
    val guardian = services.guardian

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watched apps") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (guardian == null) {
            GuardianUnavailableBlock(Modifier.padding(padding))
            return@Scaffold
        }

        val monitor = services.appAccess
        LaunchedEffect(Unit) { monitor.refresh() }

        val observations by guardian.observations.collectAsStateWithLifecycle()
        val report by monitor.report.collectAsStateWithLifecycle()

        val changes = observations
            .filter { it.categoryId == APP_ACCESS_CATEGORY }
            .sortedByDescending { it.epochMs }
        val now = System.currentTimeMillis()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroLine() }

            item { SectionHeading("Recent changes") }
            if (changes.isEmpty()) {
                item { NoChangesCard() }
            } else {
                items(changes, key = { it.id }) { change -> ChangeRow(change, now) }
            }

            item { SectionHeading("Who can reach your phone now") }
            val current = report
            if (current == null) {
                item { ReadingLine() }
            } else {
                items(current.byCapability.take(5), key = { it.capability.name }) { holders ->
                    CapabilityLine(holders)
                }
                item {
                    FilledTonalButton(onClick = onOpenAppAccess, modifier = Modifier.fillMaxWidth()) {
                        Text("See all app access")
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun IntroLine() {
    Column {
        Text(
            "Watched apps",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Mink watches which apps can reach sensitive parts of your phone — location, " +
                "camera, microphone and more — and tells you when that changes. It all happens " +
                "on your device; nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ChangeRow(obs: Observation, nowMs: Long) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                relativeTime(obs.epochMs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(obs.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun NoChangesCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "No access changes yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Mink will note it here when an app gains or loses access to something sensitive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun CapabilityLine(holders: CapabilityHolders) {
    val count = holders.apps.size
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                holders.capability.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (count == 1) "1 app" else "$count apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun ReadingLine() {
    Text(
        "Reading...",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun GuardianUnavailableBlock(modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "The guardian is not available",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Watched apps needs the guardian, and this build could not start it on your " +
                "device. You can still open App access to see who can reach your phone. " +
                "Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}
