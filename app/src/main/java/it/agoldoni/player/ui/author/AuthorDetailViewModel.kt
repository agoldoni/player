package it.agoldoni.player.ui.author

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.PlaybackManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthorDetailEvent {
    data class ShowError(val message: String) : AuthorDetailEvent()
}

@HiltViewModel
class AuthorDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    trackRepository: TrackRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    val artistName: String = Uri.decode(savedStateHandle["artistName"]!!)

    val tracks: StateFlow<List<Track>> = trackRepository
        .getTracksByArtist(artistName)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _events = Channel<AuthorDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _playbackOrder = MutableStateFlow<List<Track>>(emptyList())
    private var playbackOrder: List<Track>
        get() = _playbackOrder.value
        set(value) { _playbackOrder.value = value }
    private var currentPlaybackIndex: Int = -1

    private val ownsCurrentPlayback: StateFlow<Boolean> = combine(
        playbackManager.currentTrackId,
        _playbackOrder
    ) { id, order ->
        id != null && order.any { it.id == id }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false
    )

    val currentPlayingTrackId: StateFlow<String?> = combine(
        playbackManager.currentTrackId,
        ownsCurrentPlayback
    ) { id, owned -> if (owned) id else null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val isPlaying: StateFlow<Boolean> = combine(
        playbackManager.isPlaying,
        ownsCurrentPlayback
    ) { playing, owned -> playing && owned }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    fun toggleShuffle() {
        _shuffleEnabled.value = !_shuffleEnabled.value
    }

    fun togglePlayback() {
        if (ownsCurrentPlayback.value) {
            if (playbackManager.isPlaying.value) playbackManager.pause() else playbackManager.resume()
            return
        }

        val current = tracks.value
        if (current.isEmpty()) return

        playbackOrder = if (_shuffleEnabled.value) current.shuffled() else current
        playTrackAt(0)
    }

    fun skipToNext() {
        if (!ownsCurrentPlayback.value || playbackOrder.isEmpty() || currentPlaybackIndex < 0) return

        val nextIndex = currentPlaybackIndex + 1
        if (nextIndex >= playbackOrder.size) {
            if (_shuffleEnabled.value) playbackOrder = playbackOrder.shuffled()
            playTrackAt(0)
        } else {
            playTrackAt(nextIndex)
        }
    }

    private fun playTrackAt(index: Int) {
        val track = playbackOrder[index]
        currentPlaybackIndex = index

        val started = playbackManager.play(track) {
            val next = index + 1
            if (next < playbackOrder.size) {
                playTrackAt(next)
            } else {
                currentPlaybackIndex = -1
            }
        }
        if (!started) {
            viewModelScope.launch {
                _events.send(AuthorDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
        }
    }
}
