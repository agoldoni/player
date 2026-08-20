package it.agoldoni.player.domain.transfer

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Handshake e cifratura di sessione del trasferimento fra istanze.
 *
 * Perché non basta un PIN: un codice a 6 cifre ha ~20 bit di entropia e, se
 * fosse l'unica sorgente della chiave, chi cattura il traffico potrebbe
 * ricavarla offline in pochi secondi. Qui la chiave nasce invece da uno
 * **scambio ECDH effimero** (secp256r1, disponibile da API 26 — X25519
 * richiederebbe API 33) e il codice a 6 cifre è una *short authentication
 * string*: è derivato dal transcript dell'handshake e confrontato a vista
 * dall'utente sui due schermi. Un uomo-in-mezzo che sostituisse le chiavi
 * produrrebbe codici diversi sui due telefoni, e l'utente non confermerebbe.
 *
 * HKDF non esiste in JCA: è implementato qui sopra `HmacSHA256` secondo RFC 5869.
 *
 * Classe volutamente priva di dipendenze Android, così da restare testabile
 * negli unit test JVM (il progetto non ha Robolectric).
 */
object TransferCrypto {

    private const val KEY_ALGORITHM = "EC"
    private const val CURVE = "secp256r1"
    private const val AGREEMENT = "ECDH"
    private const val MAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    private const val INFO_SESSION_KEY = "player-transfer/v1 session-key"
    private const val INFO_VERIFICATION = "player-transfer/v1 verification-code"

    /** Cifre del codice di verifica mostrato all'utente. */
    const val CODE_DIGITS = 6

    private val secureRandom = SecureRandom()

    /** Coppia di chiavi effimera: vive quanto la schermata, mai persistita. */
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        generator.initialize(ECGenParameterSpec(CURVE), secureRandom)
        return generator.generateKeyPair()
    }

    fun encodePublicKey(key: PublicKey): String =
        Base64.getEncoder().encodeToString(key.encoded)

    fun decodePublicKey(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        return KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(bytes))
    }

    /**
     * Deriva la sessione condivisa. I due lati passano gli stessi
     * [serverPublicKey] e [clientPublicKey] (in questo ordine nel transcript),
     * scambiandosi solo il ruolo di [ownPrivateKey] / [peerPublicKey]: il
     * risultato — chiave e codice di verifica — è quindi identico su entrambi.
     */
    fun deriveSession(
        ownPrivateKey: PrivateKey,
        peerPublicKey: PublicKey,
        serverPublicKey: PublicKey,
        clientPublicKey: PublicKey
    ): TransferSession {
        val agreement = KeyAgreement.getInstance(AGREEMENT)
        agreement.init(ownPrivateKey)
        agreement.doPhase(peerPublicKey, true)
        val sharedSecret = agreement.generateSecret()

        // Il transcript entra come salt: lega la chiave alle esatte chiavi
        // pubbliche viste sul filo, ed è ciò che rende la SAS anti-MITM.
        val transcript = serverPublicKey.encoded + clientPublicKey.encoded
        val prk = hkdfExtract(salt = transcript, ikm = sharedSecret)

        val keyBytes = hkdfExpand(prk, INFO_SESSION_KEY, 32)
        val codeBytes = hkdfExpand(prk, INFO_VERIFICATION, 4)

        return TransferSession(
            key = SecretKeySpec(keyBytes, "AES"),
            verificationCode = toVerificationCode(codeBytes)
        )
    }

    /** RFC 5869 — HKDF-Extract. */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(MAC_ALGORITHM)
        val saltKey = if (salt.isEmpty()) ByteArray(HASH_LENGTH) else salt
        mac.init(SecretKeySpec(saltKey, MAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    /** RFC 5869 — HKDF-Expand, con `info` testuale (l'uso corrente). */
    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray =
        hkdfExpand(prk, info.toByteArray(Charsets.UTF_8), length)

    /** RFC 5869 — HKDF-Expand. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LENGTH) { "Lunghezza richiesta troppo grande per HKDF" }
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, MAC_ALGORITHM))

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter = 1

        while (generated < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()

            val toCopy = minOf(previous.size, length - generated)
            previous.copyInto(output, generated, 0, toCopy)
            generated += toCopy
            counter++
        }
        return output
    }

    /** Quattro byte → codice decimale a [CODE_DIGITS] cifre, zero-padded. */
    fun toVerificationCode(bytes: ByteArray): String {
        var value = 0L
        for (i in 0 until minOf(4, bytes.size)) {
            value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        }
        var modulo = 1L
        repeat(CODE_DIGITS) { modulo *= 10 }
        return (value % modulo).toString().padStart(CODE_DIGITS, '0')
    }

    /** Handle di sessione casuale, usato come header `X-Session`. */
    fun generateSessionId(): String {
        val bytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/**
 * Chiave di sessione più codice di verifica, con la fabbrica dei nonce.
 *
 * I nonce sono `[prefisso casuale 4 byte][contatore 8 byte]`: il contatore
 * garantisce l'unicità dentro la sessione (GCM non perdona il riuso di un
 * nonce con la stessa chiave), il prefisso casuale la separa dalle sessioni
 * precedenti. In v1 cifra solo il mittente, quindi un unico contatore basta.
 */
class TransferSession(
    val key: SecretKey,
    val verificationCode: String
) {
    private val noncePrefix = ByteArray(4).also { SecureRandom().nextBytes(it) }
    private val counter = AtomicLong(0)

    fun nextNonce(): ByteArray {
        val value = counter.getAndIncrement()
        val nonce = ByteArray(12)
        noncePrefix.copyInto(nonce, 0)
        for (i in 0 until 8) {
            nonce[4 + i] = ((value shr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return nonce
    }
}
