package it.agoldoni.player.ui.stats

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.repository.TrackRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class StatsViewModel @Inject constructor(
    application: Application,
    trackRepository: TrackRepository
) : ViewModel() {

    private val tracksDir = java.io.File(application.filesDir, "tracks").apply { mkdirs() }

    val trackCount: StateFlow<Int> = trackRepository.getTrackCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val totalDuration: StateFlow<Long> = trackRepository.getTotalDuration()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalOriginalFileSize: StateFlow<Long> = trackRepository.getTotalOriginalFileSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val totalEncryptedFileSize: StateFlow<Long> = trackRepository.getTotalEncryptedFileSize()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val freeSpace: StateFlow<Long> = trackRepository.getTrackCount()
        .map { computeFreeSpace() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), computeFreeSpace())

    private fun computeFreeSpace(): Long {
        val stat = StatFs(tracksDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    val albumCount: StateFlow<Int> = trackRepository.getAlbumCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val artistCount: StateFlow<Int> = trackRepository.getArtistCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
