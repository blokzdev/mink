package com.mink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.data.MinkServices
import com.mink.ui.nav.CompanionDeepLink
import com.mink.ui.nav.LocalPermissionRequester
import com.mink.ui.nav.MinkNavHost
import com.mink.ui.nav.MinkRoute

/**
 * Root composable and navigation host for the whole app.
 *
 * Owns two integration seams for the rest of the UI:
 *  1. The start destination, chosen from whether onboarding has been seen.
 *  2. The Activity permission bridge: a [rememberLauncherForActivityResult]
 *     that records the outcome through [MinkServices.permissions] and kicks off
 *     collection for the newly unlocked category. Screens reach it through
 *     [LocalPermissionRequester].
 *
 * The signature is the integration contract and must stay
 * `MinkRoot(services: MinkServices)`.
 */
@Composable
fun MinkRoot(services: MinkServices) {
    val context = LocalContext.current
    val navController = rememberNavController()

    // The permission kind currently in flight, so the result callback knows
    // which kind and category to attribute the grant to. Saved (by name) so a
    // rotation or process death while the OS dialog is up does not lose the
    // grant's attribution when the ActivityResult is re-delivered.
    var pendingKind by rememberSaveable(stateSaver = PermissionKindSaver) {
        mutableStateOf<PermissionKind?>(null)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val kind = pendingKind ?: return@rememberLauncherForActivityResult
        val granted = results.values.any { it }
        services.permissions.record(kind, granted)
        if (granted) {
            categoryForKind(kind)?.let { services.store.collect(it) }
        }
        pendingKind = null
    }

    val requestPermission: (PermissionKind) -> Unit = remember(launcher) {
        { kind ->
            val perms = kind.manifestPermissions
            if (perms.isEmpty()) {
                // Nothing to ask for on this OS version; treat as granted.
                services.permissions.record(kind, true)
                categoryForKind(kind)?.let { services.store.collect(it) }
            } else {
                pendingKind = kind
                launcher.launch(perms.toTypedArray())
            }
        }
    }

    // Null until DataStore resolves, so the start destination is never guessed.
    val seen by OnboardingStore.seenFlow(context).collectAsStateWithLifecycle(initialValue = null)

    when (val hasSeen = seen) {
        null -> {
            // Preference not resolved yet; a brief spinner avoids flashing the
            // wrong start destination.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> {
            val start = if (hasSeen) MinkRoute.HOME else MinkRoute.ONBOARDING
            CompositionLocalProvider(LocalPermissionRequester provides requestPermission) {
                MinkNavHost(
                    navController = navController,
                    services = services,
                    startDestination = start,
                )
            }

            // A companion bubble action can ask the app to open a screen. Honour
            // it once the graph exists, then clear it so a rotation does not
            // replay the jump. Only meaningful past onboarding.
            val pendingRoute by CompanionDeepLink.pendingRoute.collectAsStateWithLifecycle()
            LaunchedEffect(pendingRoute, hasSeen) {
                val route = pendingRoute
                if (hasSeen && route != null) {
                    navController.navigate(route) { launchSingleTop = true }
                    CompanionDeepLink.consume()
                }
            }
        }
    }
}

private fun categoryForKind(kind: PermissionKind): SignalCategory? =
    SignalCategory.entries.firstOrNull { it.permission == kind }

// Stores the in-flight PermissionKind by its name so it survives activity
// recreation while the OS permission dialog is showing.
private val PermissionKindSaver: Saver<PermissionKind?, String> = Saver(
    save = { it?.name },
    restore = { name -> runCatching { PermissionKind.valueOf(name) }.getOrNull() },
)
