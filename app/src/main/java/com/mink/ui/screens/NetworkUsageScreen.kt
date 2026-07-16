package com.mink.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices
import com.mink.monitor.AppDataUsage
import com.mink.monitor.NetworkUsageScanner
import com.mink.monitor.formatBytes

/**
 * The Data use screen: a distinct home for per-app data volumes — how much each
 * app sent and received over the last seven days, split into cellular, Wi-Fi,
 * roaming and background. Reads [MinkServices.networkUsage]; the scan runs on
 * device and nothing leaves the phone. Volumes only: Mink can see how much an app
 * moved, never where it went — Android does not reveal destinations to an app
 * like this. Gated on usage access, which the same card offers to grant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkUsageScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data use") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val monitor = services.networkUsage
        LaunchedEffect(Unit) { monitor.refresh() }

        val report by monitor.report.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // Re-check usage access on every return to the foreground, so granting
        // it in Settings makes the card below disappear when the user comes back.
        var hasUsageAccess by remember { mutableStateOf(NetworkUsageScanner.hasUsageAccess(context)) }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasUsageAccess = NetworkUsageScanner.hasUsageAccess(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroLine() }

            if (!hasUsageAccess) {
                item { UsageAccessCard(onAllow = { openUsageAccessSettings(context) }) }
            } else {
                val current = report
                when {
                    current == null -> item { ReadingLine() }
                    current.apps.isEmpty() -> item { NoDataUseCard() }
                    else -> items(current.apps.take(20), key = { it.uid }) { app -> DataUsageRow(app) }
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
            "Data use",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Mink shows how much data each app used over the last seven days, split into " +
                "cellular, Wi-Fi, roaming and background. It all happens on your device; nothing " +
                "leaves the phone. Mink can see the amount an app moved, never where the data went " +
                "— Android does not reveal that to an app like this.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun DataUsageRow(app: AppDataUsage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatBytes(app.totalBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "cellular ${formatBytes(app.mobileBytes)} · Wi-Fi ${formatBytes(app.wifiBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            val extras = buildList {
                if (app.roamingBytes > 0L) add("roaming ${formatBytes(app.roamingBytes)}")
                if (app.backgroundMobileBytes > 0L) {
                    add("background cellular ${formatBytes(app.backgroundMobileBytes)}")
                }
            }
            if (extras.isNotEmpty()) {
                Text(
                    extras.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

@Composable
private fun NoDataUseCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "No data use recorded yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "When apps send or receive data, Mink will show how much each one used here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun UsageAccessCard(onAllow: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "See data use by app",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Android keeps per-app data counts behind usage access. Allow it and Mink can " +
                    "show how much each app used over cellular and Wi-Fi, how much was while " +
                    "roaming, and how much ran in the background — the amount only, never where " +
                    "the data went.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onAllow) {
                Text("Allow usage access")
            }
        }
    }
}

private fun openUsageAccessSettings(context: Context) {
    runCatching {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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
