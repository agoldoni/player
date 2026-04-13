package it.agoldoni.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import it.agoldoni.player.data.local.dao.FtpConfigDao
import it.agoldoni.player.data.local.dao.PlaylistDao
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.local.entity.FtpConfig
import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.local.entity.PlaylistTrackCrossRef
import it.agoldoni.player.data.local.entity.Track

@Database(
    entities = [Track::class, Playlist::class, PlaylistTrackCrossRef::class, FtpConfig::class],
    version = 5,
    exportSchema = false
)
abstract class PlayerDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun ftpConfigDao(): FtpConfigDao

    companion object {
        const val DATABASE_NAME = "player_db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN originalFileSize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN encryptedFileSize INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS playlists (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS playlist_track_cross_ref (
                        playlistId TEXT NOT NULL,
                        trackId TEXT NOT NULL,
                        addedAt INTEGER NOT NULL,
                        PRIMARY KEY(playlistId, trackId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
                        FOREIGN KEY(trackId) REFERENCES tracks(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_playlistId ON playlist_track_cross_ref(playlistId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_track_cross_ref_trackId ON playlist_track_cross_ref(trackId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN lastPlayedTrackId TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS ftp_config (
                        id INTEGER NOT NULL PRIMARY KEY,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        username TEXT NOT NULL,
                        encryptedPassword BLOB NOT NULL,
                        rootPath TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )"""
                )
            }
        }
    }
}
