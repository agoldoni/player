package it.agoldoni.player.data.local.dao

import androidx.room.*
import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.local.entity.PlaylistTrackCrossRef
import it.agoldoni.player.data.local.entity.PlaylistWithTracks
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistWithTracks(playlistId: String): Flow<PlaylistWithTracks?>

    /** Lettura one-shot di tutte le playlist: il trasferimento non può osservare un Flow. */
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    suspend fun getAllPlaylistsOnce(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: String): Playlist?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: Playlist)

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    /** Inserimento in blocco delle relazioni, usato quando si ricostruisce una playlist ricevuta. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTracksToPlaylist(crossRefs: List<PlaylistTrackCrossRef>)

    /** Relazioni di una playlist in ordine di aggiunta: serve a comporre il manifest. */
    @Query("SELECT * FROM playlist_track_cross_ref WHERE playlistId = :playlistId ORDER BY addedAt ASC")
    suspend fun getCrossRefsForPlaylist(playlistId: String): List<PlaylistTrackCrossRef>

    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("SELECT COUNT(*) FROM playlist_track_cross_ref WHERE playlistId = :playlistId")
    fun getTrackCountForPlaylist(playlistId: String): Flow<Int>

    @Query("UPDATE playlists SET lastPlayedTrackId = :trackId WHERE id = :playlistId")
    suspend fun updateLastPlayedTrackId(playlistId: String, trackId: String?)
}
