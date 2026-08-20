package it.agoldoni.player.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferCryptoTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    @Test
    fun `i due lati derivano la stessa chiave e lo stesso codice`() {
        val server = TransferCrypto.generateKeyPair()
        val client = TransferCrypto.generateKeyPair()

        val serverSide = TransferCrypto.deriveSession(
            ownPrivateKey = server.private,
            peerPublicKey = client.public,
            serverPublicKey = server.public,
            clientPublicKey = client.public
        )
        val clientSide = TransferCrypto.deriveSession(
            ownPrivateKey = client.private,
            peerPublicKey = server.public,
            serverPublicKey = server.public,
            clientPublicKey = client.public
        )

        assertEquals(hex(serverSide.key.encoded), hex(clientSide.key.encoded))
        assertEquals(serverSide.verificationCode, clientSide.verificationCode)
        assertEquals(32, serverSide.key.encoded.size)
    }

    @Test
    fun `la chiave pubblica sopravvive alla codifica base64`() {
        val pair = TransferCrypto.generateKeyPair()
        val decoded = TransferCrypto.decodePublicKey(TransferCrypto.encodePublicKey(pair.public))
        assertEquals(hex(pair.public.encoded), hex(decoded.encoded))
    }

    @Test
    fun `un uomo in mezzo produce codici diversi sui due telefoni`() {
        val server = TransferCrypto.generateKeyPair()
        val client = TransferCrypto.generateKeyPair()
        val attacker = TransferCrypto.generateKeyPair()

        // Il mittente crede di parlare con l'attaccante...
        val onSender = TransferCrypto.deriveSession(
            ownPrivateKey = server.private,
            peerPublicKey = attacker.public,
            serverPublicKey = server.public,
            clientPublicKey = attacker.public
        )
        // ...e il destinatario crede che l'attaccante sia il mittente.
        val onReceiver = TransferCrypto.deriveSession(
            ownPrivateKey = client.private,
            peerPublicKey = attacker.public,
            serverPublicKey = attacker.public,
            clientPublicKey = client.public
        )

        // I codici mostrati sui due schermi non coincidono: l'utente non conferma.
        assertNotEquals(onSender.verificationCode, onReceiver.verificationCode)
    }

    @Test
    fun `il codice di verifica ha sei cifre decimali`() {
        repeat(50) {
            val server = TransferCrypto.generateKeyPair()
            val client = TransferCrypto.generateKeyPair()
            val session = TransferCrypto.deriveSession(
                server.private, client.public, server.public, client.public
            )
            assertTrue(
                "Codice inatteso: ${session.verificationCode}",
                session.verificationCode.matches(Regex("^\\d{6}$"))
            )
        }
    }

    @Test
    fun `hkdf rispetta il vettore di test 1 della RFC 5869`() {
        val ikm = fromHex("0b".repeat(22))
        val salt = fromHex("000102030405060708090a0b0c")
        val info = fromHex("f0f1f2f3f4f5f6f7f8f9")

        val prk = TransferCrypto.hkdfExtract(salt, ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(prk))

        val okm = TransferCrypto.hkdfExpand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(okm)
        )
    }

    @Test
    fun `i nonce di una sessione non si ripetono mai`() {
        val server = TransferCrypto.generateKeyPair()
        val client = TransferCrypto.generateKeyPair()
        val session = TransferCrypto.deriveSession(
            server.private, client.public, server.public, client.public
        )

        val nonces = (1..2000).map { hex(session.nextNonce()) }
        assertEquals(2000, nonces.toSet().size)
        assertTrue(nonces.all { it.length == 24 })
    }

    @Test
    fun `il codice deriva dai byte con zero padding`() {
        assertEquals("000000", TransferCrypto.toVerificationCode(byteArrayOf(0, 0, 0, 0)))
        assertEquals("000042", TransferCrypto.toVerificationCode(byteArrayOf(0, 0, 0, 42)))
    }

    @Test
    fun `sessioni diverse hanno handle diversi`() {
        val ids = (1..200).map { TransferCrypto.generateSessionId() }
        assertEquals(200, ids.toSet().size)
    }
}
