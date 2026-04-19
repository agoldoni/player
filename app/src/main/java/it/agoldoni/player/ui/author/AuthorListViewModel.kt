package it.agoldoni.player.ui.author

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.entity.ArtistSummary
import it.agoldoni.player.data.repository.TrackRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AuthorListViewModel @Inject constructor(
    trackRepository: TrackRepository
) : ViewModel() {

    val artists: StateFlow<List<ArtistSummary>> = trackRepository
        .getDistinctArtistsWithCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
}
