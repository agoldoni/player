package it.agoldoni.player.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumArtSaver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val artDir: File
        get() = File(context.filesDir, "album_art").also { it.mkdirs() }

    /**
     * Salva i byte della copertina come PNG nella directory privata dell'app.
     * Ritorna il path assoluto del file salvato, o null se i byte non sono validi.
     */
    suspend fun save(artBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        val bitmap = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            ?: return@withContext null
        val file = File(artDir, "${UUID.randomUUID()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        file.absolutePath
    }
}
