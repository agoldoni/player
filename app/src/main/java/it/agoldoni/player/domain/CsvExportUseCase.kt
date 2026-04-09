package it.agoldoni.player.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.data.repository.TrackRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CsvExportUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackRepository: TrackRepository
) {
    suspend operator fun invoke(): File {
        val tracks = trackRepository.getAllTracksOnce()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val fileName = "libreria_${dateFormat.format(Date())}.csv"
        val file = File(context.cacheDir, fileName)

        file.bufferedWriter().use { writer ->
            writer.appendLine("id,title,artist,album,duration_ms,year,track_number,original_size_bytes,encrypted_size_bytes,imported_at")
            for (track in tracks) {
                writer.appendLine(
                    listOf(
                        track.id,
                        track.title.csvEscape(),
                        track.artist.csvEscape(),
                        track.album.csvEscape(),
                        track.duration.toString(),
                        (track.year ?: "").csvEscape(),
                        (track.trackNumber ?: "").csvEscape(),
                        track.originalFileSize.toString(),
                        track.encryptedFileSize.toString(),
                        track.importedAt.toString()
                    ).joinToString(",")
                )
            }
        }
        return file
    }

    private fun String.csvEscape(): String {
        return if (contains(',') || contains('"') || contains('\n')) {
            "\"${replace("\"", "\"\"")}\""
        } else {
            this
        }
    }
}
