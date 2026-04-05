package com.example.player.data.repository

import com.example.player.data.local.dao.TrackDao
import com.example.player.data.local.entity.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao
) {
    fun getAllTracks(): Flow<List<Track>> = trackDao.getAllTracks()

    suspend fun insertTrack(track: Track) = trackDao.insertTrack(track)

    suspend fun insertTracks(tracks: List<Track>) = trackDao.insertTracks(tracks)

    suspend fun deleteTrack(track: Track) = trackDao.deleteTrack(track)

    suspend fun getTrackByUri(uri: String): Track? = trackDao.getTrackByUri(uri)

    suspend fun getTrackById(id: String): Track? = trackDao.getTrackById(id)
}
