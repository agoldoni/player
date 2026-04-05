package com.example.player.domain

import android.net.Uri
import com.example.player.data.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportTrackUseCase @Inject constructor(
    private val metadataExtractor: MetadataExtractor,
    private val albumArtSaver: AlbumArtSaver,
    private val trackRepository: TrackRepository
) {
    /**
     * Importa una singola traccia dall'URI fornito.
     * Salta se l'URI è già presente nel DB (deduplicazione).
     * Ritorna true se la traccia è stata inserita, false altrimenti.
     */
    suspend operator fun invoke(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        // Evita duplicati
        if (trackRepository.getTrackByUri(uri.toString()) != null) return@withContext false

        // Salva copertina se presente
        val artBytes = metadataExtractor.extractAlbumArt(uri)
        val artPath = artBytes?.let { albumArtSaver.save(it) }

        // Estrai metadati e inserisci nel DB
        val track = metadataExtractor.extract(uri, artPath) ?: return@withContext false
        trackRepository.insertTrack(track)
        true
    }

    /**
     * Importa più tracce in batch. Ritorna il numero di tracce inserite.
     */
    suspend fun importAll(uris: List<Uri>): Int = uris.count { invoke(it) }
}
