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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mink.data.MinkServices
import com.mink.narrative.DeviceStoryContext
import com.mink.narrative.FingerprintNarrative
import com.mink.narrative.IdentifyingSignal
import com.mink.narrative.NarrativeCard
import com.mink.narrative.StoryCard
import com.mink.narrative.StoryNarrative
import com.mink.ui.components.MinkIcons
import com.mink.ui.vm.SimpleFactory
import com.mink.ui.vm.SummaryViewModel

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
