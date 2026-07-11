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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mink.core.model.SignalCategory
import com.mink.data.MinkServices
import com.mink.guardian.AlertLevel
import com.mink.guardian.AlertSource
import com.mink.guardian.Alertness
import com.mink.guardian.BaselineSummary
import com.mink.guardian.DriftingSignal
import com.mink.guardian.GuardianAlert
import com.mink.guardian.MIN_SWEEPS_FOR_LEARNING
import com.mink.guardian.ModelStatus
import com.mink.guardian.Observation
import com.mink.guardian.learningDurationPhrase

/**
 * The guardian dashboard: current tier and model status, an opt-in download,
 * an enable toggle, alertness configuration, the observation timeline, open
 * alerts (with static exposure insights in their own section), and a way into
 * the chat. Degrades to a clear "unavailable" state when
 * [MinkServices.guardian] is null (for example when the native model bridge
 * could not load).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianScreen(
    services: MinkServices,
    onBack: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenWatchedApps: () -> Unit,
) {
    val guardian = services.guardian

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Guardian") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (guardian != null) {
                        IconButton(onClick = onOpenChat) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Talk to Mink")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (guardian == null) {
            GuardianUnavailable(Modifier.padding(padding))
            return@Scaffold
        }

        val state by guardian.state.collectAsStateWithLifecycle()
        val alerts by guardian.alerts.collectAsStateWithLifecycle()
        val observations by guardian.observations.collectAsStateWithLifecycle()
        val baseline by guardian.baseline.collectAsStateWithLifecycle()

        // Static exposure explainers from the rules engine are education, not
        // events; they get their own section below the learning card.
        val (insights, events) = alerts.partition { it.id.startsWith("rule.") }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    state.tier.displayName,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (state.enabled) "Watching quietly" else "Turned off",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                )
                            }
                            Switch(
                                checked = state.enabled,
                                onCheckedChange = { on ->
                                    if (on) guardian.enable() else guardian.disable()
                                },
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        ModelStatusBlock(
                            status = state.model.status,
                            progress = state.model.downloadProgress,
                            quant = state.model.quantName,
                            message = state.model.message,
                            onDownload = { guardian.prepareModel() },
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { guardian.sweepNow() }, modifier = Modifier.weight(1f)) {
                        Text("Sweep now")
                    }
                    Button(onClick = onOpenChat, modifier = Modifier.weight(1f)) {
                        Text("Talk to Mink")
                    }
                }
            }

            item {
                FilledTonalButton(onClick = onOpenWatchedApps, modifier = Modifier.fillMaxWidth()) {
                    Text("Watched apps")
                }
            }

            item {
                AlertnessCard(
                    alertness = state.alertness,
                    mutedSources = state.mutedSources,
                    onAlertnessChange = { guardian.setAlertness(it) },
                    onSourceMutedChange = { source, muted -> guardian.setSourceMuted(source, muted) },
                )
            }

            item {
                SectionLabel("Alerts", if (events.isEmpty()) "Nothing needs your attention" else null)
            }
            if (events.isEmpty()) {
                item { EmptyLine("No alerts. Mink will speak up if something changes.") }
            } else {
                items(events, key = { it.id }) { alert ->
                    AlertCard(alert, onAcknowledge = { guardian.acknowledgeAlert(alert.id) })
                }
            }

            baseline?.let { summary ->
                item { LearningSection(summary) }
            }

            if (insights.isNotEmpty()) {
                item {
                    SectionLabel(
                        "What your phone exposes",
                        "Always true of this phone — worth reading once, not worth interrupting you.",
                    )
                }
                items(insights, key = { it.id }) { alert ->
                    AlertCard(alert, onAcknowledge = { guardian.acknowledgeAlert(alert.id) })
                }
            }

            item { SectionLabel("Observations", null) }
            if (observations.isEmpty()) {
                item { EmptyLine("The timeline fills in as Mink runs its sweeps.") }
            } else {
                items(observations, key = { it.id }) { obs -> ObservationRow(obs) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ModelStatusBlock(
    status: ModelStatus,
    progress: Float,
    quant: String,
    message: String?,
    onDownload: () -> Unit,
) {
    Column {
        val label = when (status) {
            ModelStatus.ABSENT -> "Model not downloaded"
            ModelStatus.DOWNLOADING -> "Downloading model"
            ModelStatus.VERIFYING -> "Verifying model"
            ModelStatus.READY -> "Model ready"
            ModelStatus.LOADING -> "Loading model"
            ModelStatus.LOADED -> "Model loaded"
            ModelStatus.FAILED -> "Model failed to load"
            ModelStatus.UNSUPPORTED -> "This device runs the rules guardian"
        }
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (quant.isNotBlank()) {
            Text(
                quant,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (status == ModelStatus.DOWNLOADING || status == ModelStatus.VERIFYING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (status == ModelStatus.ABSENT || status == ModelStatus.FAILED) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = onDownload) { Text("Download model") }
            Text(
                "The model downloads only when you ask, and never over cellular by surprise.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun AlertCard(alert: GuardianAlert, onAcknowledge: () -> Unit) {
    val tint = when (alert.level) {
        AlertLevel.CRITICAL -> MaterialTheme.colorScheme.error
        AlertLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        AlertLevel.SUGGESTION -> MaterialTheme.colorScheme.secondary
        AlertLevel.INFO -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(alert.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = tint)
            Spacer(Modifier.height(6.dp))
            Text(
                alert.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            )
            if (!alert.acknowledged) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onAcknowledge) { Text("Got it") }
            }
        }
    }
}

@Composable
private fun AlertnessCard(
    alertness: Alertness,
    mutedSources: Set<AlertSource>,
    onAlertnessChange: (Alertness) -> Unit,
    onSourceMutedChange: (AlertSource, Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Alertness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = Alertness.entries
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = alertness == option,
                        onClick = { onAlertnessChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(
                            when (option) {
                                Alertness.QUIET -> "Quiet"
                                Alertness.STANDARD -> "Standard"
                                Alertness.PARANOID -> "Paranoid"
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (alertness) {
                    Alertness.QUIET -> "Only critical findings interrupt you."
                    Alertness.STANDARD -> "Warnings and critical findings interrupt you."
                    Alertness.PARANOID -> "Suggestions, warnings, and critical findings interrupt you."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(14.dp))
            Text("Notify me about", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            AlertSource.entries.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        source.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = source !in mutedSources,
                        onCheckedChange = { checked -> onSourceMutedChange(source, !checked) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Muting only silences notifications — everything still lands in the timeline. " +
                    "Mink's deepest protections always notify, on every setting.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun ObservationRow(obs: Observation) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                obs.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(obs.summary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LearningSection(summary: BaselineSummary) {
    if (!summary.isMature) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                "Mink is still learning this device — ${summary.sweepCount}/$MIN_SWEEPS_FOR_LEARNING sweeps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(14.dp),
            )
        }
        return
    }

    val duration = learningDurationPhrase(summary.learningSinceMs, System.currentTimeMillis())

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "What Mink has learned",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "watching ${summary.trackedSignals} signals $duration · ${summary.sweepCount} sweeps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LearningStat("Stable anchors", summary.stableAnchors, Modifier.weight(1f))
                LearningStat("Naturally changing", summary.expectedVolatile, Modifier.weight(1f))
                LearningStat("Drifting", summary.driftingSignals.size, Modifier.weight(1f))
            }
            summary.driftingSignals.take(3).forEach { signal ->
                DriftingRow(signal)
            }
        }
    }
}

@Composable
private fun LearningStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun DriftingRow(signal: DriftingSignal) {
    Column(modifier = Modifier.padding(top = 10.dp)) {
        Text(
            "${signal.name} — changed ${signal.recentChanges}× this week",
            style = MaterialTheme.typography.bodyMedium,
        )
        SignalCategory.fromId(signal.categoryId)?.title?.let { title ->
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String, hint: String?) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun GuardianUnavailable(modifier: Modifier) {
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
            "This build could not start the guardian on your device. You can still explore " +
                "every reading and export a report. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}
