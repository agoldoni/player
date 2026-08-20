package it.agoldoni.player.domain.transfer

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyPair
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransferServer"

/** Durata massima di un pairing non ancora confermato. */
private const val PAIRING_TTL_MS = 5 * 60 * 1000L

/** Tentativi di pairing rifiutati prima di rigenerare tutto e fermarsi. */
private const val MAX_REJECTED_ATTEMPTS = 3

/**
 * Stato del lato mittente, osservato dalla UI.
 */
sealed interface TransferServerState {
    data object Idle : TransferServerState
    data object Starting : TransferServerState

    /** Server in ascolto, nessun peer ancora collegato. */
    data class Ready(
        val host: String,
        val port: Int,
        val token: String,
        val trackCount: Int,
        val totalBytes: Long
    ) : TransferServerState

    /** Un peer ha completato l'handshake: l'utente deve confrontare [code]. */
    data class Pairing(
        val code: String,
        val peerDevice: String,
        val trackCount: Int,
        val totalBytes: Long
    ) : TransferServerState

    /** Trasferimento in corso: [served] file già consegnati su [total]. */
    data class Sending(
        val total: Int,
        val served: Int,
        val lastTitle: String?
    ) : TransferServerState

    /** Esito riportato dal destinatario. */
    data class Done(
        val added: Int,
        val skipped: Int,
        val errors: Int,
        val cancelled: Boolean
    ) : TransferServerState

    data class Failed(val message: String) : TransferServerState
}

/**
 * Lato mittente del trasferimento libreria-a-libreria.
 *
 * Espone sulla LAN le rotte di [TransferRoutes], si annuncia via mDNS
 * ([PeerDiscovery]) e serve i brani **ricifrati al volo**: il file viene
 * decifrato con la DEK locale e ricifrato con la chiave di sessione mentre
 * scorre verso il socket, senza mai materializzare il chiaro su disco.
 *
 * Il mittente fa da server per simmetria con `UploadServer` e perché è il
 * telefono che possiede i dati: il destinatario è quello che cerca.
 *
 * Sicurezza del pairing: il token in URL è solo un filtro d'ingresso; ciò che
 * protegge i dati è la chiave derivata da ECDH, e ciò che impedisce l'uomo in
 * mezzo è il codice a 6 cifre confrontato dall'utente sui due schermi
 * ([TransferCrypto]). Finché l'utente non conferma, ogni rotta di contenuto
 * risponde 409. Tre rifiuti e il server si ferma.
 *
 * Come la sync FTP e l'upload Wi-Fi, vive finché la schermata è aperta.
 */
@Singleton
class TransferServer @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val selectionResolver: TransferSelectionResolver,
    private val peerDiscovery: PeerDiscovery
) {
    private val _state = MutableStateFlow<TransferServerState>(TransferServerState.Idle)
    val state: StateFlow<TransferServerState> = _state.asStateFlow()

    private var engine: ApplicationEngine? = null
    private var resolved: ResolvedSelection? = null
    private var token: String? = null
    private var boundPort: Int? = null
    private var keyPair: KeyPair? = null

    @Volatile
    private var pairing: PairingSession? = null

    @Volatile
    private var rejectedAttempts = 0

    private class PairingSession(
        val sessionId: String,
        val session: TransferSession,
        val peerDevice: String,
        val createdAt: Long
    ) {
        @Volatile
        var approved: Boolean = false
    }

    /** Prepara il manifest, apre il server e si annuncia sulla rete. Idempotente. */
    suspend fun start(selection: TransferSelection) {
        if (engine != null) return
        _state.value = TransferServerState.Starting

        if (cryptoManager.sessionDek == null) {
            _state.value = TransferServerState.Failed(
                "Sessione scaduta. Riavvia l'app per autenticarti."
            )
            return
        }

        val selectionSnapshot = try {
            selectionResolver.resolve(selection)
        } catch (e: Exception) {
            Log.e(TAG, "Risoluzione della selezione fallita", e)
            _state.value = TransferServerState.Failed("Impossibile leggere la libreria: ${e.message}")
            return
        }

        if (selectionSnapshot.trackCount == 0) {
            _state.value = TransferServerState.Failed("Nessun brano da inviare con questa selezione.")
            return
        }

        val ip = NetworkUtils.getLocalIpAddress()
        if (ip == null) {
            _state.value = TransferServerState.Failed(
                "Nessuna connessione Wi-Fi rilevata. Collega i due telefoni alla stessa rete."
            )
            return
        }

        val port = NetworkUtils.firstFreePort(TRANSFER_PORT_RANGE)
        if (port == null) {
            _state.value = TransferServerState.Failed(
                "Nessuna porta disponibile nell'intervallo $TRANSFER_PORT_RANGE."
            )
            return
        }

        val newToken = NetworkUtils.generateToken()
        resolved = selectionSnapshot
        token = newToken
        boundPort = port
        keyPair = TransferCrypto.generateKeyPair()
        pairing = null
        rejectedAttempts = 0

        try {
            engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
                transferModule(newToken)
            }.start(wait = false)
        } catch (e: Exception) {
            Log.e(TAG, "Avvio server fallito", e)
            engine = null
            _state.value = TransferServerState.Failed("Avvio del server fallito: ${e.message}")
            return
        }

        peerDiscovery.register(port = port, token = newToken)

        _state.value = TransferServerState.Ready(
            host = ip,
            port = port,
            token = newToken,
            trackCount = selectionSnapshot.trackCount,
            totalBytes = selectionSnapshot.totalBytes
        )
    }

    /** L'utente ha verificato che i due codici coincidono: sblocca il trasferimento. */
    fun confirm() {
        val current = pairing ?: return
        current.approved = true
        val total = resolved?.trackCount ?: 0
        _state.value = TransferServerState.Sending(total = total, served = 0, lastTitle = null)
    }

    /**
     * L'utente ha rifiutato (codici diversi, oppure non era lui a collegarsi).
     * Le chiavi effimere e la sessione vengono rigenerate: un attaccante non
     * può accumulare tentativi utili. Dopo [MAX_REJECTED_ATTEMPTS] il server si ferma.
     */
    fun reject() {
        pairing = null
        rejectedAttempts++
        if (rejectedAttempts >= MAX_REJECTED_ATTEMPTS) {
            stop()
            _state.value = TransferServerState.Failed(
                "Troppi tentativi di collegamento rifiutati. Riavvia l'invio."
            )
            return
        }
        keyPair = TransferCrypto.generateKeyPair()
        val snapshot = resolved
        val currentToken = token
        val ip = NetworkUtils.getLocalIpAddress()
        val port = boundPort
        if (snapshot != null && currentToken != null && ip != null && port != null) {
            _state.value = TransferServerState.Ready(
                host = ip,
                port = port,
                token = currentToken,
                trackCount = snapshot.trackCount,
                totalBytes = snapshot.totalBytes
            )
        }
    }

    /**
     * Ferma il server e smette di annunciarsi, riportando lo stato a [TransferServerState.Idle].
     *
     * Il reset è necessario perché il server è un singleton mentre la schermata
     * va e viene: senza di esso, riaprendo "Invia libreria" si ritroverebbe il
     * riepilogo del trasferimento precedente invece della scelta dei contenuti.
     * Gli stati finali (Done/Failed) vivono quindi solo finché la schermata che
     * li ha prodotti resta aperta.
     */
    fun stop() {
        peerDiscovery.unregister()
        engine?.let { runCatching { it.stop(100, 500) } }
        engine = null
        pairing = null
        resolved = null
        token = null
        boundPort = null
        keyPair = null
        _state.value = TransferServerState.Idle
    }

    // ---------------------------------------------------------------- routing

    private fun Application.transferModule(expectedToken: String) {
        routing {
            post("/{token}/pair") {
                if (call.parameters["token"] != expectedToken) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                val body = runCatching {
                    TransferJson.decodeFromString(PairRequest.serializer(), call.receiveText())
                }.getOrNull()
                if (body == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                if (body.protocolVersion != PROTOCOL_VERSION) {
                    call.respondText(
                        "Versione di protocollo $PROTOCOL_VERSION richiesta",
                        status = HttpStatusCode.UpgradeRequired
                    )
                    return@post
                }
                val keys = keyPair
                if (keys == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@post
                }
                // Un pairing per volta: un secondo peer non può scavalcare il primo.
                if (pairing?.isUsable() == true) {
                    call.respond(HttpStatusCode.Conflict)
                    return@post
                }

                val peerKey: PublicKey = try {
                    TransferCrypto.decodePublicKey(body.publicKey)
                } catch (e: Exception) {
                    Log.w(TAG, "Chiave pubblica del peer illeggibile", e)
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

                val session = TransferCrypto.deriveSession(
                    ownPrivateKey = keys.private,
                    peerPublicKey = peerKey,
                    serverPublicKey = keys.public,
                    clientPublicKey = peerKey
                )
                val peerDevice = body.device.ifBlank { "Telefono sconosciuto" }
                val created = PairingSession(
                    sessionId = TransferCrypto.generateSessionId(),
                    session = session,
                    peerDevice = peerDevice,
                    createdAt = System.currentTimeMillis()
                )
                pairing = created

                val snapshot = resolved
                _state.value = TransferServerState.Pairing(
                    code = session.verificationCode,
                    peerDevice = peerDevice,
                    trackCount = snapshot?.trackCount ?: 0,
                    totalBytes = snapshot?.totalBytes ?: 0
                )

                call.respondText(
                    TransferJson.encodeToString(
                        PairResponse.serializer(),
                        PairResponse(
                            protocolVersion = PROTOCOL_VERSION,
                            publicKey = TransferCrypto.encodePublicKey(keys.public),
                            sessionId = created.sessionId,
                            device = TransferSelectionResolver.deviceName()
                        )
                    ),
                    ContentType.Application.Json
                )
            }

            get("/{token}/manifest") {
                val ctx = call.authorize(expectedToken) ?: return@get
                val snapshot = resolved
                if (snapshot == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@get
                }
                val payload = snapshot.manifest.encodeToJson().toByteArray(Charsets.UTF_8)
                val encrypted = cryptoManager.encryptBytes(
                    ctx.session.key,
                    ctx.session.nextNonce(),
                    payload
                )
                call.respondBytes(encrypted, ContentType.Application.OctetStream)
            }

            get("/{token}/track/{trackId}") {
                val ctx = call.authorize(expectedToken) ?: return@get
                val dek = cryptoManager.sessionDek
                if (dek == null) {
                    call.respond(HttpStatusCode.ServiceUnavailable)
                    return@get
                }
                val track = trackFor(call.parameters["trackId"])
                if (track == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val encryptedFile = File(track.uri)
                if (!encryptedFile.isFile) {
                    Log.w(TAG, "File mancante per il brano ${track.id}")
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                val nonce = ctx.session.nextNonce()
                // La dimensione in uscita coincide con quella del file su disco:
                // stesso schema [IV 12][cifrato][tag 16], solo con un'altra chiave.
                // Dichiararla evita il chunked encoding e dà al ricevente una
                // barra di avanzamento esatta.
                call.respondBytesWriter(
                    ContentType.Application.OctetStream,
                    contentLength = encryptedFile.length()
                ) {
                    val channel = this
                    withContext(Dispatchers.IO) {
                        cryptoManager.transcodeTo(
                            sourceKey = dek,
                            sourceFile = encryptedFile,
                            destKey = ctx.session.key,
                            destIv = nonce
                        ) { chunk ->
                            channel.writeFully(chunk)
                        }
                        channel.flush()
                    }
                }
                recordServed(track.title)
            }

            get("/{token}/art/{trackId}") {
                val ctx = call.authorize(expectedToken) ?: return@get
                val track = trackFor(call.parameters["trackId"])
                val artPath = track?.albumArtPath
                if (artPath == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val artFile = File(artPath)
                if (!artFile.isFile) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val bytes = withContext(Dispatchers.IO) { artFile.readBytes() }
                val encrypted = cryptoManager.encryptBytes(
                    ctx.session.key,
                    ctx.session.nextNonce(),
                    bytes
                )
                call.respondBytes(encrypted, ContentType.Application.OctetStream)
            }

            post("/{token}/done") {
                val ctx = call.authorize(expectedToken) ?: return@post
                val report = runCatching {
                    TransferJson.decodeFromString(TransferReport.serializer(), call.receiveText())
                }.getOrNull()
                if (report != null) {
                    _state.value = TransferServerState.Done(
                        added = report.added,
                        skipped = report.skipped,
                        errors = report.errors,
                        cancelled = report.cancelled
                    )
                }
                ctx.approved = false
                pairing = null
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    // ------------------------------------------------------------- supporto

    /**
     * Valida token, sessione, scadenza e conferma dell'utente. Risponde
     * direttamente con l'errore appropriato e ritorna null se la richiesta non
     * è autorizzata: `404` non rivela nemmeno l'esistenza del servizio,
     * `401` distingue la sessione assente, `409` il codice non ancora confermato.
     */
    private suspend fun ApplicationCall.authorize(expectedToken: String): PairingSession? {
        if (parameters["token"] != expectedToken) {
            respond(HttpStatusCode.NotFound)
            return null
        }
        val current = pairing
        val sessionId = request.headers[HEADER_SESSION]
        if (current == null || sessionId == null || sessionId != current.sessionId) {
            respond(HttpStatusCode.Unauthorized)
            return null
        }
        if (!current.isUsable()) {
            pairing = null
            respond(HttpStatusCode.Unauthorized)
            return null
        }
        if (!current.approved) {
            respond(HttpStatusCode.Conflict)
            return null
        }
        return current
    }

    private fun PairingSession.isUsable(): Boolean =
        System.currentTimeMillis() - createdAt <= PAIRING_TTL_MS

    private fun trackFor(trackId: String?): Track? =
        trackId?.let { resolved?.tracksById?.get(it) }

    private fun recordServed(title: String) {
        _state.update { current ->
            if (current is TransferServerState.Sending) {
                current.copy(served = current.served + 1, lastTitle = title)
            } else {
                current
            }
        }
    }
}
