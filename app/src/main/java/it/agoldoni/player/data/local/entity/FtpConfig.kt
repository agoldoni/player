package it.agoldoni.player.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ftp_config")
data class FtpConfig(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val host: String,
    val port: Int,
    val username: String,
    val encryptedPassword: ByteArray,
    val rootPath: String = "/",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SINGLETON_ID = 1
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FtpConfig) return false
        return id == other.id &&
            host == other.host &&
            port == other.port &&
            username == other.username &&
            encryptedPassword.contentEquals(other.encryptedPassword) &&
            rootPath == other.rootPath &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + host.hashCode()
        result = 31 * result + port
        result = 31 * result + username.hashCode()
        result = 31 * result + encryptedPassword.contentHashCode()
        result = 31 * result + rootPath.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
