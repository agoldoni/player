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

    /** Tag della coda avviata da questa schermata (questa playlist). */
    private val ownerTag = "playlist:$playlistId"

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _events = Channel<PlaylistDetailEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val ownsCurrentPlayback: StateFlow<Boolean> = combine(
        playbackManager.currentTrackId,
        playbackManager.ownerTag
    ) { id, tag ->
        id != null && tag == ownerTag
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

    init {
        // Persiste il brano in riproduzione come "ultimo riprodotto" della playlist,
        // anche quando l'avanzamento avviene dal service (notifica/lock screen).
        // A fine riproduzione (la coda non è più nostra) resetta all'inizio della playlist.
        viewModelScope.launch {
            var wasOwning = false
            combine(playbackManager.ownerTag, playbackManager.currentTrackId) { tag, id -> tag to id }
                .collect { (tag, id) ->
                    val owning = tag == ownerTag && id != null
                    if (owning) {
                        wasOwning = true
                        playlistRepository.updateLastPlayedTrackId(playlistId, id!!)
                    } else if (wasOwning) {
                        wasOwning = false
                        playlistWithTracks.value?.tracks?.firstOrNull()?.let {
                            playlistRepository.updateLastPlayedTrackId(playlistId, it.id)
                        }
                    }
                }
        }
    }

    fun toggleShuffle() {
        val enabled = !_shuffleEnabled.value
        _shuffleEnabled.value = enabled
        if (ownsCurrentPlayback.value) playbackManager.setShuffle(enabled)
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

        // Senza shuffle si riparte dall'ultimo brano riprodotto della playlist, se presente.
        val startTrackId = if (!_shuffleEnabled.value) data.playlist.lastPlayedTrackId else null

        val started = playbackManager.playQueue(ownerTag, tracks, startTrackId, _shuffleEnabled.value)
        if (!started) {
            viewModelScope.launch {
                _events.send(PlaylistDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
        }
    }

    fun skipToNext() {
        if (ownsCurrentPlayback.value) playbackManager.skipToNext()
    }
}
