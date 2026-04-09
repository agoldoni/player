package it.agoldoni.player.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.repository.PlaylistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class PlaylistListEvent {
    data class ShowError(val message: String) : PlaylistListEvent()
}

@HiltViewModel
class PlaylistListViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository
        .getAllPlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _events = Channel<PlaylistListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            val trimmed = name.trim()
            if (trimmed.isBlank()) {
                _events.send(PlaylistListEvent.ShowError("Il nome della playlist non può essere vuoto"))
                return@launch
            }
            val existing = playlistRepository.getPlaylistByName(trimmed)
            if (existing != null) {
                _events.send(PlaylistListEvent.ShowError("Esiste già una playlist con questo nome"))
                return@launch
            }
            playlistRepository.insertPlaylist(Playlist(name = trimmed))
        }
    }

    fun renamePlaylist(playlist: Playlist, newName: String) {
        viewModelScope.launch {
            val trimmed = newName.trim()
            if (trimmed.isBlank()) {
                _events.send(PlaylistListEvent.ShowError("Il nome della playlist non può essere vuoto"))
                return@launch
            }
            val existing = playlistRepository.getPlaylistByName(trimmed)
            if (existing != null && existing.id != playlist.id) {
                _events.send(PlaylistListEvent.ShowError("Esiste già una playlist con questo nome"))
                return@launch
            }
            playlistRepository.updatePlaylist(playlist.copy(name = trimmed))
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            playlistRepository.deletePlaylist(playlist)
        }
    }
}
