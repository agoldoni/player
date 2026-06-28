package it.agoldoni.player.ui.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.PlaybackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaybackBarUiState(
    val visible: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0
)

@HiltViewModel
class PlaybackBarViewModel @Inject constructor(
    private val playbackManager: PlaybackManager,
    private val trackRepository: TrackRepository
) : ViewModel() {

    private val currentTrack = MutableStateFlow<Track?>(null)

    init {
        viewModelScope.launch {
            playbackManager.currentTrackId.collect { id ->
                currentTrack.value = id?.let { trackRepository.getTrackById(it) }
            }
        }
    }

    val uiState: StateFlow<PlaybackBarUiState> = combine(
        currentTrack,
        playbackManager.isPlaying,
        playbackManager.positionMs,
        playbackManager.durationMs
    ) { track, playing, position, duration ->
        if (track == null) {
            PlaybackBarUiState(visible = false)
        } else {
            PlaybackBarUiState(
                visible = true,
                title = track.title,
                artist = track.artist,
                isPlaying = playing,
                positionMs = position,
                durationMs = duration
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaybackBarUiState()
    )

    fun togglePlayPause() {
        if (playbackManager.currentTrackId.value == null) return
        if (playbackManager.isPlaying.value) playbackManager.pause() else playbackManager.resume()
    }

    fun seekTo(positionMs: Int) {
        playbackManager.seekTo(positionMs)
    }

    fun skipToNext() {
        playbackManager.skipToNext()
    }
}
