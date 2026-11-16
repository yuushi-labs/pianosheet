package com.yuushilabs.pianosheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yuushilabs.pianosheet.feature.home.HomeScreen
import com.yuushilabs.pianosheet.feature.live.LiveScoreScreen
import com.yuushilabs.pianosheet.feature.project.ProjectDetailScreen
import com.yuushilabs.pianosheet.feature.settings.SettingsScreen
import com.yuushilabs.pianosheet.ui.theme.PianoSheetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PianoSheetTheme {
                PianoSheetApp()
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun PianoSheetApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNewRecording = { navController.navigate(Routes.LIVE) },
                onOpenProject = { id -> navController.navigate("project/$id") },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.LIVE) {
            LiveScoreScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate("project/$id") {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            ProjectDetailScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val PROJECT = "project/{projectId}"
    const val SETTINGS = "settings"
}
