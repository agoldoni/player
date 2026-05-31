package it.agoldoni.player.data.repository

import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.local.entity.ArtistSummary
import it.agoldoni.player.data.local.entity.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao
) {
    fun getAllTracks(): Flow<List<Track>> = trackDao.getAllTracks()

    suspend fun getAllTracksOnce(): List<Track> = trackDao.getAllTracksOnce()

    suspend fun insertTrack(track: Track) = trackDao.insertTrack(track)

    suspend fun insertTracks(tracks: List<Track>) = trackDao.insertTracks(tracks)

    suspend fun deleteTrack(track: Track) = trackDao.deleteTrack(track)

    suspend fun deleteTracksByArtist(artist: String) = trackDao.deleteTracksByArtist(artist)

    suspend fun getTrackByUri(uri: String): Track? = trackDao.getTrackByUri(uri)

    suspend fun getTrackById(id: String): Track? = trackDao.getTrackById(id)

    fun getTrackCount(): Flow<Int> = trackDao.getTrackCount()

    fun getTotalDuration(): Flow<Long> = trackDao.getTotalDuration()

    fun getAlbumCount(): Flow<Int> = trackDao.getAlbumCount()

    fun getArtistCount(): Flow<Int> = trackDao.getArtistCount()

    fun getTotalOriginalFileSize(): Flow<Long> = trackDao.getTotalOriginalFileSize()

    fun getTotalEncryptedFileSize(): Flow<Long> = trackDao.getTotalEncryptedFileSize()

    fun getDistinctArtistsWithCount(): Flow<List<ArtistSummary>> =
        trackDao.getDistinctArtistsWithCount()

    fun getTracksByArtist(artist: String): Flow<List<Track>> =
        trackDao.getTracksByArtist(artist)
}
