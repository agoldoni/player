package it.agoldoni.player.domain.ftp

import android.util.Log
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.repository.FtpConfigRepository
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.domain.ImportTrackUseCase
import it.agoldoni.player.domain.MetadataExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "SyncFromFtp"

sealed interface SyncProgress {
    data object Idle : SyncProgress
    data object Connecting : SyncProgress
    data object Scanning : SyncProgress
    data class Importing(
        val current: Int,
        val total: Int,
        val currentFileName: String,
        val added: Int,
        val skipped: Int,
        val errors: Int
    ) : SyncProgress

    data class Done(
        val added: Int,
        val skipped: Int,
        val errors: Int,
        val cancelled: Boolean = false
    ) : SyncProgress

    data class Failed(val message: String) : SyncProgress
}

/**
 * Orchestratore della sincronizzazione da FTP:
 * 1. legge la configurazione
 * 2. apre una singola connessione FTP
 * 3. scansiona ricorsivamente alla ricerca di .mp3
 * 4. per ogni file: download → estrai metadati → dedup → import
 * 5. chiude la connessione
 *
 * Emette stati intermedi via Flow. Cancellation-aware: il chiamante può
 * interrompere il Flow in qualunque momento e il loop risponde tra un file
 * e il successivo.
 */
@Singleton
class SyncFromFtpUseCase @Inject constructor(
    private val ftpConfigRepository: FtpConfigRepository,
    private val ftpClientFactory: FtpClientFactory,
    private val ftpScanner: FtpScanner,
    private val ftpDownloader: FtpDownloader,
    private val metadataExtractor: MetadataExtractor,
    private val importTrackUseCase: ImportTrackUseCase,
    private val trackDao: TrackDao,
    private val cryptoManager: CryptoManager
) {

    operator fun invoke(): Flow<SyncProgress> = flow {
        val dek = cryptoManager.sessionDek
        if (dek == null) {
            emit(SyncProgress.Failed("DEK non disponibile. Riavvia l'app per autenticarti."))
            return@flow
        }

        val plainConfig = try {
            ftpConfigRepository.getPlainConfig(dek)
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura config FTP", e)
            emit(SyncProgress.Failed("Configurazione FTP illeggibile"))
            return@flow
        }

        if (plainConfig == null) {
            emit(SyncProgress.Failed("Configura prima il server FTP"))
            return@flow
        }

        emit(SyncProgress.Connecting)
        var client: FTPClient? = null
        var added = 0
        var skipped = 0
        var errors = 0

        try {
            client = ftpClientFactory.connect(plainConfig)

            emit(SyncProgress.Scanning)
            val remoteFiles = ftpScanner.walk(client, plainConfig.rootPath)
            val total = remoteFiles.size

            if (total == 0) {
                emit(SyncProgress.Done(added = 0, skipped = 0, errors = 0))
                return@flow
            }

            remoteFiles.forEachIndexed { index, remote ->
                coroutineContext.ensureActive()

                val fileName = remote.path.substringAfterLast('/')
                emit(
                    SyncProgress.Importing(
                        current = index + 1,
                        total = total,
                        currentFileName = fileName,
                        added = added,
                        skipped = skipped,
                        errors = errors
                    )
                )

                val downloaded = ftpDownloader.download(client, remote.path)
                if (downloaded == null) {
                    errors++
                    return@forEachIndexed
                }

                val outcome = processDownloadedFile(downloaded, dek)
                when (outcome) {
                    ProcessOutcome.Added -> added++
                    ProcessOutcome.Skipped -> skipped++
                    ProcessOutcome.Error -> errors++
                }
            }

            emit(SyncProgress.Done(added = added, skipped = skipped, errors = errors))
        } catch (ce: CancellationException) {
            emit(
                SyncProgress.Done(
                    added = added,
                    skipped = skipped,
                    errors = errors,
                    cancelled = true
                )
            )
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante la sincronizzazione", e)
            emit(SyncProgress.Failed(e.message ?: "Errore sconosciuto durante la sincronizzazione"))
        } finally {
            client?.let { ftpClientFactory.disconnectQuietly(it) }
        }
    }.flowOn(Dispatchers.IO)

    private enum class ProcessOutcome { Added, Skipped, Error }

    private suspend fun processDownloadedFile(
        localFile: File,
        dek: javax.crypto.SecretKey
    ): ProcessOutcome {
        val uri = android.net.Uri.fromFile(localFile)
        val candidate = metadataExtractor.extract(uri)
        if (candidate == null) {
            localFile.delete()
            return ProcessOutcome.Error
        }

        val existing = trackDao.getTrackByMetadata(
            title = candidate.title,
            artist = candidate.artist,
            album = candidate.album
        )
        if (existing != null) {
            localFile.delete()
            return ProcessOutcome.Skipped
        }

        val imported = try {
            importTrackUseCase.invoke(localFile, dek, deleteSource = true)
        } catch (e: Exception) {
            Log.w(TAG, "Errore import di ${localFile.name}", e)
            localFile.delete()
            false
        }
        return if (imported) ProcessOutcome.Added else ProcessOutcome.Error
    }
}
