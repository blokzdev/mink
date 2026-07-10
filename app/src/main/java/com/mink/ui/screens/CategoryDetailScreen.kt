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
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mink.core.model.SignalCategory
import com.mink.data.LoadState
import com.mink.data.MinkServices
import com.mink.data.PermissionStatus
import com.mink.ui.components.MinkIcons
import com.mink.ui.components.SignalRow
import com.mink.ui.components.TierChip
import com.mink.ui.nav.LocalPermissionRequester
import com.mink.ui.vm.CategoryViewModel
import com.mink.ui.vm.SimpleFactory

/** Categories whose provider streams live values while the screen is open. */
private val LIVE_CATEGORIES = setOf(
    SignalCategory.BATTERY,
    SignalCategory.LOCATION,
    SignalCategory.ACTIVITY,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailScreen(
    category: SignalCategory?,
    services: MinkServices,
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    if (category == null) {
        UnknownCategory(onBack)
        return
    }

    val live = category in LIVE_CATEGORIES
    val vm: CategoryViewModel = viewModel(
        key = category.id,
        factory = SimpleFactory { CategoryViewModel(services.store, category, live) },
    )

    val statuses by services.permissions.statuses.collectAsStateWithLifecycle()
    val granted = category.permission?.let {
        services.permissions.isGranted(it) || statuses[it] == PermissionStatus.GRANTED
    } ?: true

    DisposableEffect(category, granted) {
        if (granted) vm.start()
        onDispose { vm.stop() }
    }

    val signals by vm.signals.collectAsStateWithLifecycle()
    val loadStates by vm.loadStates.collectAsStateWithLifecycle()
    val rows = signals[category].orEmpty()
    val state = loadStates[category] ?: LoadState.Idle

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(category.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CategoryHeader(category, live) }

            if (category.permission != null && !granted) {
                item {
                    PermissionGate(
                        rationale = category.permission!!.rationale,
                        onGrant = LocalPermissionRequester.current.let { request ->
                            { request(category.permission!!) }
                        },
                        onSettings = onOpenPermissions,
                    )
                }
            } else {
                when {
                    rows.isEmpty() && state is LoadState.Loading -> item { LoadingBlock() }
                    rows.isEmpty() && state is LoadState.Denied -> item { DeniedBlock(state.reason) }
                    rows.isEmpty() -> item { EmptyBlock() }
                    else -> items(rows, key = { it.id }) { signal -> SignalRow(signal) }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun CategoryHeader(category: SignalCategory, live: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                MinkIcons.forCategory(category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.size(10.dp))
            TierChip(category.sensitivity)
            if (live) {
                Spacer(Modifier.size(8.dp))
                Text(
                    "Live",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            category.sensitivity.blurb,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun PermissionGate(
    rationale: String,
    onGrant: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text(
                    "This one needs your permission",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Mink reads this only on your phone and keeps it here. Nothing leaves the " +
                    "device unless you choose to export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onGrant) { Text("Grant permission") }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(12.dp))
        Text("Reading quietly...", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeniedBlock(reason: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("Nothing to show", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "This reading is unavailable right now. $reason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyBlock() {
    Text(
        "No readings here yet.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(12.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnknownCategory(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Not found") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("That category is not available.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
