package it.agoldoni.player.domain.transfer

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.toInputStream
import it.agoldoni.player.domain.CryptoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransferClient"

/** Intervallo fra due tentativi mentre si attende la conferma sul mittente. */
private const val CONFIRM_POLL_INTERVAL_MS = 1_500L

/** Attesa massima della conferma dell'utente sul telefono mittente. */
private const val CONFIRM_TIMEOUT_MS = 5 * 60 * 1000L

/** Il mittente non ha (ancora) confermato il codice di verifica. */
class PairingNotConfirmedException : Exception("Conferma non ricevuta dal telefono mittente.")

/** La sessione di pairing è scaduta o è stata invalidata dal mittente. */
class SessionRejectedException : Exception("Il telefono mittente ha interrotto il collegamento.")

/**
 * Lato destinatario: parla con il [TransferServer] dell'altro telefono.
 *
 * Ogni payload che arriva è cifrato con la chiave di sessione derivata durante
 * il pairing: qui viene decifrato in streaming verso un file temporaneo, che
 * poi [ReceiveLibraryUseCase] passa alla pipeline di import.
 */
@Singleton
class TransferClient @Inject constructor(
    private val cryptoManager: CryptoManager
) {

    /** Sessione stabilita con un mittente, da passare a tutte le chiamate successive. */
    data class Connection(
        val baseUrl: String,
        val token: String,
        val sessionId: String,
        val session: TransferSession,
        val peerDevice: String
    )

    private var client: HttpClient? = null

    private fun client(): HttpClient = client ?: HttpClient(CIO) {
        engine {
            // I brani possono essere decine di MB: nessun timeout sulla richiesta,
            // l'utente interrompe annullando la schermata.
            requestTimeout = 0
        }
    }.also { client = it }

    fun close() {
        runCatching { client?.close() }
        client = null
    }

    /**
     * Handshake ECDH col mittente. Ritorna la connessione e, dentro di essa, il
     * codice a 6 cifre che l'utente deve confrontare con quello mostrato
     * sull'altro telefono.
     */
    suspend fun pair(host: String, port: Int, token: String): Connection {
        val keyPair = TransferCrypto.generateKeyPair()
        val baseUrl = "http://$host:$port"

        val response = client().post(baseUrl + TransferRoutes.pair(token)) {
            contentType(ContentType.Application.Json)
            setBody(
                TransferJson.encodeToString(
                    PairRequest.serializer(),
                    PairRequest(
                        protocolVersion = PROTOCOL_VERSION,
                        publicKey = TransferCrypto.encodePublicKey(keyPair.public),
                        device = TransferSelectionResolver.deviceName()
                    )
                )
            )
        }

        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.UpgradeRequired -> throw IncompatibleProtocolException(-1)
            HttpStatusCode.Conflict -> throw Exception(
                "Il telefono mittente sta già parlando con un altro dispositivo."
            )
            HttpStatusCode.NotFound -> throw Exception(
                "Codice di accesso errato: controlla i dati mostrati sul telefono mittente."
            )
            else -> throw Exception("Collegamento rifiutato (${response.status.value}).")
        }

        val body = TransferJson.decodeFromString(PairResponse.serializer(), response.bodyAsText())
        if (body.protocolVersion != PROTOCOL_VERSION) throw IncompatibleProtocolException(body.protocolVersion)

        val serverPublicKey = TransferCrypto.decodePublicKey(body.publicKey)
        val session = TransferCrypto.deriveSession(
            ownPrivateKey = keyPair.private,
            peerPublicKey = serverPublicKey,
            serverPublicKey = serverPublicKey,
            clientPublicKey = keyPair.public
        )

        return Connection(
            baseUrl = baseUrl,
            token = token,
            sessionId = body.sessionId,
            session = session,
            peerDevice = body.device.ifBlank { "Telefono sconosciuto" }
        )
    }

    /**
     * Scarica il manifest, attendendo che l'utente confermi il codice sul
     * telefono mittente (finché non lo fa, il server risponde 409).
     */
    suspend fun awaitManifest(connection: Connection): TransferManifest {
        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        while (true) {
            val response = client().get(connection.baseUrl + TransferRoutes.manifest(connection.token)) {
                header(HEADER_SESSION, connection.sessionId)
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val plain = cryptoManager.decryptBytes(connection.session.key, response.readBytes())
                    return decodeManifest(plain.toString(Charsets.UTF_8))
                }
                HttpStatusCode.Conflict -> {
                    if (System.currentTimeMillis() > deadline) throw PairingNotConfirmedException()
                    delay(CONFIRM_POLL_INTERVAL_MS)
                }
                HttpStatusCode.Unauthorized -> throw SessionRejectedException()
                else -> throw Exception("Manifest non disponibile (${response.status.value}).")
            }
        }
    }

    /**
     * Scarica un brano e lo scrive **già decifrato** in [dest].
     * [onBytes] riceve i byte cumulativi ricevuti dalla rete, per la barra di avanzamento.
     */
    suspend fun downloadTrack(
        connection: Connection,
        trackId: String,
        dest: File,
        onBytes: (Long) -> Unit
    ) {
        client().prepareGet(connection.baseUrl + TransferRoutes.track(connection.token, trackId)) {
            header(HEADER_SESSION, connection.sessionId)
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> Unit
                HttpStatusCode.Unauthorized -> throw SessionRejectedException()
                else -> throw Exception("Brano non disponibile (${response.status.value}).")
            }
            val input = response.bodyAsChannel().toInputStream()
            withContext(Dispatchers.IO) {
                input.use { network ->
                    dest.outputStream().buffered(64 * 1024).use { out ->
                        cryptoManager.decryptStream(
                            connection.session.key,
                            CountingInputStream(network, onBytes),
                            out
                        )
                    }
                }
            }
        }
    }

    /** Copertina del brano, o null se il mittente non ne ha una. */
    suspend fun downloadArt(connection: Connection, trackId: String): ByteArray? {
        val response = client().get(connection.baseUrl + TransferRoutes.art(connection.token, trackId)) {
            header(HEADER_SESSION, connection.sessionId)
        }
        return when (response.status) {
            HttpStatusCode.OK -> runCatching {
                cryptoManager.decryptBytes(connection.session.key, response.readBytes())
            }.getOrElse {
                Log.w(TAG, "Copertina di $trackId illeggibile", it)
                null
            }
            HttpStatusCode.NotFound -> null
            else -> null
        }
    }

    /** Comunica l'esito al mittente e chiude la sessione. Errori qui non sono fatali. */
    suspend fun report(connection: Connection, report: TransferReport) {
        runCatching {
            client().post(connection.baseUrl + TransferRoutes.done(connection.token)) {
                header(HEADER_SESSION, connection.sessionId)
                contentType(ContentType.Application.Json)
                setBody(TransferJson.encodeToString(TransferReport.serializer(), report))
            }
        }.onFailure { Log.w(TAG, "Invio del riepilogo al mittente fallito", it) }
    }
}

/** Conta i byte letti e li riporta al chiamante, per l'avanzamento in UI. */
private class CountingInputStream(
    private val delegate: InputStream,
    private val onBytes: (Long) -> Unit
) : InputStream() {
    private var total = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value != -1) {
            total++
            onBytes(total)
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) {
            total += read
            onBytes(total)
        }
        return read
    }

    override fun close() = delegate.close()
}
