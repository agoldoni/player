package it.agoldoni.player.domain

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.local.dao.TrackDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OrphanCleanup"

/**
 * Rimuove alla startup i file orfani (non collegati a nessuna traccia nel DB)
 * dalle directory tracks/ e album_art/, più eventuali residui nella cache di import.
 */
@Singleton
class OrphanCleanupUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackDao: TrackDao
) {
    suspend operator fun invoke() = withContext(Dispatchers.IO) {
        cleanupTracks()
        cleanupAlbumArt()
        cleanupImportCache()
        cleanupFtpTempCache()
        cleanupUploadTempCache()
    }

    private suspend fun cleanupTracks() {
        val tracksDir = File(context.filesDir, "tracks")
        if (!tracksDir.isDirectory) return

        val validIds = trackDao.getAllTrackIds().toSet()
        tracksDir.listFiles()?.forEach { file ->
            if (file.name !in validIds) {
                Log.d(TAG, "Rimosso track orfano: ${file.name}")
                file.delete()
            }
        }
    }

    private suspend fun cleanupAlbumArt() {
        val artDir = File(context.filesDir, "album_art")
        if (!artDir.isDirectory) return

        val validPaths = trackDao.getAllAlbumArtPaths().toSet()
        artDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in validPaths) {
                Log.d(TAG, "Rimossa album art orfana: ${file.name}")
                file.delete()
            }
        }
    }

    private fun cleanupImportCache() {
        val tempDir = File(context.cacheDir, "import_temp")
        if (!tempDir.isDirectory) return

        tempDir.listFiles()?.forEach { file ->
            Log.d(TAG, "Rimosso residuo cache import: ${file.name}")
            file.delete()
        }
    }

    private fun cleanupFtpTempCache() {
        val tempDir = File(context.cacheDir, "ftp_temp")
        if (!tempDir.isDirectory) return

        tempDir.listFiles()?.forEach { file ->
            Log.d(TAG, "Rimosso residuo cache FTP: ${file.name}")
            file.delete()
        }
    }

    private fun cleanupUploadTempCache() {
        val tempDir = File(context.cacheDir, "upload_temp")
        if (!tempDir.isDirectory) return

        tempDir.listFiles()?.forEach { file ->
            Log.d(TAG, "Rimosso residuo cache upload Wi-Fi: ${file.name}")
            file.delete()
        }
    }
}
