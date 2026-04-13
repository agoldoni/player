package it.agoldoni.player.data.local.dao

import androidx.room.*
import it.agoldoni.player.data.local.entity.Track
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    fun getAllTracks(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY title ASC")
    suspend fun getAllTracksOnce(): List<Track>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrackById(id: String): Track?

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun getTrackByUri(uri: String): Track?

    @Query(
        "SELECT * FROM tracks WHERE title = :title AND artist = :artist AND album = :album LIMIT 1"
    )
    suspend fun getTrackByMetadata(title: String, artist: String, album: String): Track?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: Track)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Query("SELECT id FROM tracks")
    suspend fun getAllTrackIds(): List<String>

    @Query("SELECT albumArtPath FROM tracks WHERE albumArtPath IS NOT NULL")
    suspend fun getAllAlbumArtPaths(): List<String>

    @Delete
    suspend fun deleteTrack(track: Track)

    @Query("SELECT COUNT(*) FROM tracks")
    fun getTrackCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(duration), 0) FROM tracks")
    fun getTotalDuration(): Flow<Long>

    @Query("SELECT COUNT(DISTINCT album) FROM tracks")
    fun getAlbumCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT artist) FROM tracks")
    fun getArtistCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(originalFileSize), 0) FROM tracks")
    fun getTotalOriginalFileSize(): Flow<Long>

    @Query("SELECT COALESCE(SUM(encryptedFileSize), 0) FROM tracks")
    fun getTotalEncryptedFileSize(): Flow<Long>
}
