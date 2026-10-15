package com.wavestudio.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.wavestudio.app.ui.editor.EditorScreen
import com.wavestudio.app.ui.effects.EffectsScreen
import com.wavestudio.app.ui.filebrowser.FileBrowserScreen
import com.wavestudio.app.ui.home.HomeScreen
import com.wavestudio.app.ui.mixer.MixerScreen
import com.wavestudio.app.ui.plugins.PluginsScreen
import com.wavestudio.app.ui.projects.ProjectsScreen
import com.wavestudio.app.ui.settings.SettingsScreen
import com.wavestudio.app.ui.voice.VoiceToolsScreen
import com.wavestudio.app.ui.piano.PianoBeatScreen
import com.wavestudio.app.engine.NativeAudioEngine

@Composable
fun WaveStudioNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = AppDestinations.Home.route) {
        composable(AppDestinations.Home.route) {
            HomeScreen(
                onOpenProjects = { navController.navigate(AppDestinations.Projects.route) },
                onOpenSettings = { navController.navigate(AppDestinations.Settings.route) },
                onOpenPlugins = { navController.navigate(AppDestinations.Plugins.route) },
                onNewProject = { navController.navigate(AppDestinations.Editor.createRoute(-1L)) },
                onOpenProject = { id -> navController.navigate(AppDestinations.Editor.createRoute(id)) },
                onBrowseFiles = { navController.navigate(AppDestinations.FileBrowser.route) }
            )
        }
        composable(AppDestinations.Projects.route) {
            ProjectsScreen(
                onOpenProject = { id -> navController.navigate(AppDestinations.Editor.createRoute(id)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            AppDestinations.Editor.route,
            arguments = listOf(navArgument(AppDestinations.Editor.ARG_PID) { type = NavType.LongType })
        ) { entry ->
            val pid = entry.arguments?.getLong(AppDestinations.Editor.ARG_PID) ?: -1L
            EditorScreen(
                projectId = pid,
                onBack = { navController.popBackStack() },
                onOpenMixer = { navController.navigate(AppDestinations.Mixer.createRoute(pid)) },
                onOpenEffects = { tid -> navController.navigate(AppDestinations.Effects.createRoute(tid)) },
                onOpenPlugins = { navController.navigate(AppDestinations.Plugins.route) }
            )
        }
        composable(AppDestinations.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestinations.Plugins.route) {
            PluginsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            AppDestinations.Mixer.route,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) {
            MixerScreen(onBack = { navController.popBackStack() })
        }
        composable(
            AppDestinations.Effects.route,
            arguments = listOf(navArgument("trackId") { type = NavType.IntType })
        ) { entry ->
            val tid = entry.arguments?.getInt("trackId") ?: 0
            EffectsScreen(trackId = tid, onBack = { navController.popBackStack() })
        }
        composable(AppDestinations.FileBrowser.route) {
            FileBrowserScreen(onBack = { navController.popBackStack() })
        }
        composable(AppDestinations.Voice.route) {
            val eng = remember { com.wavestudio.app.engine.NativeAudioEngine() }
            VoiceToolsScreen(engine = eng)
        }
        composable(AppDestinations.Piano.route) {
            val eng = remember { com.wavestudio.app.engine.NativeAudioEngine() }
            PianoBeatScreen(engine = eng)
        }
    }
}
