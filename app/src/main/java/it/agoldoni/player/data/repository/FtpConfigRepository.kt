package it.agoldoni.player.data.repository

import it.agoldoni.player.data.local.dao.FtpConfigDao
import it.agoldoni.player.data.local.entity.FtpConfig
import it.agoldoni.player.domain.CryptoManager
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contiene la configurazione del server FTP e si occupa di cifrare/decifrare
 * la password riusando la DEK di [CryptoManager].
 */
@Singleton
class FtpConfigRepository @Inject constructor(
    private val ftpConfigDao: FtpConfigDao,
    private val cryptoManager: CryptoManager
) {
    data class PlainConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val rootPath: String
    )

    suspend fun getConfig(): FtpConfig? = ftpConfigDao.getConfig()

    suspend fun getPlainConfig(dek: SecretKey): PlainConfig? {
        val stored = ftpConfigDao.getConfig() ?: return null
        val password = cryptoManager.decryptBytes(dek, stored.encryptedPassword).toString(Charsets.UTF_8)
        return PlainConfig(
            host = stored.host,
            port = stored.port,
            username = stored.username,
            password = password,
            rootPath = stored.rootPath
        )
    }

    suspend fun save(config: PlainConfig, dek: SecretKey) {
        val encrypted = cryptoManager.encryptBytes(dek, config.password.toByteArray(Charsets.UTF_8))
        ftpConfigDao.upsertConfig(
            FtpConfig(
                host = config.host,
                port = config.port,
                username = config.username,
                encryptedPassword = encrypted,
                rootPath = config.rootPath
            )
        )
    }

    suspend fun clear() = ftpConfigDao.clearConfig()
}
