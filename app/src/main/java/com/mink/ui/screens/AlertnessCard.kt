package com.mink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mink.guardian.Alertness
import com.mink.guardian.AlertSource

/**
 * The alertness dial (Quiet / Standard / Paranoid) plus the per-source mute
 * switches. Shared between the guardian dashboard and the Settings screen so the
 * single control has one implementation; state is the guardian's, so both entry
 * points stay consistent. The per-source list iterates [AlertSource.entries], so
 * any new source appears here automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlertnessCard(
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
