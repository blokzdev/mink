package com.mink.ui.screens

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices
import com.mink.monitor.DnsLookup
import com.mink.monitor.TrackerList

/**
 * The Network activity screen: which servers each app looks up, attributed on
 * device. It is strictly opt-in — the monitor is a local VPN, so nothing runs
 * until the user reads what it does and grants VPN consent. While active it
 * holds the single VPN slot (so it replaces any other VPN) and shows an ongoing
 * notification. Names only: Mink notes which hosts an app resolves, forwards
 * every query unchanged to the real resolver, and never inspects traffic.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsFlowScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network activity") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val monitor = services.dnsFlow
        val context = LocalContext.current
        val report by monitor.report.collectAsStateWithLifecycle()
        val running by monitor.running.collectAsStateWithLifecycle()
        val trackers = remember { TrackerList.load(context) }

        val consentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) monitor.start()
        }
        val enable: () -> Unit = {
            val prepare = VpnService.prepare(context)
            if (prepare == null) monitor.start() else consentLauncher.launch(prepare)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { IntroLine() }

            if (!monitor.isSupported) {
                item { UnsupportedCard() }
            } else {
                item {
                    if (running) {
                        RunningCard(onStop = { monitor.stop() }, onClear = { monitor.clear() })
                    } else {
                        OptInCard(onEnable = enable, privateDnsActive = privateDnsActive(context))
                    }
                }
                val lookups = report.lookups
                when {
                    lookups.isNotEmpty() -> {
                        // Persisted history stays visible even when the monitor is off.
                        if (!running) item { HistoryNote() }
                        items(lookups, key = { "${it.uid}:${it.host}" }) {
                            LookupRow(it, isTracker = trackers.isTracker(it.host))
                        }
                    }
                    running -> item { WaitingLine() }
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
            "Network activity",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Mink can show which servers each app looks up — a revealing signal, since the " +
                "names an app resolves hint at who it talks to. It reads the names only and keeps " +
                "its records on your phone; your lookups are still forwarded to your resolver, " +
                "just as they are now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun OptInCard(onEnable: () -> Unit, privateDnsActive: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Turn on network activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This uses a local VPN on your device to see which servers your apps look up. " +
                    "A few things to know first:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(8.dp))
            Bullet("It replaces any other VPN you use — Android allows only one at a time.")
            Bullet("A key icon stays in your status bar while it runs.")
            Bullet("Mink routes only your DNS lookups, not your other traffic, and forwards each to your resolver.")
            Bullet("Mink keeps its records on your phone and uploads nothing.")
            if (privateDnsActive) {
                Bullet(
                    "You have Private DNS on. While this runs, your lookups are forwarded in plain " +
                        "form instead — turn it off again when you are done.",
                )
            }
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(onClick = onEnable) {
                Text("Turn on")
            }
        }
    }
}

@Composable
private fun RunningCard(onStop: () -> Unit, onClear: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Watching now",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Mink is noting which servers your apps look up. Stop any time to release the VPN.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
                TextButton(onClick = onClear) { Text("Clear list") }
            }
        }
    }
}

@Composable
private fun LookupRow(lookup: DnsLookup, isTracker: Boolean) {
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
                    lookup.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (lookup.count > 1) {
                    Text(
                        "×${lookup.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    lookup.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isTracker) {
                    Spacer(Modifier.width(8.dp))
                    TrackerTag()
                }
            }
        }
    }
}

@Composable
private fun TrackerTag() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            "tracker",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun UnsupportedCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Needs Android 10 or newer",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Attributing a lookup to the app that made it relies on a capability added in " +
                    "Android 10, so this feature is unavailable on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            "•  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/** Whether the device is currently using Private DNS (DoT), which our forwarder would bypass. */
private fun privateDnsActive(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    return runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getLinkProperties(cm.activeNetwork)?.isPrivateDnsActive == true
    }.getOrDefault(false)
}

@Composable
private fun WaitingLine() {
    Text(
        "Listening... open an app and its lookups will appear here.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun HistoryNote() {
    Text(
        "From your recent activity. Turn on to keep watching.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
