package it.agoldoni.player.domain.transfer

import it.agoldoni.player.data.local.entity.Playlist
import it.agoldoni.player.data.local.entity.PlaylistTrackCrossRef

/**
 * Ricostruisce le playlist ricevute usando gli **ID locali** del destinatario.
 *
 * Gli ID di brani e playlist sono UUID generati all'import: riusare quelli del
 * mittente rischierebbe collisioni con la libreria già presente. Il
 * destinatario tiene quindi una mappa `idOrigine → idLocale`, dove i brani
 * saltati per dedup puntano al brano **già esistente**: così una playlist
 * ricevuta resta completa anche se metà dei suoi brani c'erano già.
 *
 * Playlist omonima già presente → **fusione**: i brani vengono aggiunti a
 * quella esistente (l'inserimento dei cross-ref è idempotente) e il suo
 * `lastPlayedTrackId` locale non viene toccato, perché descrive l'ascolto su
 * *questo* telefono.
 *
 * Logica pura, senza dipendenze Android: è la parte del flusso di ricezione
 * coperta da unit test.
 */
object PlaylistRemapper {

    data class Result(
        /** Playlist da creare ex novo. */
        val newPlaylists: List<Playlist>,
        /** Relazioni playlist↔brano da inserire (nuove e fuse). */
        val crossRefs: List<PlaylistTrackCrossRef>,
        /** Quante playlist ricevute sono state fuse con una omonima locale. */
        val mergedCount: Int
    )

    fun remap(
        manifestPlaylists: List<ManifestPlaylist>,
        trackIdMap: Map<String, String>,
        existingByName: Map<String, Playlist>,
        newIdProvider: (ManifestPlaylist) -> String
    ): Result {
        val newPlaylists = mutableListOf<Playlist>()
        val crossRefs = mutableListOf<PlaylistTrackCrossRef>()
        var merged = 0

        for (manifestPlaylist in manifestPlaylists) {
            val entries = manifestPlaylist.entries.mapNotNull { entry ->
                trackIdMap[entry.trackId]?.let { localTrackId -> localTrackId to entry.addedAt }
            }
            // Una playlist i cui brani non sono arrivati non ha senso: si salta.
            if (entries.isEmpty()) continue

            val existing = existingByName[manifestPlaylist.name]
            val playlistId = if (existing != null) {
                merged++
                existing.id
            } else {
                val id = newIdProvider(manifestPlaylist)
                newPlaylists += Playlist(
                    id = id,
                    name = manifestPlaylist.name,
                    createdAt = manifestPlaylist.createdAt,
                    // Solo per le playlist nuove: su una fusa il segnaposto locale vince.
                    lastPlayedTrackId = manifestPlaylist.lastPlayedTrackId?.let { trackIdMap[it] }
                )
                id
            }

            entries.forEach { (localTrackId, addedAt) ->
                crossRefs += PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = localTrackId,
                    addedAt = addedAt
                )
            }
        }

        return Result(newPlaylists = newPlaylists, crossRefs = crossRefs, mergedCount = merged)
    }
}
