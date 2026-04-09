package it.agoldoni.player.ui.playlist

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.PlaylistWithTracks
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.PlaylistRepository
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey
import javax.inject.Inject

private const val TAG = "PlaylistDetailVM"

sealed class PlaylistDetailEvent {
    data class ShowError(val message: String) : PlaylistDetailEvent()
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val trackRepository: TrackRepository,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val playlistId: String = savedStateHandle["playlistId"]!!

    val playlistWithTracks: StateFlow<PlaylistWithTracks?> = playlistRepository
        .getPlaylistWithTracks(playlistId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val allTracks: StateFlow<List<Track>> = trackRepository
        .getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _currentPlayingTrackId = MutableStateFlow<String?>(null)
    val currentPlayingTrackId: StateFlow<String?> = _currentPlayingTrackId

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tempPlaybackFile: File? = null
    private var playbackOrder: List<Track> = emptyList()
    private var currentPlaybackIndex: Int = -1
    private var currentDek: SecretKey? = null

    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
    }

    fun addTrack(trackId: String) {
        viewModelScope.launch {
            playlistRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrack(trackId: String) {
        viewModelScope.launch {
            playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun togglePlayback() {
        val player = mediaPlayer
        if (player != null && _currentPlayingTrackId.value != null) {
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
            } else {
                player.start()
                _isPlaying.value = true
            }
            return
        }

        val dek = cryptoManager.sessionDek
        if (dek == null) {
            viewModelScope.launch {
                _events.send(PlaylistDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
            return
        }

        val data = playlistWithTracks.value ?: return
        val tracks = data.tracks
        if (tracks.isEmpty()) return

        if (_shuffleEnabled.value) {
            playbackOrder = tracks.shuffled()
        } else {
            playbackOrder = tracks
        }

        val lastPlayedId = data.playlist.lastPlayedTrackId
        val startIndex = if (!_shuffleEnabled.value && lastPlayedId != null) {
            val idx = playbackOrder.indexOfFirst { it.id == lastPlayedId }
            if (idx >= 0) idx else 0
        } else {
            0
        }

        currentDek = dek
        playTrackAt(startIndex, playbackOrder, dek)
    }

    fun skipToNext() {
        val dek = currentDek ?: return
        if (playbackOrder.isEmpty() || currentPlaybackIndex < 0) return

        val nextIndex = currentPlaybackIndex + 1
        if (nextIndex >= playbackOrder.size) {
            if (_shuffleEnabled.value) {
                playbackOrder = playbackOrder.shuffled()
            }
            playTrackAt(0, playbackOrder, dek)
        } else {
            playTrackAt(nextIndex, playbackOrder, dek)
        }
    }

    private fun playTrackAt(index: Int, tracks: List<Track>, dek: SecretKey) {
        currentPlaybackIndex = index
        val track = tracks[index]
        _currentPlayingTrackId.value = track.id

        releasePlayer()

        viewModelScope.launch {
            // Persisti la posizione corrente
            playlistRepository.updateLastPlayedTrackId(playlistId, track.id)

            withContext(Dispatchers.IO) {
                try {
                    val encryptedFile = File(track.uri)
                    val tempFile = cryptoManager.decryptToTempFile(dek, encryptedFile)
                    tempPlaybackFile = tempFile

                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            val nextIndex = index + 1
                            if (nextIndex < tracks.size) {
                                playTrackAt(nextIndex, tracks, dek)
                            } else {
                                // Playlist terminata: reset alla prima traccia
                                _isPlaying.value = false
                                _currentPlayingTrackId.value = null
                                cleanupTempFile()
                                viewModelScope.launch {
                                    playlistRepository.updateLastPlayedTrackId(
                                        playlistId, tracks.first().id
                                    )
                                }
                            }
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                            _isPlaying.value = false
                            _currentPlayingTrackId.value = null
                            cleanupTempFile()
                            false
                        }
                        prepare()
                    }

                    mediaPlayer = player
                    player.start()
                    _isPlaying.value = true
                    Log.d(TAG, "Riproduzione playlist brano ${index + 1}/${tracks.size}: ${track.title}")

                } catch (e: Exception) {
                    Log.e(TAG, "Errore riproduzione brano: ${track.title}", e)
                    _isPlaying.value = false
                    _currentPlayingTrackId.value = null
                    cleanupTempFile()
                }
            }
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        cleanupTempFile()
    }

    private fun cleanupTempFile() {
        tempPlaybackFile?.delete()
        tempPlaybackFile = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
