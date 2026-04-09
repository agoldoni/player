package it.agoldoni.player.ui.trackdetail

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey
import javax.inject.Inject

private const val TAG = "TrackDetailVM"

sealed class TrackDetailEvent {
    data class ShowError(val message: String) : TrackDetailEvent()
}

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val trackRepository: TrackRepository,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val trackId: String = checkNotNull(savedStateHandle["trackId"])

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _events = Channel<TrackDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tempPlaybackFile: File? = null

    init {
        viewModelScope.launch {
            _track.value = trackRepository.getTrackById(trackId)
        }
    }

    fun togglePlayback() {
        val player = mediaPlayer
        if (player == null) {
            val dek = cryptoManager.sessionDek
            if (dek != null) {
                val uri = _track.value?.uri ?: return
                startPlayback(uri, dek)
            } else {
                viewModelScope.launch {
                    _events.send(TrackDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
                }
            }
        } else if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.start()
            _isPlaying.value = true
        }
    }

    private fun startPlayback(encryptedPath: String, dek: SecretKey) {
        mediaPlayer?.release()
        mediaPlayer = null

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val encryptedFile = File(encryptedPath)
                    val tempFile = cryptoManager.decryptToTempFile(dek, encryptedFile)
                    tempPlaybackFile = tempFile

                    Log.d(TAG, "File decifrato in: ${tempFile.absolutePath}")

                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            _isPlaying.value = false
                            cleanupTempFile()
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                            _isPlaying.value = false
                            cleanupTempFile()
                            false
                        }
                        prepare()
                    }

                    mediaPlayer = player
                    player.start()
                    _isPlaying.value = true
                    Log.d(TAG, "Riproduzione avviata: $encryptedPath")

                } catch (e: Exception) {
                    Log.e(TAG, "startPlayback fallito: $encryptedPath", e)
                    _isPlaying.value = false
                    cleanupTempFile()
                }
            }
        }
    }

    private fun cleanupTempFile() {
        tempPlaybackFile?.delete()
        tempPlaybackFile = null
    }

    override fun onCleared() {
        mediaPlayer?.release()
        mediaPlayer = null
        cleanupTempFile()
        super.onCleared()
    }
}
