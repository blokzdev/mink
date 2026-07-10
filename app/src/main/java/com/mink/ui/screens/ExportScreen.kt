package com.mink.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.data.MinkServices
import com.mink.ui.export.ReportBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds a JSON and text report of every collected signal, writes it into the
 * app's cache under exports/, and shares it through the FileProvider. Exporting
 * is the only way any data leaves the phone, and it happens only when the user
 * taps share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { services.store.collectAll() }
    val snapshot by services.store.signals.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    val populated = snapshot.count { it.value.isNotEmpty() }
    val signalCount = snapshot.values.sumOf { it.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export report") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Take your report with you",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Mink can write everything it read into a JSON and a text file, then hand " +
                            "them to an app you choose. This is the only time your data leaves the " +
                            "phone, and only because you asked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "$populated categories, $signalCount readings ready to export.",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true
                    scope.launch {
                        val files = withContext(Dispatchers.IO) {
                            writeReportFiles(context, snapshot)
                        }
                        shareReportFiles(context, files)
                        busy = false
                    }
                },
                enabled = !busy && signalCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Build and share report")
                }
            }

            if (signalCount == 0) {
                Text(
                    "Nothing has been read yet. Open a few categories first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

private fun writeReportFiles(
    context: Context,
    snapshot: Map<com.mink.core.model.SignalCategory, List<com.mink.core.model.FingerprintSignal>>,
): List<File> {
    val now = System.currentTimeMillis()
    val report = ReportBuilder.buildReport(snapshot, now)
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val jsonFile = File(dir, "mink-report-$now.json")
    val textFile = File(dir, "mink-report-$now.txt")
    runCatching { jsonFile.writeText(ReportBuilder.toJson(report)) }
    runCatching { textFile.writeText(ReportBuilder.toText(report)) }
    return listOf(jsonFile, textFile).filter { it.exists() && it.length() > 0 }
}

private fun shareReportFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList(
        files.mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
        },
    )
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Share Mink report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
