package it.agoldoni.player.domain

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.util.SupportedAudioExtensions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFileCopier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Copia il file audio in una directory temporanea (cache). Il file
     * temporaneo mantiene l'estensione originale (fallback: "mp3") in modo
     * che [android.media.MediaMetadataRetriever] e la pipeline di import
     * possano determinare correttamente il formato.
     * Ritorna il path assoluto del file temporaneo, o null in caso di errore.
     * Il file temporaneo verrà cifrato e poi eliminato da ImportTrackUseCase.
     */
    suspend fun copyToTemp(uri: Uri): String? = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_temp").also { it.mkdirs() }
        val ext = resolveExtension(uri)
        val dest = File(tempDir, "${UUID.randomUUID()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    private fun resolveExtension(uri: Uri): String {
        val fromMime = context.contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.lowercase()
        if (fromMime != null && fromMime in SupportedAudioExtensions) return fromMime

        val fromPath = uri.lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
        if (!fromPath.isNullOrEmpty() && fromPath in SupportedAudioExtensions) return fromPath

        return "mp3"
    }
}
