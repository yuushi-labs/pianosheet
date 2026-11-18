package com.yuushilabs.pianosheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yuushilabs.pianosheet.app.AppContainer
import com.yuushilabs.pianosheet.feature.analyze.AnalyzeScreen
import com.yuushilabs.pianosheet.feature.home.HomeScreen
import com.yuushilabs.pianosheet.feature.live.LiveScoreScreen
import com.yuushilabs.pianosheet.feature.project.ProjectDetailScreen
import com.yuushilabs.pianosheet.feature.score.ScoreScreen
import com.yuushilabs.pianosheet.feature.settings.SettingsScreen
import com.yuushilabs.pianosheet.ui.theme.PianoSheetTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as PianoSheetApplication).container

    /**
     * 0.4.7: the release check runs on every launch and every return to the
     * foreground, so opening the app is enough — no 確認 button and no cool-down.
     * It lives on the activity (not a screen) because home, settings and the
     * player all come and go while the updater is shared by the whole process.
     */
    private val updateObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            container.updateController.onForeground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(updateObserver)
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
                onOpenProject = { id -> navController.navigate(Routes.project(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onImported = { id ->
                    navController.navigate(Routes.analyze(id, force = false)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(Routes.LIVE) {
            LiveScoreScreen(
                onBack = { navController.popBackStack() },
                onSaved = { id ->
                    navController.navigate(Routes.analyze(id, force = false)) {
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
                onOpenScore = { navController.navigate(Routes.score(projectId)) },
                onAnalyse = { force -> navController.navigate(Routes.analyze(projectId, force)) },
            )
        }
        composable(
            route = Routes.ANALYZE,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("force") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            val force = entry.arguments?.getBoolean("force") ?: false
            AnalyzeScreen(
                projectId = projectId,
                forceReanalyse = force,
                onDone = { id ->
                    navController.navigate(Routes.score(id)) {
                        popUpTo(Routes.ANALYZE) { inclusive = true }
                    }
                },
                onCancelled = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.SCORE,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            ScoreScreen(
                projectId = projectId,
                onBack = { navController.popBackStack() },
                onAnalyse = { id -> navController.navigate(Routes.analyze(id, force = true)) },
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
    const val ANALYZE = "analyze/{projectId}/{force}"
    const val SCORE = "score/{projectId}"

    fun project(id: String): String = "project/$id"

    fun analyze(id: String, force: Boolean): String = "analyze/$id/$force"

    fun score(id: String): String = "score/$id"
}
