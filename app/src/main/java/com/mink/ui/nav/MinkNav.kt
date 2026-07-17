package com.mink.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.data.MinkServices
import com.mink.ui.screens.AboutScreen
import com.mink.ui.screens.AppAccessScreen
import com.mink.ui.screens.CategoryDetailScreen
import com.mink.ui.screens.CompanionScreen
import com.mink.ui.screens.DnsFlowScreen
import com.mink.ui.screens.ExportScreen
import com.mink.ui.screens.GuardianChatScreen
import com.mink.ui.screens.GuardianScreen
import com.mink.ui.screens.HomeScreen
import com.mink.ui.screens.NetworkUsageScreen
import com.mink.ui.screens.OnboardingScreen
import com.mink.ui.screens.PermissionsScreen
import com.mink.ui.screens.SettingsScreen
import com.mink.ui.screens.SummaryScreen
import com.mink.ui.screens.WatchedAppsScreen

/** The navigation destinations of the app, kept in one place. */
object MinkRoute {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CATEGORY = "category/{id}"
    const val SUMMARY = "summary"
    const val GUARDIAN = "guardian"
    const val GUARDIAN_CHAT = "guardianChat"
    const val COMPANION = "companion"
    const val ABOUT = "about"
    const val EXPORT = "export"
    const val PERMISSIONS = "permissions"
    const val APP_ACCESS = "app_access"
    const val WATCHED_APPS = "watched_apps"
    const val NETWORK_USAGE = "network_usage"
    const val DNS_FLOW = "dns_flow"
    const val SETTINGS = "settings"

    fun category(category: SignalCategory): String = "category/${category.id}"

    /** Routes a companion bubble action is allowed to deep-link into. */
    private val deepLinkable =
        setOf(
            HOME, GUARDIAN, COMPANION, SUMMARY, ABOUT, EXPORT, PERMISSIONS, APP_ACCESS,
            WATCHED_APPS, NETWORK_USAGE, DNS_FLOW, SETTINGS,
        )

    /** Whether [route] is a known, parameterless destination we can navigate to. */
    fun isDeepLinkable(route: String?): Boolean = route != null && route in deepLinkable
}

/**
 * A tiny process-wide relay for a route the companion overlay asks the app to
 * open. [com.mink.MainActivity] writes the route from the launch intent; the
 * navigation host reads it once and navigates, then clears it so a rotation
 * does not replay the jump. Kept here so neither the Activity nor the overlay
 * needs a reference to the NavController.
 */
object CompanionDeepLink {
    private val _pendingRoute = MutableStateFlow<String?>(null)
    val pendingRoute: StateFlow<String?> = _pendingRoute.asStateFlow()

    /** Offer a route to open; ignored unless it is a known deep-link target. */
    fun offer(route: String?) {
        if (MinkRoute.isDeepLinkable(route)) _pendingRoute.value = route
    }

    /** Clear the pending route once it has been navigated to. */
    fun consume() {
        _pendingRoute.value = null
    }
}

/**
 * A tiny process-wide relay for a question the user wants to ask Mink about a
 * specific finding. [com.mink.ui.screens.GuardianScreen] writes a grounded
 * question from an alert card and navigates to the chat;
 * [com.mink.ui.screens.GuardianChatScreen] reads it once on first composition
 * into its input, then clears it so a
 * rotation does not replay the prefill. Kept here beside [CompanionDeepLink] so
 * neither screen needs a reference to the other.
 */
object ChatPrefill {
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    /** Offer a draft question to pre-fill the chat input; blanks are ignored. */
    fun offer(question: String?) {
        val trimmed = question?.trim()
        if (!trimmed.isNullOrEmpty()) _draft.value = trimmed
    }

    /** Clear the pending draft once it has been consumed into the input. */
    fun consume() {
        _draft.value = null
    }
}

/**
 * Supplied by [com.mink.ui.MinkRoot]. Screens call this to request an Android
 * runtime permission; the root's Activity Result launcher records the outcome
 * and kicks off collection for the newly unlocked category.
 */
val LocalPermissionRequester = staticCompositionLocalOf<(PermissionKind) -> Unit> {
    { /* no-op until MinkRoot provides the real bridge */ }
}

/**
 * The whole navigation graph. Hosted by [com.mink.ui.MinkRoot], which also owns
 * the permission bridge exposed via [LocalPermissionRequester].
 */
@Composable
fun MinkNavHost(
    navController: NavHostController,
    services: MinkServices,
    startDestination: String,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(MinkRoute.ONBOARDING) {
            OnboardingScreen(
                onDone = {
                    navController.navigate(MinkRoute.HOME) {
                        popUpTo(MinkRoute.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(MinkRoute.HOME) {
            HomeScreen(services = services, navController = navController)
        }

        composable(
            route = MinkRoute.CATEGORY,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            val category = SignalCategory.fromId(id)
            CategoryDetailScreen(
                category = category,
                services = services,
                onBack = { navController.popBackStack() },
                onOpenPermissions = { navController.navigate(MinkRoute.PERMISSIONS) },
            )
        }

        composable(MinkRoute.SUMMARY) {
            SummaryScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.GUARDIAN) {
            GuardianScreen(
                services = services,
                onBack = { navController.popBackStack() },
                onOpenChat = { navController.navigate(MinkRoute.GUARDIAN_CHAT) },
                onOpenWatchedApps = { navController.navigate(MinkRoute.WATCHED_APPS) },
            )
        }

        composable(MinkRoute.GUARDIAN_CHAT) {
            GuardianChatScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.COMPANION) {
            CompanionScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.EXPORT) {
            ExportScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.PERMISSIONS) {
            PermissionsScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.APP_ACCESS) {
            AppAccessScreen(services = services, onBack = { navController.popBackStack() })
        }

        composable(MinkRoute.WATCHED_APPS) {
            WatchedAppsScreen(
                services = services,
                onBack = { navController.popBackStack() },
                onOpenAppAccess = { navController.navigate(MinkRoute.APP_ACCESS) },
            )
        }

        composable(MinkRoute.NETWORK_USAGE) {
            NetworkUsageScreen(
                services = services,
                onBack = { navController.popBackStack() },
            )
        }

        composable(MinkRoute.DNS_FLOW) {
            DnsFlowScreen(
                services = services,
                onBack = { navController.popBackStack() },
            )
        }

        composable(MinkRoute.SETTINGS) {
            SettingsScreen(
                services = services,
                onBack = { navController.popBackStack() },
                onOpenNetworkActivity = { navController.navigate(MinkRoute.DNS_FLOW) },
                onOpenPermissions = { navController.navigate(MinkRoute.PERMISSIONS) },
                onOpenExport = { navController.navigate(MinkRoute.EXPORT) },
                onOpenAbout = { navController.navigate(MinkRoute.ABOUT) },
            )
        }
    }
}
