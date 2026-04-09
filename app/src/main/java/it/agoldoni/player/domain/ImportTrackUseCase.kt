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
     * Importa una singola traccia:
     * 1. Copia il file MP3 in una directory temporanea
     * 2. Estrae i metadati dal file temporaneo
     * 3. Salva la copertina (filesDir/album_art/)
     * 4. Cifra il file con la DEK e lo salva come {trackId} nella directory tracks
     * 5. Elimina il file temporaneo
     * 6. Inserisce la Track nel DB con il path del file cifrato
     */
    suspend operator fun invoke(uri: Uri, dek: SecretKey): Boolean = withContext(Dispatchers.IO) {
        val tempPath = audioFileCopier.copyToTemp(uri) ?: return@withContext false
        val tempFile = File(tempPath)

        try {
            val tempUri = Uri.fromFile(tempFile)

            // Estrai copertina e metadati dal file temporaneo (non cifrato)
            val artBytes = metadataExtractor.extractAlbumArt(tempUri)
            val artPath = artBytes?.let { albumArtSaver.save(it) }
            val track = metadataExtractor.extract(tempUri, artPath)
                ?: return@withContext false

            val originalSize = tempFile.length()

            // Cifra il file temporaneo → salva come {trackId}
            val encryptedFile = File(tracksDir, track.id)
            cryptoManager.encryptFile(dek, tempFile, encryptedFile)

            val encryptedSize = encryptedFile.length()

            // Inserisci nel DB con il path del file cifrato e le dimensioni
            trackRepository.insertTrack(
                track.copy(
                    uri = encryptedFile.absolutePath,
                    originalFileSize = originalSize,
                    encryptedFileSize = encryptedSize
                )
            )
            true
        } finally {
            tempFile.delete()
        }
    }

    suspend fun importAll(uris: List<Uri>, dek: SecretKey): Int = uris.count { invoke(it, dek) }
}
