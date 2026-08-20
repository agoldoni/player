package it.agoldoni.player.ui.transfer

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.PlaylistRepository
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.transfer.TransferSelection
import it.agoldoni.player.domain.transfer.TransferServer
import it.agoldoni.player.domain.transfer.TransferServerState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

private const val TAG = "SendLibraryVM"

sealed class SendLibraryEvent {
    data class RequestBiometricAuth(
        val cryptoObject: BiometricPrompt.CryptoObject,
        val isSetup: Boolean
    ) : SendLibraryEvent()

    data class ShowError(val message: String) : SendLibraryEvent()
}

/**
 * Lato mittente. Il server è un singleton: la schermata lo avvia entrando e lo
 * ferma uscendo, come già fa `WifiUploadViewModel` con `UploadServer`.
 *
 * Come per la ricezione, se la DEK non è sbloccata si chiede l'autenticazione
 * prima di partire invece di fallire: il server deve decifrare i brani per
 * ricifrarli con la chiave di sessione.
 */
@HiltViewModel
class SendLibraryViewModel @Inject constructor(
    private val transferServer: TransferServer,
    private val cryptoManager: CryptoManager,
    trackRepository: TrackRepository,
    playlistRepository: PlaylistRepository
) : ViewModel() {

    val state: StateFlow<TransferServerState> = transferServer.state

    private val _events = Channel<SendLibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<Track>> = trackRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var pendingSelection: TransferSelection? = null

    fun start(selection: TransferSelection) {
        if (cryptoManager.sessionDek != null) {
            viewModelScope.launch { transferServer.start(selection) }
            return
        }

        pendingSelection = selection
        viewModelScope.launch {
            try {
                val (cipher, isSetup) = cryptoManager.prepareBiometricCipher()
                _events.send(
                    SendLibraryEvent.RequestBiometricAuth(
                        BiometricPrompt.CryptoObject(cipher), isSetup
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Errore preparazione cipher biometrico", e)
                pendingSelection = null
                _events.send(SendLibraryEvent.ShowError("Errore di autenticazione biometrica"))
            }
        }
    }

    fun onBiometricSuccess(cipher: Cipher, isSetup: Boolean) {
        val selection = pendingSelection ?: return
        pendingSelection = null
        viewModelScope.launch {
            try {
                cryptoManager.obtainDek(cipher, isSetup)
            } catch (e: Exception) {
                Log.e(TAG, "Errore sblocco della chiave", e)
                _events.send(SendLibraryEvent.ShowError("Errore durante lo sblocco della chiave"))
                return@launch
            }
            transferServer.start(selection)
        }
    }

    fun onBiometricError(message: String) {
        pendingSelection = null
        Log.w(TAG, "Autenticazione biometrica fallita: $message")
        viewModelScope.launch {
            _events.send(SendLibraryEvent.ShowError("Autenticazione annullata: $message"))
        }
    }

    fun confirm() = transferServer.confirm()

    fun reject() = transferServer.reject()

    fun stop() = transferServer.stop()

    override fun onCleared() {
        transferServer.stop()
        super.onCleared()
    }
}
