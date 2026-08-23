package it.agoldoni.player.domain.webdav

import android.os.SystemClock
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.writeFully
import it.agoldoni.player.data.local.dao.TrackDao
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.domain.AesGcmStreams
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WebDavServer"

/** Distinto da `UploadServer` (8080..8090) e dal trasferimento (8091..8100). */
private val PORT_RANGE = 8101..8110

/** Validità della vista sulla libreria. Oltre, si rilegge il DB. */
private const val TREE_TTL_MS = 60_000L

/**
 * Il file su disco è `IV(12) || cifrato || tag(16)`: il chiaro è 28 byte in meno.
 * Derivato dalle costanti del formato e non scritto a mano, perché è il numero
 * da cui dipende la coerenza fra `getcontentlength` e i byte serviti.
 */
private const val GCM_OVERHEAD_BYTES = AesGcmStreams.IV_SIZE + AesGcmStreams.TAG_BITS / 8

private val PROPFIND = HttpMethod("PROPFIND")

private val ALLOWED_METHODS = "OPTIONS, HEAD, GET, PROPFIND"

/** Metodi di scrittura di WebDAV: il server è di sola lettura e li rifiuta tutti. */
private val WRITE_METHODS = listOf(
    "PUT", "DELETE", "MKCOL", "MOVE", "COPY", "LOCK", "UNLOCK", "PROPPATCH"
).map { HttpMethod(it) }

private val XML_CONTENT_TYPE = ContentType.Application.Xml.withCharset(Charsets.UTF_8)

/** Stato del server di esportazione, osservato dalla UI. */
sealed interface WebDavServerState {
    data object Idle : WebDavServerState
    data object Starting : WebDavServerState

    /** Server attivo: [url] è l'indirizzo (con token) da dare a rclone. */
    data class Running(
        val url: String,
        val trackCount: Int,
        val totalBytes: Long
    ) : WebDavServerState

    data class Failed(val message: String) : WebDavServerState
}

/**
 * Espone la libreria sulla rete locale come un filesystem WebDAV **di sola
 * lettura**, così che un PC possa scaricarne i brani con strumenti standard
 * (`rclone`, oppure `rsync` su una cartella montata con `rclone mount`).
 *
 * È l'unico dei server dell'app che va **verso l'esterno**: `UploadServer`
 * riceve da un browser e `TransferServer` parla solo con un'altra istanza.
 *
 * Perché WebDAV e non un protocollo nostro: il client lista una cartella con
 * `PROPFIND`, legge `getcontentlength` di ogni file e scarica solo ciò che nella
 * propria cartella manca o ha dimensione diversa. **Il confronto lo fa il PC**,
 * guardando la destinazione reale: l'app non tiene alcun indice di ciò che è già
 * stato esportato, e quindi non ha nulla che possa disallinearsi e mentire.
 *
 * L'albero (`Artista/Album/NN - Titolo.ext`) è una vista sui record di `tracks`,
 * costruita da [LibraryTree]; i file restano cifrati su disco e vengono decifrati
 * in streaming solo durante la `GET`.
 *
 * Come per l'upload Wi-Fi, il server vive finché la schermata "Esporta su PC" è
 * aperta (nessun foreground service) e tutte le rotte stanno sotto un token
 * casuale. A differenza del trasferimento fra istanze, **il traffico è in
 * chiaro**: dall'altra parte c'è rclone, che di un handshake ECDH non saprebbe
 * che farsene. La DEK non lascia comunque il device.
 */
@Singleton
class WebDavServer @Inject constructor(
    private val trackDao: TrackDao,
    private val cryptoManager: CryptoManager
) {
    private val _state = MutableStateFlow<WebDavServerState>(WebDavServerState.Idle)
    val state: StateFlow<WebDavServerState> = _state.asStateFlow()

    private var engine: ApplicationEngine? = null

    private val treeMutex = Mutex()

    @Volatile
    private var cachedTree: DavNode.Collection? = null

    @Volatile
    private var cachedAt = 0L

    /** Avvia il server e pubblica l'URL nello stato. Idempotente. */
    suspend fun start() {
        if (engine != null) return
        _state.value = WebDavServerState.Starting

        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            _state.value = WebDavServerState.Failed(
                "Nessuna connessione Wi-Fi rilevata. Connetti il telefono alla stessa rete del PC."
            )
            return
        }

        val port = NetworkUtils.firstFreePort(PORT_RANGE)
        if (port == null) {
            _state.value = WebDavServerState.Failed("Nessuna porta disponibile nell'intervallo $PORT_RANGE.")
            return
        }

        // Aprire la schermata deve dare una vista fresca, non quella di un'ora fa.
        val tracks = try {
            trackDao.getAllTracksOnce()
        } catch (e: Exception) {
            Log.e(TAG, "Lettura della libreria fallita", e)
            _state.value = WebDavServerState.Failed("Impossibile leggere la libreria.")
            return
        }
        treeMutex.withLock {
            cachedTree = LibraryTree.build(tracks)
            cachedAt = SystemClock.elapsedRealtime()
        }

        val token = NetworkUtils.generateToken()
        try {
            engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                webDavModule(token)
            }.start(wait = false)
        } catch (e: Exception) {
            Log.e(TAG, "Avvio server fallito", e)
            engine = null
            _state.value = WebDavServerState.Failed("Avvio del server fallito: ${e.message}")
            return
        }

        _state.value = WebDavServerState.Running(
            url = "http://$ip:$port/$token",
            trackCount = tracks.size,
            totalBytes = tracks.sumOf { plainSize(File(it.uri)) }
        )
    }

    /** Ferma il server e ripristina lo stato Idle (mantiene eventuale Failed). */
    fun stop() {
        engine?.let { runCatching { it.stop(100, 500) } }
        engine = null
        cachedTree = null
        cachedAt = 0L
        if (_state.value !is WebDavServerState.Failed) {
            _state.value = WebDavServerState.Idle
        }
    }

    // ---------------------------------------------------------------- routing

    private fun Application.webDavModule(token: String) {
        routing {
            davRoute(HttpMethod.Options) { respondOptions(call) }
            davRoute(PROPFIND) { respondPropfind(call, token) }
            davRoute(HttpMethod.Get) { respondFile(call, token, withBody = true) }
            davRoute(HttpMethod.Head) { respondFile(call, token, withBody = false) }
            WRITE_METHODS.forEach { method ->
                davRoute(method) { respondMethodNotAllowed(call) }
            }
        }
    }

    /**
     * Registra lo stesso handler sulla root (`/{token}`) e su tutto ciò che le sta
     * sotto. Il tailcard coprirebbe anche zero segmenti, ma dichiarare le due
     * rotte rende il percorso della root esplicito invece che dipendente da quel
     * dettaglio di risoluzione.
     */
    private fun Route.davRoute(
        method: HttpMethod,
        handler: suspend PipelineContext<Unit, ApplicationCall>.() -> Unit
    ) {
        route("/{token}", method) { handle { handler() } }
        route("/{token}/{path...}", method) { handle { handler() } }
    }

    private suspend fun respondOptions(call: ApplicationCall) {
        call.response.header("DAV", "1")
        call.response.header(HttpHeaders.Allow, ALLOWED_METHODS)
        call.respond(HttpStatusCode.OK)
    }

    private suspend fun respondMethodNotAllowed(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, ALLOWED_METHODS)
        call.respond(HttpStatusCode.MethodNotAllowed)
    }

    private suspend fun respondPropfind(call: ApplicationCall, token: String) {
        // Il corpo non viene interpretato — si risponde sempre con lo stesso set di
        // proprietà, che RFC 4918 §9.1 consente — ma va comunque consumato, o la
        // connessione resta disallineata per la richiesta successiva.
        runCatching { call.receiveChannel().discard(Long.MAX_VALUE) }

        val segments = call.davSegments(token) ?: return

        val depth = call.request.header("Depth")?.trim() ?: "1"
        if (depth.equals("infinity", ignoreCase = true)) {
            call.respondText(WebDavXml.finiteDepthError(), XML_CONTENT_TYPE, HttpStatusCode.Forbidden)
            return
        }

        val node = tree().resolve(segments)
        val self = node?.toResource(segments)
        if (self == null) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val resources = mutableListOf(self)
        if (depth != "0" && node is DavNode.Collection) {
            node.children.forEach { child ->
                child.toResource(segments + child.name)?.let { resources += it }
            }
        }

        call.respondText(
            WebDavXml.multiStatus(token, resources),
            XML_CONTENT_TYPE,
            HttpStatusCode.MultiStatus
        )
    }

    private suspend fun respondFile(call: ApplicationCall, token: String, withBody: Boolean) {
        val segments = call.davSegments(token) ?: return

        val node = tree().resolve(segments)
        if (node !is DavNode.TrackFile) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        // Non ci si fida della cache: fra la costruzione dell'albero e adesso il
        // brano può essere stato cancellato dalla libreria.
        val file = File(node.track.uri)
        if (!file.isFile) {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        val dek = cryptoManager.sessionDek
        if (dek == null) {
            call.respond(HttpStatusCode.ServiceUnavailable)
            return
        }

        val length = plainSize(file)
        val type = contentTypeOf(node.track)
        // Un seek su AES-GCM in streaming costringerebbe a decifrare e buttare via
        // tutto il prefisso: meglio dichiarare che le richieste parziali non ci sono.
        call.response.header(HttpHeaders.AcceptRanges, "none")

        if (!withBody) {
            call.respond(object : OutgoingContent.NoContent() {
                override val contentType: ContentType = type
                override val contentLength: Long = length
                override val status: HttpStatusCode = HttpStatusCode.OK
            })
            return
        }

        call.respondBytesWriter(type, contentLength = length) {
            val channel = this
            withContext(Dispatchers.IO) {
                cryptoManager.decryptTo(dek, file) { chunk -> channel.writeFully(chunk) }
                channel.flush()
            }
        }
    }

    /**
     * Verifica il capability token e ritorna il percorso richiesto, già decodificato
     * dal routing di Ktor. Un token errato riceve `404` e non `401`: non deve essere
     * distinguibile da una risorsa inesistente.
     */
    private suspend fun ApplicationCall.davSegments(expectedToken: String): List<String>? {
        if (parameters["token"] != expectedToken) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        return parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ vista

    private suspend fun tree(): DavNode.Collection = treeMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedTree
        if (cached != null && now - cachedAt < TREE_TTL_MS) return@withLock cached

        val fresh = LibraryTree.build(trackDao.getAllTracksOnce())
        cachedTree = fresh
        cachedAt = now
        fresh
    }

    /**
     * Traduce un nodo in una risorsa WebDAV. Ritorna null per un brano il cui file
     * cifrato non esiste più: meglio ometterlo dall'elenco che annunciarlo con
     * dimensione zero e far scaricare al client un file vuoto.
     */
    private fun DavNode.toResource(segments: List<String>): DavResource? = when (this) {
        is DavNode.Collection -> DavResource(
            segments = segments,
            isCollection = true,
            displayName = name,
            lastModified = lastModifiedOf(this)
        )

        is DavNode.TrackFile -> {
            val file = File(track.uri)
            if (!file.isFile) {
                null
            } else {
                DavResource(
                    segments = segments,
                    isCollection = false,
                    displayName = name,
                    contentLength = plainSize(file),
                    lastModified = track.importedAt,
                    contentType = contentTypeOf(track).toString()
                )
            }
        }
    }

    private fun lastModifiedOf(node: DavNode): Long = when (node) {
        is DavNode.TrackFile -> node.track.importedAt
        is DavNode.Collection -> node.children.maxOfOrNull { lastModifiedOf(it) } ?: 0L
    }

    /**
     * Dimensione del brano **in chiaro**, derivata dal file cifrato e non da
     * `Track.originalFileSize`: quella colonna vale `0` per i brani importati prima
     * della migrazione `1→2`, e un `getcontentlength` a zero renderebbe i file non
     * scaricabili in silenzio. Deve coincidere al byte con quanto serve la `GET`,
     * altrimenti rclone interrompe con "corrupted on transfer: sizes differ".
     */
    private fun plainSize(file: File): Long =
        (file.length() - GCM_OVERHEAD_BYTES).coerceAtLeast(0L)

    private fun contentTypeOf(track: Track): ContentType =
        when (track.originalExtension.lowercase()) {
            "flac" -> ContentType("audio", "flac")
            else -> ContentType("audio", "mpeg")
        }
}
