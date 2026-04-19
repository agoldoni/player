package it.agoldoni.player.util

/**
 * Estensioni audio supportate dall'app (senza punto, lowercase).
 * Ogni formato qui elencato deve essere decodificabile sia da
 * [android.media.MediaMetadataRetriever] (import + metadati) sia da
 * [android.media.MediaPlayer] (playback).
 */
val SupportedAudioExtensions: List<String> = listOf("mp3", "flac")

/**
 * Ritorna l'estensione del path (senza punto, lowercase) se è tra quelle
 * supportate, altrimenti null.
 */
fun supportedExtensionFromPath(path: String): String? {
    val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return if (ext in SupportedAudioExtensions) ext else null
}
