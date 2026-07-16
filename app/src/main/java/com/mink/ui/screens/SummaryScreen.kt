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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mink.data.MinkServices
import com.mink.guardian.Guardian
import com.mink.guardian.GuardianTier
import com.mink.guardian.ModelStatus
import com.mink.narrative.DeviceStoryContext
import com.mink.narrative.FingerprintNarrative
import com.mink.narrative.FingerprintReport
import com.mink.narrative.IdentifyingSignal
import com.mink.narrative.NarrativeCard
import com.mink.narrative.StoryCard
import com.mink.narrative.StoryNarrative
import com.mink.narrative.SummaryNarration
import com.mink.ui.components.MinkIcons
import com.mink.ui.vm.SimpleFactory
import com.mink.ui.vm.SummaryViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The fingerprint narrative: a headline uniqueness read, a supporting paragraph,
 * a set of plain-English cards, and the handful of readings that identify this
 * phone the most, built by [FingerprintNarrative] from the current snapshot.
 * Between the headline and those cards it also shows the derived "story" cards
 * [StoryNarrative] infers from the same snapshot and the app-access report.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    services: MinkServices,
    onBack: () -> Unit,
) {
    val vm: SummaryViewModel = viewModel(factory = SimpleFactory { SummaryViewModel(services.store) })
    LaunchedEffect(Unit) {
        vm.sweep()
        services.appAccess.refresh()
    }

    val snapshot by vm.signals.collectAsStateWithLifecycle()
    val narrative = remember(snapshot) { FingerprintNarrative.build(snapshot) }

    val report by services.appAccess.report.collectAsStateWithLifecycle()
    val story = remember(snapshot, report) {
        val appReport = report
        val now = System.currentTimeMillis()
        val context = DeviceStoryContext(
            nowMs = now,
            earliestUserInstallMs = appReport?.apps
                ?.filter { !it.isSystem && it.firstInstallMs in StoryNarrative.MIN_PLAUSIBLE_INSTALL_MS..now }
                ?.minOfOrNull { it.firstInstallMs },
            userPackageNames = appReport?.apps?.filter { !it.isSystem }?.map { it.packageName } ?: emptyList(),
        )
        StoryNarrative.build(snapshot, context)
    }

    // Watch the guardian's model status so "Mink's read" can offer a live read,
    // nudge the download, or stay hidden on a rules-only device.
    val guardian = services.guardian
    val guardianState = if (guardian != null) {
        guardian.state.collectAsStateWithLifecycle().value
    } else {
        null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fingerprint summary") },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    Text(
                        narrative.headline,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        narrative.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(14.dp))
                    UniquenessMeter(narrative.uniquenessScore)
                }
            }

            if (guardian != null &&
                guardianState != null &&
                guardianState.tier != GuardianTier.RULES_ONLY
            ) {
                item {
                    MinkReadSection(
                        guardian = guardian,
                        status = guardianState.model.status,
                        message = guardianState.model.message,
                        report = narrative,
                        story = story,
                        appScope = services.appScope,
                    )
                }
            }

            if (story.isNotEmpty()) {
                item {
                    Text(
                        "The story your phone tells",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(story, key = { it.id }) { card -> StoryCardView(card) }
            }

            if (narrative.cards.isNotEmpty()) {
                item {
                    Text(
                        "What it adds up to",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(narrative.cards, key = { it.id }) { card -> NarrativeCardView(card) }
            }

            if (narrative.topSignals.isNotEmpty()) {
                item {
                    Text(
                        "Most identifying readings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(narrative.topSignals, key = { it.category.id }) { top -> TopSignalView(top) }
            }

            item {
                Spacer(Modifier.height(8.dp))
                ClosingNote()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Mink's model-authored read of the summary, shown only above the rules tier.
 * With a loaded model it offers a one-tap read composed on the device by
 * [Guardian.narrate] from a grounded prompt, falling back to the deterministic
 * [FingerprintReport.detail] whenever the model returns nothing so the section is
 * never empty. Without a loaded model it nudges the one-time download, or notes
 * that the model is being prepared. The deterministic report stays the backbone
 * shown alongside; this section only adds a plain-language voice.
 */
@Composable
private fun MinkReadSection(
    guardian: Guardian,
    status: ModelStatus,
    message: String?,
    report: FingerprintReport,
    story: List<StoryCard>,
    appScope: CoroutineScope,
) {
    Column {
        Text(
            "Mink's read",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        if (status == ModelStatus.LOADED) {
            var narration by remember(report) { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(false) }

            when {
                loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Mink is reading…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        )
                    }
                }

                narration != null -> MinkReadCard(narration!!)

                else -> {
                    FilledTonalButton(
                        onClick = {
                            loading = true
                            // Launch on the process-wide app scope, not the
                            // composition scope, so scrolling or leaving the screen
                            // does not cancel a read mid-generation. The narrate leaf
                            // never throws and returns null when the model gives
                            // nothing usable, so fall back to the grounded report
                            // detail — the read is never empty.
                            appScope.launch {
                                val text = guardian.narrate(
                                    SummaryNarration.buildNarrationPrompt(report, story),
                                )
                                narration = text ?: report.detail
                                loading = false
                            }
                        },
                    ) {
                        Text("Let Mink read this")
                    }
                }
            }
        } else if (status == ModelStatus.ABSENT || status == ModelStatus.FAILED) {
            // A failed download carries the reason (e.g. a metered connection) in
            // model.message; surface it so the Download button does not look broken.
            // ABSENT keeps the plain invite copy.
            if (status == ModelStatus.FAILED && !message.isNullOrBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            } else {
                Text(
                    "With the on-device model, Mink can read this in its own words.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { guardian.prepareModel() }) {
                Text("Download")
            }
        } else if (status == ModelStatus.READY) {
            // Downloaded but not loaded: the model only loads once the guardian is
            // on, so point there rather than sitting on the generic "preparing" copy.
            Text(
                "Mink's model is downloaded. Turn on the guardian to load it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        } else {
            Text(
                "Mink is preparing its model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * The read itself in the screen's card styling: the model-written paragraph over a
 * quiet basis line marking that it was composed on the device. The same card
 * carries the deterministic [FingerprintReport.detail] when the model returns
 * nothing, so the basis holds either way.
 */
@Composable
private fun MinkReadCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Written on your device by Mink",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun UniquenessMeter(score: Int) {
    Column {
        Row {
            Text(
                "Recognizability",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$score / 100",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { score / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
        )
    }
}

@Composable
private fun NarrativeCardView(card: NarrativeCard) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                card.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                card.basis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun StoryCardView(card: StoryCard) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                card.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                card.basis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun TopSignalView(top: IdentifyingSignal) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                MinkIcons.forCategory(top.category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(top.category.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    top.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun ClosingNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Put it together", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "None of these readings is a name or an account. Together they can be " +
                    "distinctive enough to recognize your phone again. Trackers don't need " +
                    "your name, email, or location to know it is you.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Mink reads all of this on your phone and keeps it here. Nothing is uploaded, " +
                    "synced, or shared unless you choose to export.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}
