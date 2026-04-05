package com.example.player.ui.trackdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.player.data.local.entity.Track
import com.example.player.data.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val trackRepository: TrackRepository
) : ViewModel() {

    private val trackId: String = checkNotNull(savedStateHandle["trackId"])

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track

    init {
        viewModelScope.launch {
            _track.value = trackRepository.getTrackById(trackId)
        }
    }
}
