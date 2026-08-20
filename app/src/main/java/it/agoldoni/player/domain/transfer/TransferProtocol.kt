package it.agoldoni.player.domain.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Contratto del trasferimento libreria-a-libreria fra due istanze dell'app.
 *
 * Il mittente espone un piccolo server HTTP sulla LAN (vedi [TransferServer]),
 * il destinatario lo interroga (vedi [TransferClient]). Tutte le rotte vivono
 * sotto un token casuale — capability URL, stesso schema dell'upload Wi-Fi —
 * e i payload viaggiano cifrati con la chiave di sessione derivata
 * dall'handshake ECDH (vedi [TransferCrypto]): il token è un filtro d'ingresso,
 * non ciò che protegge i dati.
 *
 * [PROTOCOL_VERSION] è il punto di compatibilità fra versioni diverse dell'app
 * installate sui due telefoni: in caso di mismatch si fallisce esplicitamente,
 * senza tentare interpretazioni parziali del manifest.
 */
const val PROTOCOL_VERSION = 1

/** Range di porte dedicato: distinto da quello di `UploadServer` (8080..8090). */
val TRANSFER_PORT_RANGE = 8091..8100

/** Tipo di servizio mDNS con cui il mittente si annuncia sulla rete locale. */
const val TRANSFER_SERVICE_TYPE = "_playerxfer._tcp"

/** Attributi TXT pubblicati insieme al servizio mDNS. */
const val TXT_TOKEN = "token"
const val TXT_DEVICE = "device"
const val TXT_VERSION = "v"

/** Header con cui il destinatario presenta la sessione di pairing. */
const val HEADER_SESSION = "X-Session"

/** Rotte del server, tutte sotto il capability token. */
object TransferRoutes {
    fun pair(token: String) = "/$token/pair"
    fun manifest(token: String) = "/$token/manifest"
    fun track(token: String, trackId: String) = "/$token/track/$trackId"
    fun art(token: String, trackId: String) = "/$token/art/$trackId"
    fun done(token: String) = "/$token/done"
}

/** Richiesta di pairing: chiave pubblica effimera del destinatario. */
@Serializable
data class PairRequest(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("publicKey") val publicKey: String,
    @SerialName("device") val device: String = ""
)

/** Risposta di pairing: chiave pubblica effimera del mittente + handle di sessione. */
@Serializable
data class PairResponse(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("publicKey") val publicKey: String,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("device") val device: String
)

/**
 * Un brano nel manifest. I campi replicano
 * [it.agoldoni.player.data.local.entity.Track] tranne `uri` e `albumArtPath`,
 * che sono percorsi locali del mittente e non hanno senso altrove.
 *
 * `id` è l'ID **di origine**: serve solo a chiedere il file e a ricostruire le
 * playlist; il destinatario genera i propri ID e tiene una mappa in memoria.
 */
@Serializable
data class ManifestTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val year: String? = null,
    val trackNumber: String? = null,
    val originalExtension: String = "mp3",
    val originalFileSize: Long = 0,
    val encryptedFileSize: Long = 0,
    val importedAt: Long = 0,
    val hasArt: Boolean = false
)

/** Riferimento a un brano dentro una playlist, con la data di aggiunta originale. */
@Serializable
data class ManifestPlaylistEntry(
    val trackId: String,
    val addedAt: Long
)

@Serializable
data class ManifestPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val lastPlayedTrackId: String? = null,
    val entries: List<ManifestPlaylistEntry> = emptyList()
)

/** Indice completo di ciò che il mittente mette a disposizione. */
@Serializable
data class TransferManifest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val device: String,
    val trackCount: Int,
    val totalBytes: Long,
    val tracks: List<ManifestTrack> = emptyList(),
    val playlists: List<ManifestPlaylist> = emptyList()
)

/** Esito comunicato dal destinatario a fine trasferimento (rotta `done`). */
@Serializable
data class TransferReport(
    val added: Int,
    val skipped: Int,
    val errors: Int,
    val cancelled: Boolean = false
)

/** Lanciata quando i due lati parlano versioni di protocollo diverse. */
class IncompatibleProtocolException(val theirVersion: Int) : Exception(
    "Le due installazioni parlano protocolli diversi (locale $PROTOCOL_VERSION, remoto $theirVersion). " +
        "Aggiorna l'app su entrambi i telefoni."
)

/**
 * `ignoreUnknownKeys` tiene in vita i client più vecchi di fronte a campi
 * aggiunti in futuro senza cambio di versione; i campi rimossi o reinterpretati
 * richiedono comunque un bump di [PROTOCOL_VERSION].
 */
val TransferJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun TransferManifest.encodeToJson(): String = TransferJson.encodeToString(TransferManifest.serializer(), this)

/** Decodifica il manifest verificando la compatibilità di protocollo. */
fun decodeManifest(json: String): TransferManifest {
    val manifest = TransferJson.decodeFromString(TransferManifest.serializer(), json)
    if (manifest.protocolVersion != PROTOCOL_VERSION) {
        throw IncompatibleProtocolException(manifest.protocolVersion)
    }
    return manifest
}
