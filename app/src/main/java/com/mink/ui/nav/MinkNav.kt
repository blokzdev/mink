package com.mink.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mink.core.model.PermissionKind
import com.mink.core.model.SignalCategory
import com.mink.data.MinkServices
import com.mink.ui.screens.AboutScreen
import com.mink.ui.screens.CategoryDetailScreen
import com.mink.ui.screens.CompanionScreen
import com.mink.ui.screens.ExportScreen
import com.mink.ui.screens.GuardianChatScreen
import com.mink.ui.screens.GuardianScreen
import com.mink.ui.screens.HomeScreen
import com.mink.ui.screens.OnboardingScreen
import com.mink.ui.screens.PermissionsScreen
import com.mink.ui.screens.SummaryScreen

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

    fun category(category: SignalCategory): String = "category/${category.id}"
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
    }
}
