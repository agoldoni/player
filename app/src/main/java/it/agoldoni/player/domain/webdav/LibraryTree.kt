package it.agoldoni.player.domain.webdav

import it.agoldoni.player.data.local.entity.Track

/**
 * Nodo dell'albero virtuale che l'app espone via WebDAV.
 *
 * L'albero non esiste su disco: è una vista costruita dai record di `tracks`.
 * I file veri restano cifrati in `filesDir/tracks/{id}` e vengono decifrati solo
 * al momento della `GET`.
 */
sealed interface DavNode {
    val name: String

    /** Una cartella: la root, un artista o un album. */
    data class Collection(
        override val name: String,
        val children: List<DavNode>
    ) : DavNode

    /** Un brano, esposto come file audio. */
    data class TrackFile(
        override val name: String,
        val track: Track
    ) : DavNode
}

/**
 * Costruisce l'albero `Artista/Album/NN - Titolo.ext` a partire dalla libreria.
 *
 * Nessuna dipendenza da Android: è la parte verificabile su JVM di tutta la
 * feature, come [it.agoldoni.player.domain.transfer.PlaylistRemapper] lo è per
 * il trasferimento.
 *
 * **La stabilità dei nomi è il contratto principale di questa classe.** Il client
 * (rclone, rsync) decide cosa scaricare confrontando percorso e dimensione con
 * la propria cartella: se lo stesso brano cambiasse nome fra una sincronizzazione
 * e l'altra, verrebbe riscaricato ogni volta e la feature perderebbe il suo unico
 * scopo. Per questo la disambiguazione degli omonimi deriva dall'ID del brano e
 * non da un contatore progressivo, che cambierebbe al variare della libreria.
 */
object LibraryTree {

    const val UNKNOWN_ARTIST = "Sconosciuto"
    const val UNKNOWN_ALBUM = "Senza album"
    const val UNKNOWN_TITLE = "Senza titolo"

    /** Limite prudenziale per segmento: sotto i 255 byte di ext4/NTFS anche in UTF-8. */
    const val MAX_SEGMENT = 120

    private const val DEFAULT_EXTENSION = "mp3"
    private const val ID_SUFFIX_LENGTH = 8

    /** Vietati su Windows; `/` e `\` lo sono ovunque perché separano i percorsi. */
    private val FORBIDDEN_CHARS = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun build(tracks: List<Track>): DavNode.Collection {
        val artists = tracks
            .groupBy { sanitizeSegment(it.artist, UNKNOWN_ARTIST) }
            .entries
            .sortedBy { it.key }
            .map { (artistDir, artistTracks) ->
                val albums = artistTracks
                    .groupBy { sanitizeSegment(it.album, UNKNOWN_ALBUM) }
                    .entries
                    .sortedBy { it.key }
                    .map { (albumDir, albumTracks) ->
                        DavNode.Collection(albumDir, fileNodes(albumTracks))
                    }
                DavNode.Collection(artistDir, albums)
            }
        return DavNode.Collection("", artists)
    }

    /**
     * Ripulisce un singolo segmento di percorso.
     *
     * L'ordine dei passaggi conta: prima si sostituiscono i caratteri vietati,
     * poi si taglia alla lunghezza massima, e **solo alla fine** si rimuovono
     * punti e spazi finali — altrimenti un taglio potrebbe reintrodurre proprio
     * il punto finale che Windows rifiuta.
     */
    fun sanitizeSegment(raw: String, fallback: String): String {
        val replaced = buildString(raw.length) {
            for (c in raw.trim()) append(if (isForbidden(c)) '_' else c)
        }
        return replaced.take(MAX_SEGMENT).trimEnd(' ', '.').ifEmpty { fallback }
    }

    /**
     * Prefisso numerico della traccia, zero-paddato a due cifre.
     *
     * Il tag ID3 vale spesso `"3/12"` (traccia su totale): si prende solo la
     * parte iniziale. Se il campo manca o non comincia con una cifra il brano
     * resta senza prefisso, il che è preferibile a inventare uno `00`.
     */
    fun trackNumberPrefix(raw: String?): String? {
        val digits = raw?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        val number = digits.toIntOrNull() ?: return null
        return number.toString().padStart(2, '0')
    }

    private fun fileNodes(tracks: List<Track>): List<DavNode> {
        val bases = tracks.map { it to baseName(it) }
        val colliding = duplicatesOf(bases.map { it.second })

        val named = bases.map { (track, base) ->
            val disambiguated =
                if (base in colliding) "$base [${track.id.take(ID_SUFFIX_LENGTH)}]" else base
            track to "$disambiguated.${extensionOf(track)}"
        }

        // Rete di sicurezza per il caso in cui due UUID condividano i primi otto
        // caratteri: si ricade sull'ID intero, che è unico per costruzione.
        val stillColliding = duplicatesOf(named.map { it.second })

        return named
            .map { (track, name) ->
                val finalName = if (name in stillColliding) {
                    "${baseName(track)} [${track.id}].${extensionOf(track)}"
                } else {
                    name
                }
                DavNode.TrackFile(finalName, track)
            }
            .sortedBy { it.name }
    }

    private fun baseName(track: Track): String {
        val title = sanitizeSegment(track.title, UNKNOWN_TITLE)
        val prefix = trackNumberPrefix(track.trackNumber)
        val composed = if (prefix != null) "$prefix - $title" else title
        return composed.take(MAX_SEGMENT).trimEnd(' ', '.').ifEmpty { UNKNOWN_TITLE }
    }

    private fun extensionOf(track: Track): String =
        track.originalExtension.trim().lowercase().ifEmpty { DEFAULT_EXTENSION }

    private fun duplicatesOf(names: List<String>): Set<String> =
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

    private fun isForbidden(c: Char): Boolean =
        c in FORBIDDEN_CHARS || c.code < 0x20 || c.code == 0x7F
}

/**
 * Risolve un percorso (già decodificato, senza il token) in un nodo dell'albero.
 * Ritorna null se un segmento non esiste o se si tenta di scendere dentro un file.
 */
fun DavNode.Collection.resolve(segments: List<String>): DavNode? {
    var current: DavNode = this
    for (segment in segments) {
        if (segment.isEmpty()) continue
        val collection = current as? DavNode.Collection ?: return null
        current = collection.children.firstOrNull { it.name == segment } ?: return null
    }
    return current
}
