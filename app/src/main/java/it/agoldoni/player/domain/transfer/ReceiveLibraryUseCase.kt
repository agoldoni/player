package it.agoldoni.player.domain.transfer

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.repository.PlaylistRepository
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.ImportTrackUseCase
import it.agoldoni.player.util.SupportedAudioExtensions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

private const val TAG = "ReceiveLibrary"

/** Ogni quanti byte ricevuti aggiornare la barra del file in corso. */
private const val PROGRESS_STEP_BYTES = 256 * 1024

/** Margine di sicurezza sullo spazio disco richiesto (chiaro + cifrato). */
private const val DISK_SPACE_FACTOR = 2.2

sealed interface TransferProgress {
    data object Idle : TransferProgress
    data object Connecting : TransferProgress

    /** Codice da confrontare con quello mostrato sul telefono mittente. */
    data class AwaitingConfirmation(
        val code: String,
        val peerDevice: String
    ) : TransferProgress

    /** Conferma data: si attende che anche il mittente approvi. */
    data object WaitingForSender : TransferProgress

    data class Importing(
        val current: Int,
        val total: Int,
        val currentTitle: String,
        val fileBytes: Long,
        val fileTotalBytes: Long,
        val added: Int,
        val skipped: Int,
        val errors: Int
    ) : TransferProgress

    data class Done(
        val added: Int,
        val skipped: Int,
        val errors: Int,
        val playlists: Int,
        val cancelled: Boolean = false
    ) : TransferProgress

    data class Failed(val message: String) : TransferProgress
}

/**
 * Lato destinatario: pairing, manifest, download e import.
 *
 * Ricalca la forma di `SyncFromFtpUseCase` — un Flow di stati, cancellabile fra
 * un brano e il successivo — con due differenze sostanziali:
 *
 * 1. **conferma dell'utente**: dopo l'handshake il flusso si ferma su
 *    [TransferProgress.AwaitingConfirmation] finché non arriva [confirm] o
 *    [cancelPairing], così il codice a 6 cifre viene approvato su entrambi i lati;
 * 2. **metadati preservati**: l'import passa da
 *    [ImportTrackUseCase.importTransferred], che usa i campi del manifest invece
 *    di riestrarli dal file.
 *
 * La dedup su `(title, artist, album)` è la stessa di FTP e upload Wi-Fi: un
 * brano già presente viene saltato ma **entra comunque nella mappa degli ID**,
 * così le playlist ricevute restano complete. È anche ciò che rende gratuita la
 * ripresa dopo un'interruzione: al secondo tentativo i brani già importati
 * risultano "già presenti".
 */
@Singleton
class ReceiveLibraryUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transferClient: TransferClient,
    private val importTrackUseCase: ImportTrackUseCase,
    private val playlistRepository: PlaylistRepository,
    private val trackDao: TrackDao,
    private val cryptoManager: CryptoManager
) {
    private val tempDir: File
        get() = File(context.cacheDir, "transfer_temp").also { it.mkdirs() }

    @Volatile
    private var confirmation: CompletableDeferred<Boolean>? = null

    /** L'utente ha verificato che i due codici coincidono. */
    fun confirm() {
        confirmation?.complete(true)
    }

    /** L'utente ha rifiutato: il trasferimento termina senza scaricare nulla. */
    fun cancelPairing() {
        confirmation?.complete(false)
    }

    operator fun invoke(host: String, port: Int, token: String): Flow<TransferProgress> = channelFlow {
        val dek = cryptoManager.sessionDek
        if (dek == null) {
            send(TransferProgress.Failed("Sessione scaduta. Riavvia l'app per autenticarti."))
            return@channelFlow
        }

        send(TransferProgress.Connecting)

        var added = 0
        var skipped = 0
        var errors = 0
        var playlistCount = 0
        var connection: TransferClient.Connection? = null

        try {
            val established = transferClient.pair(host, port, token)
            connection = established

            val gate = CompletableDeferred<Boolean>()
            confirmation = gate
            send(
                TransferProgress.AwaitingConfirmation(
                    code = established.session.verificationCode,
                    peerDevice = established.peerDevice
                )
            )
            val approved = gate.await()
            confirmation = null
            if (!approved) {
                transferClient.report(established, TransferReport(0, 0, 0, cancelled = true))
                send(TransferProgress.Done(0, 0, 0, playlists = 0, cancelled = true))
                return@channelFlow
            }

            send(TransferProgress.WaitingForSender)
            val manifest = transferClient.awaitManifest(established)

            val required = (manifest.totalBytes * DISK_SPACE_FACTOR).toLong()
            val available = context.filesDir.usableSpace
            if (available < required) {
                send(
                    TransferProgress.Failed(
                        "Spazio insufficiente: servono circa ${formatSize(required)}, " +
                            "disponibili ${formatSize(available)}."
                    )
                )
                return@channelFlow
            }

            val total = manifest.trackCount
            // Mappa idOrigine → idLocale: include i brani saltati per dedup,
            // altrimenti le playlist ricevute perderebbero pezzi.
            val idMap = mutableMapOf<String, String>()

            manifest.tracks.forEachIndexed { index, manifestTrack ->
                coroutineContext.ensureActive()

                send(
                    TransferProgress.Importing(
                        current = index + 1,
                        total = total,
                        currentTitle = manifestTrack.title,
                        fileBytes = 0,
                        fileTotalBytes = manifestTrack.originalFileSize,
                        added = added,
                        skipped = skipped,
                        errors = errors
                    )
                )

                val existing = trackDao.getTrackByMetadata(
                    title = manifestTrack.title,
                    artist = manifestTrack.artist,
                    album = manifestTrack.album
                )
                if (existing != null) {
                    idMap[manifestTrack.id] = existing.id
                    skipped++
                    return@forEachIndexed
                }

                val extension = manifestTrack.originalExtension.lowercase()
                    .takeIf { it in SupportedAudioExtensions } ?: "mp3"
                val temp = File(tempDir, "${UUID.randomUUID()}.$extension")

                val localId = try {
                    var lastReported = 0L
                    val msRete = measureTimeMillis {
                    transferClient.downloadTrack(established, manifestTrack.id, temp) { bytes ->
                        if (bytes - lastReported >= PROGRESS_STEP_BYTES) {
                            lastReported = bytes
                            trySend(
                                TransferProgress.Importing(
                                    current = index + 1,
                                    total = total,
                                    currentTitle = manifestTrack.title,
                                    fileBytes = bytes,
                                    fileTotalBytes = manifestTrack.originalFileSize,
                                    added = added,
                                    skipped = skipped,
                                    errors = errors
                                )
                            )
                        }
                    }
                    }
                    val art = if (manifestTrack.hasArt) {
                        transferClient.downloadArt(established, manifestTrack.id)
                    } else {
                        null
                    }
                    var importato: String? = null
                    val msImport = measureTimeMillis {
                        importato = importTrackUseCase.importTransferred(temp, manifestTrack, art, dek)
                    }
                    // Diagnostica dei tempi: separa il costo di rete+decifratura da
                    // quello di ricifratura e scrittura nel DB.
                    val kb = manifestTrack.originalFileSize / 1024.0
                    Log.d(
                        TAG,
                        "perf \"${manifestTrack.title}\" ${kb.toInt()} KB: " +
                            "rete+decifra ${msRete}ms (${"%.2f".format(kb / 1024 / (msRete.coerceAtLeast(1) / 1000.0))} MB/s), " +
                            "import ${msImport}ms (${"%.2f".format(kb / 1024 / (msImport.coerceAtLeast(1) / 1000.0))} MB/s)"
                    )
                    importato
                } catch (ce: CancellationException) {
                    temp.delete()
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "Trasferimento di ${manifestTrack.title} fallito", e)
                    temp.delete()
                    null
                }

                if (localId != null) {
                    idMap[manifestTrack.id] = localId
                    added++
                } else {
                    errors++
                }
            }

            playlistCount = applyPlaylists(manifest, idMap)

            transferClient.report(established, TransferReport(added, skipped, errors))
            send(
                TransferProgress.Done(
                    added = added,
                    skipped = skipped,
                    errors = errors,
                    playlists = playlistCount
                )
            )
        } catch (ce: CancellationException) {
            connection?.let { transferClient.report(it, TransferReport(added, skipped, errors, cancelled = true)) }
            send(
                TransferProgress.Done(
                    added = added,
                    skipped = skipped,
                    errors = errors,
                    playlists = playlistCount,
                    cancelled = true
                )
            )
            throw ce
        } catch (e: IncompatibleProtocolException) {
            send(TransferProgress.Failed(e.message ?: "Versioni di protocollo incompatibili."))
        } catch (e: Exception) {
            Log.e(TAG, "Trasferimento fallito", e)
            send(TransferProgress.Failed(e.message ?: "Errore sconosciuto durante il trasferimento."))
        } finally {
            confirmation = null
            transferClient.close()
            cleanupTemp()
        }
    }.flowOn(Dispatchers.IO)

    /** Crea o fonde le playlist ricevute usando gli ID locali. */
    private suspend fun applyPlaylists(
        manifest: TransferManifest,
        idMap: Map<String, String>
    ): Int {
        if (manifest.playlists.isEmpty()) return 0

        val existingByName = playlistRepository.getAllPlaylistsOnce().associateBy { it.name }
        val result = PlaylistRemapper.remap(
            manifestPlaylists = manifest.playlists,
            trackIdMap = idMap,
            existingByName = existingByName,
            newIdProvider = { UUID.randomUUID().toString() }
        )

        result.newPlaylists.forEach { playlist ->
            runCatching { playlistRepository.insertPlaylist(playlist) }
                .onFailure { Log.w(TAG, "Playlist ${playlist.name} non creata", it) }
        }
        if (result.crossRefs.isNotEmpty()) {
            playlistRepository.addTracksToPlaylist(result.crossRefs)
        }
        return result.newPlaylists.size + result.mergedCount
    }

    private fun cleanupTemp() {
        runCatching { tempDir.listFiles()?.forEach { it.delete() } }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }
}
