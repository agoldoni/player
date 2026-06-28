package it.agoldoni.player.ui.trackdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class TrackDetailEvent {
    data class ShowError(val message: String) : TrackDetailEvent()
}

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val trackRepository: TrackRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    private val trackId: String = checkNotNull(savedStateHandle["trackId"])

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    val isPlaying: StateFlow<Boolean> = combine(
        playbackManager.currentTrackId,
        playbackManager.isPlaying
    ) { currentId, playing ->
        currentId == trackId && playing
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    private val _events = Channel<TrackDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            _track.value = trackRepository.getTrackById(trackId)
        }
    }

    fun togglePlayback() {
        val track = _track.value ?: return
        if (playbackManager.currentTrackId.value == track.id) {
            if (playbackManager.isPlaying.value) playbackManager.pause() else playbackManager.resume()
            return
        }
        val started = playbackManager.playSingle(track)
        if (!started) {
            viewModelScope.launch {
                _events.send(TrackDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
        }
    }
}
