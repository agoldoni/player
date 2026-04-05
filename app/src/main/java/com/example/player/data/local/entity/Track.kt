package com.example.player.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,             // millisecondi
    val year: String?,
    val trackNumber: String?,
    val albumArtPath: String?,      // path locale alla copertina salvata
    val importedAt: Long = System.currentTimeMillis()
)
