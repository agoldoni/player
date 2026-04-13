package it.agoldoni.player.domain

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportTrackUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val metadataExtractor: MetadataExtractor,
    private val albumArtSaver: AlbumArtSaver,
    private val audioFileCopier: AudioFileCopier,
    private val cryptoManager: CryptoManager,
    private val trackRepository: TrackRepository
) {
    private val tracksDir: File
        get() = File(context.filesDir, "tracks").also { it.mkdirs() }

    /**
     * Importa una traccia a partire da un [Uri] (tipicamente dal file picker).
     * Copia il contenuto in un file temporaneo e delega a [importLocalFile].
     */
    suspend operator fun invoke(uri: Uri, dek: SecretKey): Boolean = withContext(Dispatchers.IO) {
        val tempPath = audioFileCopier.copyToTemp(uri) ?: return@withContext false
        val tempFile = File(tempPath)
        try {
            importLocalFile(tempFile, dek, deleteSource = false)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Importa una traccia a partire da un file già presente su disco locale
     * (es. scaricato da FTP). Se [deleteSource] è true, il file sorgente viene
     * cancellato al termine dell'import a prescindere dall'esito.
     */
    suspend operator fun invoke(
        localFile: File,
        dek: SecretKey,
        deleteSource: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            importLocalFile(localFile, dek, deleteSource = false)
        } finally {
            if (deleteSource) localFile.delete()
        }
    }

    suspend fun importAll(uris: List<Uri>, dek: SecretKey): Int = uris.count { invoke(it, dek) }

    /**
     * Pipeline comune: estrai metadati → salva copertina → cifra → inserisci nel DB.
     * Il parametro [deleteSource] qui è ignorato: la gestione del ciclo di vita del
     * file sorgente è lasciata ai chiamanti pubblici per evitare doppi delete.
     */
    private suspend fun importLocalFile(
        source: File,
        dek: SecretKey,
        @Suppress("UNUSED_PARAMETER") deleteSource: Boolean
    ): Boolean {
        val sourceUri = Uri.fromFile(source)

        val artBytes = metadataExtractor.extractAlbumArt(sourceUri)
        val artPath = artBytes?.let { albumArtSaver.save(it) }
        val track = metadataExtractor.extract(sourceUri, artPath) ?: return false

        val originalSize = source.length()
        val encryptedFile = File(tracksDir, track.id)
        cryptoManager.encryptFile(dek, source, encryptedFile)
        val encryptedSize = encryptedFile.length()

        trackRepository.insertTrack(
            track.copy(
                uri = encryptedFile.absolutePath,
                originalFileSize = originalSize,
                encryptedFileSize = encryptedSize
            )
        )
        return true
    }
}
