package it.agoldoni.player.ui.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.domain.webdav.WebDavServer
import it.agoldoni.player.domain.webdav.WebDavServerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WebDavExportViewModel @Inject constructor(
    private val webDavServer: WebDavServer
) : ViewModel() {

    val state: StateFlow<WebDavServerState> = webDavServer.state

    /**
     * A differenza dell'upload Wi-Fi l'avvio è sospeso: prima di aprire la porta
     * il server legge la libreria per pubblicare quanti brani e quanti byte sta
     * esponendo.
     */
    fun start() {
        viewModelScope.launch { webDavServer.start() }
    }

    fun stop() = webDavServer.stop()

    override fun onCleared() {
        webDavServer.stop()
        super.onCleared()
    }
}
