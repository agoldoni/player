package it.agoldoni.player.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

class AesGcmStreamsTest {

    private fun key(seed: Byte) = SecretKeySpec(ByteArray(32) { seed }, "AES")

    private fun nonce(seed: Byte) = ByteArray(12) { seed }

    private fun payload(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun encrypt(keySeed: Byte, nonceSeed: Byte, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AesGcmStreams.encrypt(key(keySeed), nonce(nonceSeed), ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    private fun decrypt(keySeed: Byte, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        AesGcmStreams.decrypt(key(keySeed), ByteArrayInputStream(data), out)
        return out.toByteArray()
    }

    @Test
    fun `cifrare e decifrare uno stream restituisce i dati originali`() {
        // Oltre la soglia del buffer da 64 KB, per esercitare più giri di update().
        val data = payload(300_000)
        val cipherText = encrypt(1, 7, data)

        assertEquals(12 + data.size + 16, cipherText.size)
        assertArrayEquals(nonce(7), cipherText.copyOfRange(0, 12))
        assertArrayEquals(data, decrypt(1, cipherText))
    }

    @Test
    fun `il testo cifrato non contiene il chiaro`() {
        val data = "sequenza riconoscibile".repeat(100).toByteArray()
        val cipherText = encrypt(1, 3, data)
        assertTrue(!String(cipherText, Charsets.ISO_8859_1).contains("sequenza riconoscibile"))
    }

    @Test
    fun `una chiave sbagliata fa fallire la verifica del tag`() {
        val cipherText = encrypt(1, 5, payload(1024))
        assertThrows(AEADBadTagException::class.java) { decrypt(2, cipherText) }
    }

    @Test
    fun `un byte manomesso fa fallire la verifica del tag`() {
        val cipherText = encrypt(1, 5, payload(1024))
        cipherText[cipherText.size / 2] = (cipherText[cipherText.size / 2] + 1).toByte()
        assertThrows(AEADBadTagException::class.java) { decrypt(1, cipherText) }
    }

    @Test
    fun `un nonce sbagliato fa fallire la verifica del tag`() {
        val cipherText = encrypt(1, 5, payload(1024))
        nonce(9).copyInto(cipherText, 0)
        assertThrows(AEADBadTagException::class.java) { decrypt(1, cipherText) }
    }

    @Test
    fun `encryptBytes produce lo stesso formato dello stream`() {
        val data = payload(2048)
        val fromBytes = AesGcmStreams.encryptBytes(key(1), nonce(4), data)
        assertArrayEquals(encrypt(1, 4, data), fromBytes)
        assertArrayEquals(data, decrypt(1, fromBytes))
    }

    @Test
    fun `decryptingStream decifra un file al volo`() {
        val data = payload(150_000)
        val file = File.createTempFile("gcm_", ".bin").apply { deleteOnExit() }
        file.writeBytes(encrypt(1, 2, data))

        val decrypted = AesGcmStreams.decryptingStream(key(1), file).use { it.readBytes() }

        assertArrayEquals(data, decrypted)
    }

    @Test
    fun `verify accetta un file integro e ne riporta la dimensione in chiaro`() {
        val data = payload(120_000)
        val file = File.createTempFile("verify_ok_", ".bin").apply { deleteOnExit() }
        file.writeBytes(encrypt(1, 2, data))

        assertEquals(data.size.toLong(), AesGcmStreams.verify(key(1), file))
    }

    @Test
    fun `verify smaschera un file troncato a meta`() {
        // È il caso del trasferimento interrotto: byte validi, ma manca la coda col tag.
        val cipherText = encrypt(1, 2, payload(120_000))
        val file = File.createTempFile("verify_troncato_", ".bin").apply { deleteOnExit() }
        file.writeBytes(cipherText.copyOf(cipherText.size / 2))

        assertThrows(AEADBadTagException::class.java) { AesGcmStreams.verify(key(1), file) }
    }

    @Test
    fun `verify smaschera un byte alterato nel mezzo`() {
        val cipherText = encrypt(1, 2, payload(120_000))
        cipherText[cipherText.size / 3] = (cipherText[cipherText.size / 3] + 1).toByte()
        val file = File.createTempFile("verify_alterato_", ".bin").apply { deleteOnExit() }
        file.writeBytes(cipherText)

        assertThrows(AEADBadTagException::class.java) { AesGcmStreams.verify(key(1), file) }
    }

    @Test
    fun `un IV di dimensione errata viene rifiutato subito`() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcmStreams.encrypt(
                key(1),
                ByteArray(8),
                ByteArrayInputStream(ByteArray(4)),
                ByteArrayOutputStream()
            )
        }
    }

    @Test
    fun `decryptTo consegna il chiaro a blocchi`() = runBlocking {
        val data = payload(300_000)
        val file = File.createTempFile("decrypt_to_", ".bin").apply { deleteOnExit() }
        file.writeBytes(encrypt(1, 2, data))

        val out = ByteArrayOutputStream()
        var blocchi = 0
        AesGcmStreams.decryptTo(key(1), file) { chunk ->
            blocchi++
            out.write(chunk)
        }

        assertArrayEquals(data, out.toByteArray())
        // I blocchi restano proporzionali al buffer da 64 KB, non ai 512 byte di
        // CipherInputStream: è ciò che tiene lineare il numero di update().
        assertTrue("blocchi consegnati: $blocchi", blocchi <= 16)
    }

    @Test
    fun `decryptTo smaschera un file alterato`() {
        val cipherText = encrypt(1, 2, payload(120_000))
        cipherText[cipherText.size / 3] = (cipherText[cipherText.size / 3] + 1).toByte()
        val file = File.createTempFile("decrypt_to_alterato_", ".bin").apply { deleteOnExit() }
        file.writeBytes(cipherText)

        assertThrows(AEADBadTagException::class.java) {
            runBlocking { AesGcmStreams.decryptTo(key(1), file) { } }
        }
    }
}
