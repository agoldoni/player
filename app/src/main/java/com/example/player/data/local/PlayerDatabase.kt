package com.example.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.player.data.local.dao.TrackDao
import com.example.player.data.local.entity.Track

@Database(
    entities = [Track::class],
    version = 1,
    exportSchema = false
)
abstract class PlayerDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        const val DATABASE_NAME = "player_db"
    }
}
