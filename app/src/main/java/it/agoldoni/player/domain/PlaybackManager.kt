package it.agoldoni.player.domain

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.domain.playback.PlaybackQueue
import it.agoldoni.player.domain.playback.PlaybackService
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Façade di riproduzione usato dai ViewModel.
 *
 * Internamente delega a [PlaybackService] tramite un [MediaController]: il service
 * possiede ExoPlayer e la MediaSession, garantendo riproduzione in background e
 * controlli su lock screen/notifica. La coda è in [PlaybackQueue] (singleton condiviso
 * col service): qui si imposta e di lì il service la fa avanzare anche dai comandi di
 * sistema.
 *
 * L'API pubblica (StateFlow + comandi) è stata mantenuta vicina a quella precedente
 * per limitare le modifiche ai ViewModel. La connessione del [MediaController] è
 * asincrona: i comandi richiesti prima della connessione vengono accodati in [pending].
 */
@UnstableApi
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val queue: PlaybackQueue
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var positionPollJob: Job? = null

    /** Id del brano corrente (dalla coda), null se nessuna riproduzione. */
    val currentTrackId: StateFlow<String?> = queue.currentTrackId

    /** Tag della schermata che possiede la coda corrente (vedi [PlaybackQueue]). */
    val ownerTag: StateFlow<String?> = queue.ownerTag

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _durationMs = MutableStateFlow(0)
    val durationMs: StateFlow<Int> = _durationMs.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            syncFromController(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else pending.add(block)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionPolling() else positionPollJob?.cancel()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controller?.let { updateDuration(it) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            controller?.let { updateDuration(it); _positionMs.value = it.currentPosition.toInt().coerceAtLeast(0) }
        }
    }

    private fun syncFromController(c: MediaController) {
        _isPlaying.value = c.isPlaying
        updateDuration(c)
        _positionMs.value = c.currentPosition.toInt().coerceAtLeast(0)
        if (c.isPlaying) startPositionPolling()
    }

    private fun updateDuration(c: MediaController) {
        val d = c.duration
        _durationMs.value = if (d > 0) d.toInt() else 0
    }

    /**
     * Avvia la riproduzione di una coda originata da [ownerTag].
     * @return false se la sessione biometrica è scaduta (DEK non disponibile).
     */
    fun playQueue(ownerTag: String, source: List<Track>, startTrackId: String?, shuffle: Boolean): Boolean {
        if (cryptoManager.sessionDek == null) return false
        if (source.isEmpty()) return true
        queue.setQueue(ownerTag, source, startTrackId, shuffle)
        withController { it.sendCustomCommand(SessionCommand(PlaybackService.CMD_PLAY_CURRENT, Bundle.EMPTY), Bundle.EMPTY) }
        return true
    }

    /** Riproduce un singolo brano (coda di un elemento). */
    fun playSingle(track: Track): Boolean =
        playQueue("single:${track.id}", listOf(track), track.id, shuffle = false)

    fun pause() = withController { it.pause() }

    fun resume() = withController { it.play() }

    fun stop() {
        queue.clear()
        withController { it.sendCustomCommand(SessionCommand(PlaybackService.CMD_STOP, Bundle.EMPTY), Bundle.EMPTY) }
    }

    fun seekTo(positionMs: Int) {
        _positionMs.value = positionMs.coerceAtLeast(0)
        withController { it.seekTo(positionMs.toLong().coerceAtLeast(0)) }
    }

    /** Avanza al brano successivo della coda (con wrap a fine coda). */
    fun skipToNext() = withController { it.seekToNext() }

    /** Riordina la coda corrente in base allo shuffle, mantenendo il brano in riproduzione. */
    fun setShuffle(enabled: Boolean) = queue.setShuffle(enabled)

    private fun startPositionPolling() {
        positionPollJob?.cancel()
        positionPollJob = scope.launch {
            while (isActive) {
                val c = controller ?: break
                if (c.isPlaying) {
                    _positionMs.value = c.currentPosition.toInt().coerceAtLeast(0)
                }
                delay(500)
            }
        }
    }
}
