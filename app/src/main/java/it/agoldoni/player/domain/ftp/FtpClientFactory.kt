package it.agoldoni.player.domain.ftp

import it.agoldoni.player.data.repository.FtpConfigRepository
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unico punto di creazione di [FTPClient]: timeout, passive mode e codifica
 * sono impostati qui per evitare divergenze tra scanner e downloader.
 */
@Singleton
class FtpClientFactory @Inject constructor() {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val DATA_TIMEOUT_MS = 60_000
    }

    /**
     * Apre una connessione verso il server FTP usando la config fornita.
     * Ritorna un client già loggato e pronto per le operazioni di listing/download.
     * In caso di errore chiude la socket prima di propagare l'eccezione.
     */
    fun connect(config: FtpConfigRepository.PlainConfig): FTPClient {
        val client = FTPClient()
        client.connectTimeout = CONNECT_TIMEOUT_MS
        client.defaultTimeout = CONNECT_TIMEOUT_MS
        client.controlEncoding = "UTF-8"

        try {
            client.connect(config.host, config.port)
            val reply = client.replyCode
            if (!FTPReply.isPositiveCompletion(reply)) {
                client.disconnect()
                throw IOException("Server FTP ha rifiutato la connessione: $reply")
            }

            if (!client.login(config.username, config.password)) {
                val loginReply = client.replyString
                client.disconnect()
                throw IOException("Login FTP fallito: $loginReply")
            }

            client.setFileType(FTP.BINARY_FILE_TYPE)
            client.enterLocalPassiveMode()
            client.setDataTimeout(Duration.ofMillis(DATA_TIMEOUT_MS.toLong()))
            return client
        } catch (t: Throwable) {
            runCatching { if (client.isConnected) client.disconnect() }
            throw t
        }
    }

    /**
     * Chiude una connessione FTP in modo silenzioso, ignorando eventuali errori di logout.
     */
    fun disconnectQuietly(client: FTPClient) {
        runCatching { if (client.isConnected) client.logout() }
        runCatching { if (client.isConnected) client.disconnect() }
    }
}
