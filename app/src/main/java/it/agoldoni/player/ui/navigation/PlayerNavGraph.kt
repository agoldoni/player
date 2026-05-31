package it.agoldoni.player.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.agoldoni.player.ui.author.AuthorDetailScreen
import it.agoldoni.player.ui.author.AuthorListScreen
import it.agoldoni.player.ui.ftp.FtpConfigScreen
import it.agoldoni.player.ui.ftp.FtpSyncScreen
import it.agoldoni.player.ui.info.AppInfoScreen
import it.agoldoni.player.ui.playlist.PlaylistDetailScreen
import it.agoldoni.player.ui.playlist.PlaylistListScreen
import it.agoldoni.player.ui.stats.StatsScreen
import it.agoldoni.player.ui.trackdetail.TrackDetailScreen
import it.agoldoni.player.ui.tracklist.TrackListScreen
import it.agoldoni.player.ui.upload.WifiUploadScreen

sealed class Screen(val route: String) {
    object TrackList : Screen("track_list")
    object PlaylistList : Screen("playlist_list")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        fun createRoute(playlistId: String) = "playlist_detail/$playlistId"
    }
    object TrackDetail : Screen("track_detail/{trackId}") {
        fun createRoute(trackId: String) = "track_detail/$trackId"
    }
    object AuthorList : Screen("author_list")
    object AuthorDetail : Screen("author_detail/{artistName}") {
        fun createRoute(artistName: String) = "author_detail/${Uri.encode(artistName)}"
    }
    object AppInfo : Screen("app_info")
    object Stats : Screen("stats")
    object FtpConfig : Screen("ftp_config")
    object FtpSync : Screen("ftp_sync")
    object WifiUpload : Screen("wifi_upload")
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

        composable(Screen.AuthorList.route) {
            AuthorListScreen(
                onAuthorClick = { artistName ->
                    navController.navigate(Screen.AuthorDetail.createRoute(artistName))
                },
                onOpenDrawer = onOpenDrawer
            )
        }

        composable(
            route = Screen.AuthorDetail.route,
            arguments = listOf(navArgument("artistName") { type = NavType.StringType })
        ) {
            AuthorDetailScreen(
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

        composable(Screen.FtpConfig.route) {
            FtpConfigScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.FtpSync.route) {
            FtpSyncScreen(
                onBack = { navController.popBackStack() },
                onOpenConfig = {
                    navController.navigate(Screen.FtpConfig.route) {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.WifiUpload.route) {
            WifiUploadScreen(onBack = { navController.popBackStack() })
        }
    }
}
