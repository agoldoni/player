package it.agoldoni.player.ui.upload

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.domain.upload.UploadServer
import it.agoldoni.player.domain.upload.UploadServerState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class WifiUploadViewModel @Inject constructor(
    private val uploadServer: UploadServer
) : ViewModel() {

    val state: StateFlow<UploadServerState> = uploadServer.state

    fun start() = uploadServer.start()

    fun stop() = uploadServer.stop()

    override fun onCleared() {
        uploadServer.stop()
        super.onCleared()
    }
}
