package it.agoldoni.player.ui.transfer

import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.transfer.PeerDiscovery
import it.agoldoni.player.domain.transfer.ReceiveLibraryUseCase
import it.agoldoni.player.domain.transfer.TransferProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.crypto.Cipher
import javax.inject.Inject

private const val TAG = "ReceiveLibraryVM"

sealed class ReceiveLibraryEvent {
    data class RequestBiometricAuth(
        val cryptoObject: BiometricPrompt.CryptoObject,
        val isSetup: Boolean
    ) : ReceiveLibraryEvent()

    data class ShowError(val message: String) : ReceiveLibraryEvent()
}

/**
 * Lato destinatario. La ricezione è un'operazione con inizio e fine, quindi
 * segue il modello di `FtpSyncViewModel`: un Flow raccolto in un [Job]
 * cancellabile, con lo stato corrente esposto come [StateFlow].
 *
 * Caso tipico di questa feature: il telefono che riceve è **appena installato**
 * e la sua libreria è vuota, quindi la DEK non esiste ancora e il gate biometrico
 * all'avvio non è mai comparso. Prima di collegarsi si chiede allora
 * l'autenticazione, che crea la chiave — stesso schema dell'import da file
 * (`TrackListViewModel`).
 */
@HiltViewModel
class ReceiveLibraryViewModel @Inject constructor(
    private val receiveLibraryUseCase: ReceiveLibraryUseCase,
    private val peerDiscovery: PeerDiscovery,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _progress = MutableStateFlow<TransferProgress>(TransferProgress.Idle)
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private val _events = Channel<ReceiveLibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val peers: StateFlow<List<PeerDiscovery.Peer>> = peerDiscovery.peers
    val discoveryError: StateFlow<String?> = peerDiscovery.discoveryError

    private var currentJob: Job? = null
    private var pendingConnection: Triple<String, Int, String>? = null

    fun startDiscovery() = peerDiscovery.startDiscovery()

    fun stopDiscovery() = peerDiscovery.stopDiscovery()

    fun connect(host: String, port: Int, token: String) {
        if (currentJob?.isActive == true) return

        if (cryptoManager.sessionDek != null) {
            startTransfer(host, port, token)
            return
        }

        // Chiave non ancora sbloccata (o mai creata): serve l'autenticazione.
        pendingConnection = Triple(host, port, token)
        viewModelScope.launch {
            try {
                val (cipher, isSetup) = cryptoManager.prepareBiometricCipher()
                _events.send(
                    ReceiveLibraryEvent.RequestBiometricAuth(
                        BiometricPrompt.CryptoObject(cipher), isSetup
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Errore preparazione cipher biometrico", e)
                pendingConnection = null
                _events.send(ReceiveLibraryEvent.ShowError("Errore di autenticazione biometrica"))
            }
        }
    }

    fun onBiometricSuccess(cipher: Cipher, isSetup: Boolean) {
        val pending = pendingConnection ?: return
        pendingConnection = null
        viewModelScope.launch {
            try {
                cryptoManager.obtainDek(cipher, isSetup)
            } catch (e: Exception) {
                Log.e(TAG, "Errore sblocco della chiave", e)
                _events.send(ReceiveLibraryEvent.ShowError("Errore durante lo sblocco della chiave"))
                return@launch
            }
            val (host, port, token) = pending
            startTransfer(host, port, token)
        }
    }

    fun onBiometricError(message: String) {
        pendingConnection = null
        Log.w(TAG, "Autenticazione biometrica fallita: $message")
        viewModelScope.launch {
            _events.send(ReceiveLibraryEvent.ShowError("Autenticazione annullata: $message"))
        }
    }

    private fun startTransfer(host: String, port: Int, token: String) {
        _progress.value = TransferProgress.Connecting
        currentJob = viewModelScope.launch {
            receiveLibraryUseCase(host, port, token).collect { state ->
                _progress.value = state
            }
        }
    }

    fun confirm() = receiveLibraryUseCase.confirm()

    fun rejectPairing() {
        receiveLibraryUseCase.cancelPairing()
    }

    fun cancel() {
        currentJob?.cancel()
    }

    fun reset() {
        if (isRunning) return
        _progress.value = TransferProgress.Idle
    }

    val isRunning: Boolean
        get() = when (_progress.value) {
            TransferProgress.Connecting,
            TransferProgress.WaitingForSender,
            is TransferProgress.AwaitingConfirmation,
            is TransferProgress.Importing -> true
            else -> false
        }

    override fun onCleared() {
        currentJob?.cancel()
        peerDiscovery.stopDiscovery()
        super.onCleared()
    }
}
