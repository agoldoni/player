package it.agoldoni.player.ui.ftp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.repository.FtpConfigRepository
import it.agoldoni.player.domain.ftp.SyncFromFtpUseCase
import it.agoldoni.player.domain.ftp.SyncProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FtpSyncViewModel @Inject constructor(
    private val syncFromFtpUseCase: SyncFromFtpUseCase,
    private val ftpConfigRepository: FtpConfigRepository
) : ViewModel() {

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val _hasConfig = MutableStateFlow<Boolean?>(null)
    val hasConfig: StateFlow<Boolean?> = _hasConfig.asStateFlow()

    private var currentJob: Job? = null

    init {
        viewModelScope.launch {
            _hasConfig.value = ftpConfigRepository.getConfig() != null
        }
    }

    fun refreshConfigStatus() {
        viewModelScope.launch {
            _hasConfig.value = ftpConfigRepository.getConfig() != null
        }
    }

    fun start() {
        if (currentJob?.isActive == true) return
        _progress.value = SyncProgress.Connecting
        currentJob = viewModelScope.launch {
            syncFromFtpUseCase().collect { state ->
                _progress.value = state
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
    }

    val isRunning: Boolean
        get() = when (_progress.value) {
            SyncProgress.Connecting, SyncProgress.Scanning, is SyncProgress.Importing -> true
            else -> false
        }

    fun reset() {
        if (isRunning) return
        _progress.value = SyncProgress.Idle
    }

    override fun onCleared() {
        currentJob?.cancel()
        super.onCleared()
    }
}
