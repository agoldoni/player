package it.agoldoni.player.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.TrackRepository
import it.agoldoni.player.domain.transfer.ManifestTrack
import it.agoldoni.player.util.SupportedAudioExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ImportTrack"

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

    /**
     * Importa un brano ricevuto da un'altra istanza dell'app.
     *
     * Diverge dalla pipeline ordinaria su un punto essenziale: **non riestrae i
     * metadati**. Titolo, artista, album, anno, numero di traccia e data di
     * import arrivano dal manifest del mittente e vanno preservati tali e quali,
     * altrimenti il brano migrato perderebbe la propria storia. La copertina
     * arriva già come byte, quindi anche [MetadataExtractor] resta fuori dal giro.
     *
     * Ritorna l'ID **locale** assegnato al brano — serve a rimappare le playlist
     * ricevute — oppure null se l'import è fallito. Il file sorgente viene
     * cancellato in ogni caso.
     */
    suspend fun importTransferred(
        source: File,
        meta: ManifestTrack,
        artBytes: ByteArray?,
        dek: SecretKey
    ): String? = withContext(Dispatchers.IO) {
        try {
            val artPath = artBytes?.let { albumArtSaver.save(it) }
            val id = UUID.randomUUID().toString()
            val encryptedFile = File(tracksDir, id)

            // La dimensione originale è quella del file appena ricevuto in chiaro:
            // è il dato di verità, e coincide con quanto dichiarato nel manifest.
            val originalSize = source.length()
            cryptoManager.encryptFile(dek, source, encryptedFile)

            val extension = meta.originalExtension.lowercase()
                .takeIf { it in SupportedAudioExtensions }
                ?: source.extension.lowercase().takeIf { it in SupportedAudioExtensions }
                ?: "mp3"

            trackRepository.insertTrack(
                Track(
                    id = id,
                    uri = encryptedFile.absolutePath,
                    title = meta.title,
                    artist = meta.artist,
                    album = meta.album,
                    duration = meta.duration,
                    year = meta.year,
                    trackNumber = meta.trackNumber,
                    albumArtPath = artPath,
                    originalFileSize = originalSize,
                    encryptedFileSize = encryptedFile.length(),
                    originalExtension = extension,
                    importedAt = if (meta.importedAt > 0) meta.importedAt else System.currentTimeMillis()
                )
            )
            id
        } catch (e: Exception) {
            Log.w(TAG, "Import del brano trasferito \"${meta.title}\" fallito", e)
            null
        } finally {
            source.delete()
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

        val extension = source.extension.lowercase()
            .takeIf { it in SupportedAudioExtensions }
            ?: "mp3"

        trackRepository.insertTrack(
            track.copy(
                uri = encryptedFile.absolutePath,
                originalFileSize = originalSize,
                encryptedFileSize = encryptedSize,
                originalExtension = extension
            )
        )
        return true
    }
}
