package it.agoldoni.player.ui.playlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.PlaylistWithTracks
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.PlaylistRepository
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PlaylistDetailEvent {
    data class ShowError(val message: String) : PlaylistDetailEvent()
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val playlistRepository: PlaylistRepository,
    private val trackRepository: TrackRepository,
    private val playbackManager: PlaybackManager
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

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
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

    /** Id del brano in riproduzione se appartiene a questa playlist, altrimenti null. */
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
        if (ownsCurrentPlayback.value) {
            if (playbackManager.isPlaying.value) playbackManager.pause() else playbackManager.resume()
            return
        }

        val data = playlistWithTracks.value ?: return
        val tracks = data.tracks
        if (tracks.isEmpty()) return

        playbackOrder = if (_shuffleEnabled.value) tracks.shuffled() else tracks

        val lastPlayedId = data.playlist.lastPlayedTrackId
        val startIndex = if (!_shuffleEnabled.value && lastPlayedId != null) {
            val idx = playbackOrder.indexOfFirst { it.id == lastPlayedId }
            if (idx >= 0) idx else 0
        } else {
            0
        }

        playTrackAt(startIndex)
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

        viewModelScope.launch {
            playlistRepository.updateLastPlayedTrackId(playlistId, track.id)
        }

        val started = playbackManager.play(track) {
            // onCompletion naturale: prossimo brano oppure fine playlist
            val next = index + 1
            if (next < playbackOrder.size) {
                playTrackAt(next)
            } else {
                currentPlaybackIndex = -1
                val firstId = playbackOrder.firstOrNull()?.id
                if (firstId != null) {
                    viewModelScope.launch {
                        playlistRepository.updateLastPlayedTrackId(playlistId, firstId)
                    }
                }
            }
        }
        if (!started) {
            viewModelScope.launch {
                _events.send(PlaylistDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
        }
    }
}
