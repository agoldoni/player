package it.agoldoni.player.ui.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.local.entity.ArtistSummary
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.PlaybackManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthorListViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    val artists: StateFlow<List<ArtistSummary>> = trackRepository
        .getDistinctArtistsWithCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /**
     * Elimina tutti i brani dell'autore. Una volta rimosse tutte le tracce, l'autore
     * sparisce automaticamente dalla lista (è derivato via GROUP BY artist) e i file
     * cifrati orfani vengono ripuliti alla prossima startup da OrphanCleanupUseCase.
     */
    fun deleteAuthor(artistName: String) {
        viewModelScope.launch {
            val currentId = playbackManager.currentTrackId.value
            if (currentId != null) {
                val current = trackRepository.getTrackById(currentId)
                val belongsToAuthor = current != null && if (artistName == TrackDao.UNKNOWN_ARTIST) {
                    current.artist.isBlank()
                } else {
                    current.artist == artistName
                }
                if (belongsToAuthor) playbackManager.stop()
            }
            trackRepository.deleteTracksByArtist(artistName)
        }
    }
}
