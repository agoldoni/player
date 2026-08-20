package it.agoldoni.player.domain.transfer

import android.os.Build
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.data.repository.PlaylistRepository
import it.agoldoni.player.data.repository.TrackRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cosa il mittente mette a disposizione del destinatario.
 *
 * [Tracks] non porta con sé playlist: una playlist ha senso solo se completa, e
 * una selezione arbitraria di brani non garantisce di contenerla tutta. Chi
 * vuole le playlist usa [Playlists] o [WholeLibrary].
 */
sealed interface TransferSelection {
    data object WholeLibrary : TransferSelection
    data class Playlists(val playlistIds: Set<String>) : TransferSelection
    data class Tracks(val trackIds: Set<String>) : TransferSelection
}

/** Manifest più i [Track] locali che lo soddisfano, indicizzati per ID di origine. */
data class ResolvedSelection(
    val manifest: TransferManifest,
    val tracksById: Map<String, Track>
) {
    val trackCount: Int get() = manifest.trackCount
    val totalBytes: Long get() = manifest.totalBytes
}

/**
 * Traduce una [TransferSelection] nel manifest da servire e nella mappa dei
 * brani da cui leggere i file. È l'unico punto che tocca il DB lato mittente:
 * il server, una volta partito, lavora su questa fotografia.
 */
@Singleton
class TransferSelectionResolver @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository
) {

    suspend fun resolve(selection: TransferSelection): ResolvedSelection {
        val playlists = when (selection) {
            is TransferSelection.WholeLibrary -> playlistRepository.getAllPlaylistsOnce()
            is TransferSelection.Playlists ->
                playlistRepository.getAllPlaylistsOnce().filter { it.id in selection.playlistIds }
            is TransferSelection.Tracks -> emptyList()
        }

        val crossRefsByPlaylist = playlists.associate { playlist ->
            playlist.id to playlistRepository.getCrossRefsForPlaylist(playlist.id)
        }

        val tracks: List<Track> = when (selection) {
            is TransferSelection.WholeLibrary -> trackRepository.getAllTracksOnce()
            is TransferSelection.Playlists -> {
                val ids = crossRefsByPlaylist.values.flatten().map { it.trackId }.distinct()
                if (ids.isEmpty()) emptyList() else trackRepository.getTracksByIds(ids)
            }
            is TransferSelection.Tracks -> {
                val ids = selection.trackIds.toList()
                if (ids.isEmpty()) emptyList() else trackRepository.getTracksByIds(ids)
            }
        }

        val tracksById = tracks.associateBy { it.id }
        val manifestTracks = tracks.map { track ->
            ManifestTrack(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                duration = track.duration,
                year = track.year,
                trackNumber = track.trackNumber,
                originalExtension = track.originalExtension,
                originalFileSize = track.originalFileSize,
                encryptedFileSize = track.encryptedFileSize,
                importedAt = track.importedAt,
                hasArt = track.albumArtPath != null
            )
        }

        val manifestPlaylists = playlists.map { playlist ->
            // Solo i brani effettivamente inclusi nella selezione: un riferimento
            // a un brano non trasferito resterebbe pendente sul destinatario.
            val entries = crossRefsByPlaylist[playlist.id].orEmpty()
                .filter { it.trackId in tracksById }
                .map { ManifestPlaylistEntry(trackId = it.trackId, addedAt = it.addedAt) }
            ManifestPlaylist(
                id = playlist.id,
                name = playlist.name,
                createdAt = playlist.createdAt,
                lastPlayedTrackId = playlist.lastPlayedTrackId?.takeIf { it in tracksById },
                entries = entries
            )
        }

        val manifest = TransferManifest(
            device = deviceName(),
            trackCount = manifestTracks.size,
            // Dimensione in chiaro: è quella che il destinatario deve poter ospitare.
            totalBytes = manifestTracks.sumOf { it.originalFileSize },
            tracks = manifestTracks,
            playlists = manifestPlaylists
        )

        return ResolvedSelection(manifest, tracksById)
    }

    companion object {
        /** Nome mostrato all'utente sull'altro telefono. */
        fun deviceName(): String {
            val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
            val model = Build.MODEL.orEmpty()
            return when {
                model.startsWith(manufacturer, ignoreCase = true) -> model
                model.isBlank() -> manufacturer.ifBlank { "Telefono Android" }
                else -> "$manufacturer $model"
            }
        }
    }
}
