package it.agoldoni.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.agoldoni.player.ui.info.AppInfoScreen
import it.agoldoni.player.ui.playlist.PlaylistDetailScreen
import it.agoldoni.player.ui.playlist.PlaylistListScreen
import it.agoldoni.player.ui.stats.StatsScreen
import it.agoldoni.player.ui.trackdetail.TrackDetailScreen
import it.agoldoni.player.ui.tracklist.TrackListScreen

sealed class Screen(val route: String) {
    object TrackList : Screen("track_list")
    object PlaylistList : Screen("playlist_list")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }
    object TrackDetail : Screen("track_detail/{trackId}") {
        fun createRoute(trackId: String) = "track_detail/$trackId"
    }
    object AppInfo : Screen("app_info")
    object Stats : Screen("stats")
}

@Composable
fun PlayerNavGraph(
    onOpenDrawer: () -> Unit = {},
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
                },
                onNavigateToPlaylists = {
                    navController.navigate(Screen.PlaylistList.route) {
                        popUpTo(Screen.TrackList.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenDrawer = onOpenDrawer
            )
        }

        composable(
            route = Screen.TrackDetail.route,
            arguments = listOf(navArgument("trackId") { type = NavType.StringType })
        ) {
            TrackDetailScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.PlaylistList.route) {
            PlaylistListScreen(
                onPlaylistClick = { playlistId ->
                    navController.navigate(Screen.PlaylistDetail.createRoute(playlistId))
                },
                onNavigateToTracks = {
                    navController.navigate(Screen.TrackList.route) {
                        popUpTo(Screen.PlaylistList.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenDrawer = onOpenDrawer
            )
        }

        composable(
            route = Screen.PlaylistDetail.route,
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
        ) {
            PlaylistDetailScreen(
                onTrackClick = { trackId ->
                    navController.navigate(Screen.TrackDetail.createRoute(trackId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.AppInfo.route) {
            AppInfoScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Stats.route) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
