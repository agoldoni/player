package com.example.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.player.ui.trackdetail.TrackDetailScreen
import com.example.player.ui.tracklist.TrackListScreen

sealed class Screen(val route: String) {
    object TrackList : Screen("track_list")
    object TrackDetail : Screen("track_detail/{trackId}") {
        fun createRoute(trackId: String) = "track_detail/$trackId"
    }
}

@Composable
fun PlayerNavGraph(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Screen.TrackList.route
    ) {
        composable(Screen.TrackList.route) {
            TrackListScreen(
                onTrackClick = { trackId ->
                    navController.navigate(Screen.TrackDetail.createRoute(trackId))
                }
            )
        }

        composable(
            route = Screen.TrackDetail.route,
            arguments = listOf(navArgument("trackId") { type = NavType.StringType })
        ) {
            TrackDetailScreen(onBack = { navController.popBackStack() })
        }
    }
}
