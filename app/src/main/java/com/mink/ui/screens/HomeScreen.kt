package com.mink.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.mink.core.model.SignalCategory
import com.mink.core.model.Sensitivity
import com.mink.data.MinkServices
import com.mink.ui.components.MinkIcons
import com.mink.ui.components.TierChip
import com.mink.ui.nav.MinkRoute
import com.mink.ui.vm.HomeViewModel
import com.mink.ui.vm.SimpleFactory

/**
 * The landing screen. A hero card frames "what your phone reveals" and opens the
 * summary, a Guardian banner and a Companion entry sit below it, and the rest is
 * the category list grouped by sensitivity tier, mirroring Loupe's home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    services: MinkServices,
    navController: NavHostController,
) {
    val vm: HomeViewModel = viewModel(
        factory = SimpleFactory { HomeViewModel(services.store, services.permissions) },
    )
    LaunchedEffect(Unit) { vm.refreshAll() }

    val signals by vm.signals.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mink") },
                actions = {
                    IconButton(onClick = { navController.navigate(MinkRoute.EXPORT) }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export")
                    }
                    IconButton(onClick = { navController.navigate(MinkRoute.ABOUT) }) {
                        Icon(Icons.Filled.Info, contentDescription = "About")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeroCard(
                    populatedCategories = signals.count { it.value.isNotEmpty() },
                    onClick = { navController.navigate(MinkRoute.SUMMARY) },
                )
            }
            item {
                EntryBanner(
                    icon = Icons.Filled.Shield,
                    title = "Meet Mink, your guardian",
                    subtitle = "A calm, on-device watcher that explains what apps can see.",
                    onClick = { navController.navigate(MinkRoute.GUARDIAN) },
                )
            }
            item {
                EntryBanner(
                    icon = Icons.Filled.Pets,
                    title = "Floating companion",
                    subtitle = "Let the 8-bit Mink hover on screen and speak up when it matters.",
                    onClick = { navController.navigate(MinkRoute.COMPANION) },
                )
            }
            item {
                EntryBanner(
                    icon = Icons.Filled.Apps,
                    title = "App access",
                    subtitle = "See which apps can reach your location, camera, and microphone.",
                    onClick = { navController.navigate(MinkRoute.APP_ACCESS) },
                )
            }

            Sensitivity.entries.forEach { tier ->
                val categories = services.store.categories(tier)
                item(key = "header-${tier.name}") {
                    TierHeader(tier)
                }
                items(categories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        count = signals[category]?.size ?: 0,
                        onClick = { navController.navigate(MinkRoute.category(category)) },
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun HeroCard(populatedCategories: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "What your phone reveals",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Any app can quietly read many of these values without asking. On their " +
                    "own they seem harmless. Together they can single your phone out.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Read the summary",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun EntryBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun TierHeader(tier: Sensitivity) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                MinkIcons.forSensitivity(tier),
                contentDescription = null,
                tint = tier.tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                tier.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            tier.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CategoryRow(
    category: SignalCategory,
    count: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MinkIcons.forCategory(category),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            Spacer(Modifier.size(8.dp))
            if (count > 0) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.size(8.dp))
            }
            TierChip(category.sensitivity)
        }
    }
}
