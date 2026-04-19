package it.agoldoni.player.domain.ftp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.util.supportedExtensionFromPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scarica un singolo file dal server FTP in una cartella temporanea dedicata
 * (`cacheDir/ftp_temp/`). Il download è stream-based: il file non viene mai
 * caricato interamente in memoria. Ritorna il file locale scaricato oppure
 * null in caso di errore.
 */
@Singleton
class FtpDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tempDir: File
        get() = File(context.cacheDir, "ftp_temp").also { it.mkdirs() }

    suspend fun download(client: FTPClient, remotePath: String): File? =
        withContext(Dispatchers.IO) {
            val ext = supportedExtensionFromPath(remotePath) ?: "mp3"
            val dest = File(tempDir, "${UUID.randomUUID()}.$ext")
            try {
                val ok = dest.outputStream().use { out ->
                    client.retrieveFile(remotePath, out)
                }
                if (ok) {
                    dest
                } else {
                    dest.delete()
                    null
                }
            } catch (e: Exception) {
                dest.delete()
                null
            }
        }
}
