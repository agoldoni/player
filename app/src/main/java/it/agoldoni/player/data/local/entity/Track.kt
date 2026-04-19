package it.agoldoni.player.data.local.entity

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
    val originalFileSize: Long = 0, // dimensione file audio originale in byte
    val encryptedFileSize: Long = 0, // dimensione file cifrato in byte
    val originalExtension: String = "mp3", // estensione originale senza punto, lowercase (es. "mp3", "flac")
    val importedAt: Long = System.currentTimeMillis()
)
