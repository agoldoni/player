package it.agoldoni.player.domain.ftp

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

data class FtpRemoteFile(
    val path: String,
    val sizeBytes: Long
)

/**
 * Percorre ricorsivamente un server FTP a partire da una root e ritorna tutti
 * i file con estensione .mp3 (case-insensitive). L'operazione è cancellabile:
 * tra una directory e l'altra verifica lo stato della coroutine.
 */
@Singleton
class FtpScanner @Inject constructor() {

    suspend fun walk(client: FTPClient, rootPath: String): List<FtpRemoteFile> =
        withContext(Dispatchers.IO) {
            val collected = mutableListOf<FtpRemoteFile>()
            walkInto(client, normalizeRoot(rootPath), collected)
            collected
        }

    private suspend fun walkInto(
        client: FTPClient,
        directory: String,
        acc: MutableList<FtpRemoteFile>
    ) {
        coroutineContext.ensureActive()

        val entries: Array<FTPFile> = runCatching { client.listFiles(directory) }
            .getOrNull()
            ?: return

        for (entry in entries) {
            coroutineContext.ensureActive()
            val name = entry.name ?: continue
            if (name == "." || name == "..") continue

            val childPath = joinPath(directory, name)
            when {
                entry.isDirectory -> walkInto(client, childPath, acc)
                entry.isFile && name.endsWith(".mp3", ignoreCase = true) -> {
                    acc.add(FtpRemoteFile(path = childPath, sizeBytes = entry.size))
                }
            }
        }
    }

    private fun normalizeRoot(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/"
        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withLeadingSlash.length > 1 && withLeadingSlash.endsWith("/"))
            withLeadingSlash.dropLast(1)
        else
            withLeadingSlash
    }

    private fun joinPath(parent: String, child: String): String {
        val normalizedParent = if (parent.endsWith("/")) parent else "$parent/"
        return normalizedParent + child
    }
}
