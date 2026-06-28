package it.agoldoni.player.domain.playback

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.domain.CryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private const val TAG = "PlaybackService"

/**
 * Servizio di riproduzione media basato su Media3.
 *
 * Possiede l'[ExoPlayer] e la [MediaSession]: tiene viva la riproduzione in background
 * e pubblica i controlli di sistema (notifica MediaStyle e lock screen), gestiti
 * automaticamente da Media3 a partire dai metadati del brano e dai comandi disponibili.
 *
 * I file audio sono cifrati a riposo: ogni brano viene decifrato on-demand in un file
 * temporaneo ([CryptoManager.decryptToTempFile]) che diventa la sorgente del [MediaItem].
 * Si riproduce un solo brano alla volta; l'avanzamento di coda è gestito qui leggendo
 * [PlaybackQueue], non dalla timeline nativa di ExoPlayer.
 *
 * Il pulsante "successivo" di notifica/lock screen è abilitato tramite un
 * [ForwardingPlayer] che dichiara disponibile [Player.COMMAND_SEEK_TO_NEXT] e instrada
 * `seekToNext()` sull'avanzamento di coda.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var queue: PlaybackQueue

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTempFile: File? = null
    private var prepareJob: Job? = null

    companion object {
        /** Comando custom: decifra e riproduci il brano corrente di [PlaybackQueue]. */
        const val CMD_PLAY_CURRENT = "it.agoldoni.player.PLAY_CURRENT"
        /** Comando custom: avanza al brano successivo (con wrap a fine coda). */
        const val CMD_SKIP_NEXT = "it.agoldoni.player.SKIP_NEXT"
        /** Comando custom: ferma la riproduzione e svuota la coda. */
        const val CMD_STOP = "it.agoldoni.player.STOP"
    }

    override fun onCreate() {
        super.onCreate()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { addListener(playerListener) }

        // Espone il comando "successivo" anche con un solo MediaItem in timeline.
        val player = object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().add(COMMAND_SEEK_TO_NEXT).build()

            override fun isCommandAvailable(command: Int): Boolean =
                command == COMMAND_SEEK_TO_NEXT || super.isCommandAvailable(command)

            override fun hasNextMediaItem(): Boolean = true

            override fun seekToNext() = advanceAndPlay(wrap = true)

            override fun seekToNextMediaItem() = advanceAndPlay(wrap = true)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_CURRENT, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_NEXT, Bundle.EMPTY))
                .add(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PLAY_CURRENT -> queue.current()?.let { prepareAndPlay(it) } ?: stopPlayback()
                CMD_SKIP_NEXT -> advanceAndPlay(wrap = true)
                CMD_STOP -> stopPlayback()
                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            // Fine brano naturale: avanza senza loop (a fine coda si ferma).
            if (playbackState == Player.STATE_ENDED) advanceAndPlay(wrap = false)
        }
    }

    private fun advanceAndPlay(wrap: Boolean) {
        val next = queue.moveToNext(wrap)
        if (next == null) stopPlayback() else prepareAndPlay(next)
    }

    /** Decifra [track] in un file temporaneo e avvia la riproduzione. */
    private fun prepareAndPlay(track: Track) {
        val dek = cryptoManager.sessionDek
        if (dek == null) {
            // DEK non disponibile (es. processo riavviato senza sblocco biometrico).
            Log.w(TAG, "DEK non disponibile: riproduzione interrotta")
            stopPlayback()
            return
        }
        prepareJob?.cancel()
        prepareJob = scope.launch {
            val temp = withContext(Dispatchers.IO) {
                runCatching {
                    cryptoManager.decryptToTempFile(dek, File(track.uri), track.originalExtension)
                }.getOrElse {
                    Log.e(TAG, "Errore decifratura brano: ${track.title}", it)
                    null
                }
            }
            if (temp == null) {
                stopPlayback()
                return@launch
            }
            val previous = currentTempFile
            currentTempFile = temp

            val item = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(Uri.fromFile(temp))
                .setMediaMetadata(buildMetadata(track))
                .build()
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            // Cleanup del temp del brano precedente (decisione: cancellazione al transition).
            withContext(Dispatchers.IO) { previous?.delete() }
        }
    }

    private fun stopPlayback() {
        prepareJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        currentTempFile?.let { f -> scope.launch(Dispatchers.IO) { f.delete() } }
        currentTempFile = null
        queue.clear()
    }

    private fun buildMetadata(track: Track): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
        track.albumArtPath?.let { builder.setArtworkUri(Uri.fromFile(File(it))) }
        return builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        prepareJob?.cancel()
        mediaSession.release()
        exoPlayer.release()
        currentTempFile?.delete()
        currentTempFile = null
        scope.cancel()
        super.onDestroy()
    }
}
