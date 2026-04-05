package com.example.player.ui.tracklist

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.player.data.local.entity.Track
import com.example.player.data.repository.TrackRepository
import com.example.player.domain.ImportTrackUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackListViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val importTrackUseCase: ImportTrackUseCase
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = trackRepository
        .getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun importTracks(uris: List<Uri>) {
        viewModelScope.launch {
            importTrackUseCase.importAll(uris)
        }
    }
}
