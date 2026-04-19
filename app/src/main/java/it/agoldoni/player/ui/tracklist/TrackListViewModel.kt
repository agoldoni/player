package it.agoldoni.player.ui.tracklist

import android.net.Uri
import android.util.Log
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.CsvExportUseCase
import it.agoldoni.player.domain.ImportTrackUseCase
import it.agoldoni.player.domain.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.crypto.Cipher
import javax.inject.Inject

private const val TAG = "TrackListVM"

sealed class TrackListEvent {
    /** Richiesta biometrica solo per il primo setup della DEK (primo import in assoluto). */
    data class RequestBiometricAuth(
        val cryptoObject: BiometricPrompt.CryptoObject,
        val isSetup: Boolean
    ) : TrackListEvent()

    data class ShowError(val message: String) : TrackListEvent()
    data class ShareCsvFile(val file: File) : TrackListEvent()
}

@HiltViewModel
class TrackListViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val importTrackUseCase: ImportTrackUseCase,
    private val cryptoManager: CryptoManager,
    private val csvExportUseCase: CsvExportUseCase,
    private val playbackManager: PlaybackManager
) : ViewModel() {

    val tracks: StateFlow<List<Track>> = trackRepository
        .getAllTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val playingTrackId: StateFlow<String?> = playbackManager.currentTrackId

    private val _events = Channel<TrackListEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pendingImportUris: List<Uri>? = null

    fun importTracks(uris: List<Uri>) {
        viewModelScope.launch {
            val dek = cryptoManager.sessionDek
            if (dek != null) {
                // DEK già sbloccata (dal gate biometrico all'avvio)
                val count = importTrackUseCase.importAll(uris, dek)
                Log.d(TAG, "Importate $count tracce")
                return@launch
            }

            // Primo utilizzo in assoluto: la DEK non esiste ancora,
            // serve autenticazione biometrica per crearla
            pendingImportUris = uris
            try {
                val (cipher, isSetup) = cryptoManager.prepareBiometricCipher()
                _events.send(
                    TrackListEvent.RequestBiometricAuth(
                        BiometricPrompt.CryptoObject(cipher), isSetup
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Errore preparazione cipher biometrico", e)
                _events.send(TrackListEvent.ShowError("Errore di autenticazione biometrica"))
            }
        }
    }

    fun onBiometricSuccess(cipher: Cipher, isSetup: Boolean) {
        viewModelScope.launch {
            try {
                val dek = cryptoManager.obtainDek(cipher, isSetup)
                val uris = pendingImportUris ?: return@launch
                pendingImportUris = null
                val count = importTrackUseCase.importAll(uris, dek)
                Log.d(TAG, "Importate $count tracce")
            } catch (e: Exception) {
                Log.e(TAG, "Errore durante importazione", e)
                _events.send(TrackListEvent.ShowError("Errore durante l'importazione"))
            }
        }
    }

    fun onBiometricError(message: String) {
        pendingImportUris = null
        Log.w(TAG, "Autenticazione biometrica fallita: $message")
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            if (playbackManager.currentTrackId.value == track.id) {
                playbackManager.stop()
            }
            trackRepository.deleteTrack(track)
        }
    }

    fun togglePlayTrack(track: Track) {
        if (playbackManager.currentTrackId.value == track.id) {
            playbackManager.stop()
            return
        }
        val started = playbackManager.play(track)
        if (!started) {
            viewModelScope.launch {
                _events.send(TrackListEvent.ShowError("Sessione scaduta, riavvia l'app"))
            }
        }
    }

    fun exportCsv() {
        viewModelScope.launch {
            try {
                val file = csvExportUseCase()
                _events.send(TrackListEvent.ShareCsvFile(file))
            } catch (e: Exception) {
                Log.e(TAG, "Errore esportazione CSV", e)
                _events.send(TrackListEvent.ShowError("Errore durante l'esportazione CSV"))
            }
        }
    }
}
