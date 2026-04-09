package it.agoldoni.player.domain

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import it.agoldoni.player.data.local.entity.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Estrae i metadati da un URI audio e costruisce un [Track].
     * Ritorna null se il file non è leggibile.
     */
    fun extract(uri: Uri, albumArtPath: String? = null): Track? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment ?: "Unknown Title"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Unknown Album"
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
            val trackNumber = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)

            Track(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                duration = durationMs,
                year = year,
                trackNumber = trackNumber,
                albumArtPath = albumArtPath
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Estrae i byte della copertina album embedded nel file audio.
     * Ritorna null se non presente.
     */
    fun extractAlbumArt(uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
