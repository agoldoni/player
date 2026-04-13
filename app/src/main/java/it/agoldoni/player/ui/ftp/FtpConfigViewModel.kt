package it.agoldoni.player.ui.ftp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import it.agoldoni.player.data.repository.FtpConfigRepository
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.ftp.FtpClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "FtpConfigVM"
private const val PASSWORD_PLACEHOLDER = "••••••••"

data class FtpConfigUiState(
    val host: String = "",
    val port: String = "21",
    val username: String = "",
    val password: String = "",
    val rootPath: String = "/",
    val isLoaded: Boolean = false,
    val hasStoredPassword: Boolean = false,
    val isTesting: Boolean = false,
    val isSaving: Boolean = false
) {
    val hostError: String? = if (host.isNotBlank() || !isLoaded) null else "Obbligatorio"
    val portError: String? = run {
        val n = port.toIntOrNull()
        if (n == null || n !in 1..65535) "Porta 1–65535" else null
    }
    val usernameError: String? = if (username.isNotBlank() || !isLoaded) null else "Obbligatorio"
    val canSubmit: Boolean = host.isNotBlank() &&
        portError == null &&
        username.isNotBlank() &&
        (hasStoredPassword || password.isNotEmpty()) &&
        !isTesting && !isSaving
}

sealed class FtpConfigEvent {
    data class ShowMessage(val text: String) : FtpConfigEvent()
    data object Saved : FtpConfigEvent()
}

@HiltViewModel
class FtpConfigViewModel @Inject constructor(
    private val ftpConfigRepository: FtpConfigRepository,
    private val ftpClientFactory: FtpClientFactory,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _state = MutableStateFlow(FtpConfigUiState())
    val state: StateFlow<FtpConfigUiState> = _state.asStateFlow()

    private val _events = Channel<FtpConfigEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch { loadExisting() }
    }

    private suspend fun loadExisting() {
        val stored = ftpConfigRepository.getConfig()
        if (stored == null) {
            _state.update { it.copy(isLoaded = true) }
            return
        }
        _state.update {
            it.copy(
                host = stored.host,
                port = stored.port.toString(),
                username = stored.username,
                password = PASSWORD_PLACEHOLDER,
                rootPath = stored.rootPath,
                isLoaded = true,
                hasStoredPassword = true
            )
        }
    }

    fun onHostChange(value: String) = _state.update { it.copy(host = value) }
    fun onPortChange(value: String) = _state.update { it.copy(port = value.filter(Char::isDigit)) }
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value) }
    fun onPasswordChange(value: String) = _state.update {
        it.copy(password = value, hasStoredPassword = false)
    }
    fun onRootPathChange(value: String) = _state.update { it.copy(rootPath = value) }

    fun testConnection() {
        val snapshot = _state.value
        if (!snapshot.canSubmit && !snapshot.hasStoredPassword) return

        viewModelScope.launch {
            _state.update { it.copy(isTesting = true) }
            val plain = resolvePlainConfig(snapshot)
            if (plain == null) {
                _state.update { it.copy(isTesting = false) }
                _events.send(FtpConfigEvent.ShowMessage("Impossibile leggere la password salvata"))
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = ftpClientFactory.connect(plain)
                    ftpClientFactory.disconnectQuietly(client)
                }
            }
            _state.update { it.copy(isTesting = false) }
            if (result.isSuccess) {
                _events.send(FtpConfigEvent.ShowMessage("Connessione riuscita"))
            } else {
                Log.w(TAG, "Test connessione fallito", result.exceptionOrNull())
                val msg = result.exceptionOrNull()?.message?.take(140) ?: "Errore sconosciuto"
                _events.send(FtpConfigEvent.ShowMessage("Connessione fallita: $msg"))
            }
        }
    }

    fun save() {
        val snapshot = _state.value
        if (!snapshot.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val plain = resolvePlainConfig(snapshot)
            if (plain == null) {
                _state.update { it.copy(isSaving = false) }
                _events.send(FtpConfigEvent.ShowMessage("Riavvia l'app per autenticarti"))
                return@launch
            }
            val dek = cryptoManager.sessionDek
            if (dek == null) {
                _state.update { it.copy(isSaving = false) }
                _events.send(FtpConfigEvent.ShowMessage("Riavvia l'app per autenticarti"))
                return@launch
            }

            val result = runCatching { ftpConfigRepository.save(plain, dek) }
            _state.update { it.copy(isSaving = false) }
            if (result.isSuccess) {
                _state.update {
                    it.copy(password = PASSWORD_PLACEHOLDER, hasStoredPassword = true)
                }
                _events.send(FtpConfigEvent.Saved)
                _events.send(FtpConfigEvent.ShowMessage("Configurazione salvata"))
            } else {
                Log.e(TAG, "Errore salvataggio config", result.exceptionOrNull())
                _events.send(FtpConfigEvent.ShowMessage("Errore durante il salvataggio"))
            }
        }
    }

    /**
     * Risolve la configurazione in chiaro partendo dallo stato UI: se la password
     * è ancora il placeholder (utente non l'ha modificata), recupera quella salvata.
     */
    private suspend fun resolvePlainConfig(state: FtpConfigUiState): FtpConfigRepository.PlainConfig? {
        val port = state.port.toIntOrNull() ?: return null
        val dek = cryptoManager.sessionDek ?: return null

        val password = if (state.hasStoredPassword && state.password == PASSWORD_PLACEHOLDER) {
            val stored = ftpConfigRepository.getPlainConfig(dek) ?: return null
            stored.password
        } else {
            state.password
        }

        return FtpConfigRepository.PlainConfig(
            host = state.host.trim(),
            port = port,
            username = state.username.trim(),
            password = password,
            rootPath = state.rootPath.ifBlank { "/" }
        )
    }
}
