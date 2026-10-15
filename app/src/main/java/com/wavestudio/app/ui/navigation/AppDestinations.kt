package com.wavestudio.app.ui.navigation

sealed class AppDestinations(val route: String) {
    object Home : AppDestinations("home")
    object Projects : AppDestinations("projects")
    object Editor : AppDestinations("editor/{projectId}") {
        fun createRoute(projectId: Long) = "editor/$projectId"
        const val ARG_PID = "projectId"
    }
    object Settings : AppDestinations("settings")
    object Studio : AppDestinations("studio")
    object Plugins : AppDestinations("plugins")
    object Mixer : AppDestinations("mixer/{projectId}") {
        fun createRoute(projectId: Long) = "mixer/$projectId"
    }
    object Effects : AppDestinations("effects/{trackId}") {
        fun createRoute(trackId: Int) = "effects/$trackId"
    }
}
