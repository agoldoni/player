package it.agoldoni.player.ui.author

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey
import javax.inject.Inject

private const val TAG = "AuthorDetailVM"

sealed class AuthorDetailEvent {
    data class ShowError(val message: String) : AuthorDetailEvent()
}

@HiltViewModel
class AuthorDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    val artistName: String = Uri.decode(savedStateHandle["artistName"]!!)

    val tracks: StateFlow<List<Track>> = trackRepository
        .getTracksByArtist(artistName)
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

    private val _events = Channel<AuthorDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tempPlaybackFile: File? = null
    private var playbackOrder: List<Track> = emptyList()
    private var currentPlaybackIndex: Int = -1
    private var currentDek: SecretKey? = null

    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
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
                _events.send(AuthorDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
            return
        }

        val current = tracks.value
        if (current.isEmpty()) return

        playbackOrder = if (_shuffleEnabled.value) current.shuffled() else current
        currentDek = dek
        playTrackAt(0, playbackOrder, dek)
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

    private fun playTrackAt(index: Int, queue: List<Track>, dek: SecretKey) {
        currentPlaybackIndex = index
        val track = queue[index]
        _currentPlayingTrackId.value = track.id

        releasePlayer()

        viewModelScope.launch {
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
                            if (nextIndex < queue.size) {
                                playTrackAt(nextIndex, queue, dek)
                            } else {
                                _isPlaying.value = false
                                _currentPlayingTrackId.value = null
                                cleanupTempFile()
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
                    Log.d(TAG, "Riproduzione autore brano ${index + 1}/${queue.size}: ${track.title}")

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
