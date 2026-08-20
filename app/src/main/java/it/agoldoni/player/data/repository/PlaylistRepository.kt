package it.agoldoni.player.data.repository

import it.agoldoni.player.data.local.dao.PlaylistDao
import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.local.entity.PlaylistTrackCrossRef
import it.agoldoni.player.data.local.entity.PlaylistWithTracks
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao
) {
    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    fun getPlaylistWithTracks(playlistId: String): Flow<PlaylistWithTracks?> =
        playlistDao.getPlaylistWithTracks(playlistId)

    suspend fun getAllPlaylistsOnce(): List<Playlist> = playlistDao.getAllPlaylistsOnce()

    suspend fun getPlaylistById(playlistId: String): Playlist? =
        playlistDao.getPlaylistById(playlistId)

    suspend fun getPlaylistByName(name: String): Playlist? =
        playlistDao.getPlaylistByName(name)

    suspend fun insertPlaylist(playlist: Playlist) =
        playlistDao.insertPlaylist(playlist)

    suspend fun updatePlaylist(playlist: Playlist) =
        playlistDao.updatePlaylist(playlist)

    suspend fun deletePlaylist(playlist: Playlist) =
        playlistDao.deletePlaylist(playlist)

    suspend fun addTrackToPlaylist(playlistId: String, trackId: String) =
        playlistDao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId))

    suspend fun addTracksToPlaylist(crossRefs: List<PlaylistTrackCrossRef>) =
        playlistDao.addTracksToPlaylist(crossRefs)

    suspend fun getCrossRefsForPlaylist(playlistId: String): List<PlaylistTrackCrossRef> =
        playlistDao.getCrossRefsForPlaylist(playlistId)

    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) =
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)

    fun getTrackCountForPlaylist(playlistId: String): Flow<Int> =
        playlistDao.getTrackCountForPlaylist(playlistId)

    suspend fun updateLastPlayedTrackId(playlistId: String, trackId: String?) =
        playlistDao.updateLastPlayedTrackId(playlistId, trackId)
}
