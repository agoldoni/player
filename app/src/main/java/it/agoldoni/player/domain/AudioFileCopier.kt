package it.agoldoni.player.domain

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
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
     * Copia il file audio in una directory temporanea (cache).
     * Ritorna il path assoluto del file temporaneo, o null in caso di errore.
     * Il file temporaneo verrà cifrato e poi eliminato da ImportTrackUseCase.
     */
    suspend fun copyToTemp(uri: Uri): String? = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "import_temp").also { it.mkdirs() }
        val dest = File(tempDir, "${UUID.randomUUID()}.mp3")
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
}
