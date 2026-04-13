package it.agoldoni.player.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import it.agoldoni.player.data.local.entity.FtpConfig

@Dao
interface FtpConfigDao {

    @Query("SELECT * FROM ftp_config WHERE id = :id LIMIT 1")
    suspend fun getConfig(id: Int = FtpConfig.SINGLETON_ID): FtpConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: FtpConfig)

    @Query("DELETE FROM ftp_config")
    suspend fun clearConfig()
}
