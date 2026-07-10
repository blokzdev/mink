package com.mink.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mink.core.model.DisplayHint
import com.mink.core.model.FingerprintSignal
import com.mink.core.model.SignalEntry

/**
 * Renders one [FingerprintSignal] honouring its [DisplayHint]: monospaced plain
 * text, label/value tables, a three-axis vector, wrapping chips, or a compound
 * side-by-side layout. Tapping the row expands the rationale that teaches why
 * the reading leaks identity.
 */
@Composable
fun SignalRow(
    signal: FingerprintSignal,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(signal.id) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = signal.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(Modifier.width(0.dp))
            Column(modifier = Modifier.padding(top = 8.dp)) {
                SignalValue(signal)
            }

            AnimatedVisibility(visible = expanded) {
                Text(
                    text = signal.rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SignalValue(signal: FingerprintSignal) {
    val entries = signal.entries.orEmpty()
    when (signal.displayHint) {
        DisplayHint.KEY_VALUE ->
            if (entries.isNotEmpty()) KeyValueBlock(entries) else PlainValue(signal.value)

        DisplayHint.AXIS ->
            if (entries.isNotEmpty()) AxisBlock(entries) else PlainValue(signal.value)

        DisplayHint.TAGS ->
            if (entries.isNotEmpty()) TagsBlock(entries.map { it.value }) else TagsFromValue(signal.value)

        DisplayHint.COMPOUND ->
            if (entries.isNotEmpty()) CompoundBlock(entries) else PlainValue(signal.value)

        DisplayHint.PLAIN -> PlainValue(signal.value)
    }
}

@Composable
private fun PlainValue(value: String) {
    Text(
        text = value.ifBlank { "Unavailable" },
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun KeyValueBlock(entries: List<SignalEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}

@Composable
private fun AxisBlock(entries: List<SignalEntry>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        entries.forEach { entry ->
            Column {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun CompoundBlock(entries: List<SignalEntry>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        entries.forEach { entry ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Text(
                    text = entry.value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TagsBlock(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.filter { it.isNotBlank() }.forEach { tag ->
            Text(
                text = tag,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun TagsFromValue(value: String) {
    val tags = value.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
    if (tags.isEmpty()) PlainValue(value) else TagsBlock(tags)
}
