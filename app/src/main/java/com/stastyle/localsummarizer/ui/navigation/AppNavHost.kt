package com.stastyle.localsummarizer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stastyle.localsummarizer.ui.screens.history.HistoryScreen
import com.stastyle.localsummarizer.ui.screens.main.MainScreen
import com.stastyle.localsummarizer.ui.screens.settings.SettingsScreen

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.HISTORY) {
            HistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
