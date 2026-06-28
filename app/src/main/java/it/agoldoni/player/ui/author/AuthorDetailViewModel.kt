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
    private val trackRepository: TrackRepository,
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

    /** Tag della coda avviata da questa schermata (i brani di questo autore). */
    private val ownerTag = "author:$artistName"

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled

    private val _events = Channel<AuthorDetailEvent>(Channel.BUFFERED)
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
        val enabled = !_shuffleEnabled.value
        _shuffleEnabled.value = enabled
        if (ownsCurrentPlayback.value) playbackManager.setShuffle(enabled)
    }

    fun togglePlayback() {
        if (ownsCurrentPlayback.value) {
            if (playbackManager.isPlaying.value) playbackManager.pause() else playbackManager.resume()
            return
        }

        val source = tracks.value
        if (source.isEmpty()) return

        val started = playbackManager.playQueue(ownerTag, source, null, _shuffleEnabled.value)
        if (!started) sendSessionExpired()
    }

    fun skipToNext() {
        if (ownsCurrentPlayback.value) playbackManager.skipToNext()
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            if (playbackManager.currentTrackId.value == track.id) {
                playbackManager.stop()
            }
            trackRepository.deleteTrack(track)
        }
    }

    private fun sendSessionExpired() {
        viewModelScope.launch {
            _events.send(AuthorDetailEvent.ShowError("Sessione scaduta, riavvia l'app"))
        }
    }
}
