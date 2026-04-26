package it.agoldoni.player.domain

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import it.agoldoni.player.data.local.entity.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PlaybackManager"

/**
 * Coordinatore unico della riproduzione audio: garantisce che un solo brano suoni
 * contemporaneamente su tutte le schermate (lista, dettaglio, playlist).
 */
@Singleton
class PlaybackManager @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentTrackId = MutableStateFlow<String?>(null)
    val currentTrackId: StateFlow<String?> = _currentTrackId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null
    private var generation = 0
    private var positionPollJob: Job? = null

    /**
     * Avvia la riproduzione di [track]. Ferma qualunque brano in corso.
     * [onCompletion] è invocato al termine naturale del brano (non quando
     * viene fermato perché ne parte un altro). Ritorna false se la sessione
     * biometrica è scaduta.
     */
    fun play(track: Track, onCompletion: () -> Unit = {}): Boolean {
        val dek = cryptoManager.sessionDek ?: return false
        stop()
        val myGen = ++generation
        _currentTrackId.value = track.id
        scope.launch {
            withContext(Dispatchers.IO) {
                var localPlayer: MediaPlayer? = null
                var localTemp: File? = null
                try {
                    localTemp = cryptoManager.decryptToTempFile(dek, File(track.uri), track.originalExtension)
                    localPlayer = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(localTemp.absolutePath)
                        setOnCompletionListener {
                            if (myGen == generation) {
                                cleanup()
                                onCompletion()
                            }
                        }
                        setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                            if (myGen == generation) stop()
                            false
                        }
                        prepare()
                    }
                    if (myGen != generation) {
                        localPlayer.release()
                        localTemp.delete()
                        return@withContext
                    }
                    tempFile = localTemp
                    mediaPlayer = localPlayer
                    _durationMs.value = localPlayer.duration.coerceAtLeast(0)
                    _positionMs.value = 0
                    localPlayer.start()
                    _isPlaying.value = true
                    startPositionPolling(myGen)
                } catch (e: Exception) {
                    Log.e(TAG, "Errore avvio riproduzione: ${track.title}", e)
                    localPlayer?.release()
                    localTemp?.delete()
                    if (myGen == generation) stop()
                }
            }
        }
        return true
    }

    /** Mette in pausa il brano corrente, se presente e in riproduzione. */
    fun pause() {
        val p = mediaPlayer ?: return
        if (p.isPlaying) {
            p.pause()
            _isPlaying.value = false
        }
    }

    /** Riprende la riproduzione se il brano corrente è in pausa. */
    fun resume() {
        val p = mediaPlayer ?: return
        if (!p.isPlaying) {
            p.start()
            _isPlaying.value = true
        }
    }

    /** Ferma e rilascia il player corrente. */
    fun stop() {
        generation++
        cleanup()
    }

    /** Sposta la riproduzione al punto indicato (in ms). No-op se nessun brano in corso. */
    fun seekTo(positionMs: Int) {
        val p = mediaPlayer ?: return
        val clamped = positionMs.coerceIn(0, _durationMs.value.coerceAtLeast(0))
        p.seekTo(clamped)
        _positionMs.value = clamped
    }

    private fun startPositionPolling(myGen: Int) {
        positionPollJob?.cancel()
        positionPollJob = scope.launch {
            while (isActive && myGen == generation) {
                val p = mediaPlayer ?: break
                runCatching {
                    if (p.isPlaying) {
                        _positionMs.value = p.currentPosition.coerceAtLeast(0)
                    }
                }
                delay(500)
            }
        }
    }

    private fun cleanup() {
        positionPollJob?.cancel()
        positionPollJob = null
        mediaPlayer?.release()
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
        _currentTrackId.value = null
        _isPlaying.value = false
        _durationMs.value = 0
        _positionMs.value = 0
    }
}
