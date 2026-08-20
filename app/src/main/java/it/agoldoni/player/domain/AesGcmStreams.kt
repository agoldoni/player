package it.agoldoni.player.domain

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Primitive AES-256-GCM su stream, con chiave e IV forniti dal chiamante.
 *
 * Vivono qui, e non dentro [CryptoManager], perché quella classe dipende da
 * `Context` e da AndroidKeystore e non è istanziabile negli unit test JVM.
 * La logica di cifratura, invece, è pura JCA: tenerla separata la rende
 * verificabile senza Robolectric. [CryptoManager] resta l'unica porta d'accesso
 * per il resto dell'app.
 *
 * Formato, identico a quello già usato per i file dei brani:
 * `[IV (12 byte)][dati cifrati + tag GCM]`.
 */
internal object AesGcmStreams {

    const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val IV_SIZE = 12
    const val TAG_BITS = 128
    const val BUFFER_SIZE = 64 * 1024

    fun encrypt(key: SecretKey, iv: ByteArray, source: InputStream, dest: OutputStream) {
        require(iv.size == IV_SIZE) { "IV di ${iv.size} byte, attesi $IV_SIZE" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        dest.write(iv)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = fill(source, buffer)
            if (read <= 0) break
            cipher.update(buffer, 0, read)?.let { dest.write(it) }
        }
        cipher.doFinal()?.let { dest.write(it) }
        dest.flush()
    }

    fun decrypt(key: SecretKey, source: InputStream, dest: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, readIv(source)))

        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = fill(source, buffer)
            if (read <= 0) break
            cipher.update(buffer, 0, read)?.let { dest.write(it) }
        }
        cipher.doFinal()?.let { dest.write(it) }
        dest.flush()
    }

    /**
     * Come [encrypt], ma invece di scrivere su un [OutputStream] consegna i
     * blocchi cifrati a [sink], che può sospendere.
     *
     * Serve al mittente: scrivere sul canale di Ktor passando da un
     * `OutputStream` bloccante costa carissimo sull'engine CIO (il ponte
     * suddivide in blocchi da 4 KB con un giro di sospensione ciascuno, ed è la
     * stessa patologia già annotata in `UploadServer` per il multipart).
     */
    suspend fun encryptTo(
        key: SecretKey,
        iv: ByteArray,
        source: InputStream,
        sink: suspend (ByteArray) -> Unit
    ) {
        require(iv.size == IV_SIZE) { "IV di ${iv.size} byte, attesi $IV_SIZE" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        sink(iv)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = fill(source, buffer)
            if (read <= 0) break
            val chunk = cipher.update(buffer, 0, read)
            if (chunk != null && chunk.isNotEmpty()) sink(chunk)
        }
        val last = cipher.doFinal()
        if (last != null && last.isNotEmpty()) sink(last)
    }

    fun encryptBytes(key: SecretKey, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(iv.size == IV_SIZE) { "IV di ${iv.size} byte, attesi $IV_SIZE" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /**
     * Decifra [sourceFile] con [sourceKey] e ricifra al volo con [destKey],
     * consegnando i blocchi a [sink]. È il cuore del trasferimento fra istanze:
     * il chiaro non tocca mai il disco e non viene mai materializzato tutto
     * insieme dal nostro codice.
     *
     * Legge a blocchi da 64 KB e non usa [CipherInputStream]: quello leggerebbe
     * 512 byte per volta e, siccome il provider AEAD di Android accumula
     * l'input fino a `doFinal`, ogni `update` finirebbe per ricopiare il buffer
     * già accumulato — costo quadratico nel numero di chiamate.
     */
    suspend fun transcodeTo(
        sourceKey: SecretKey,
        sourceFile: File,
        destKey: SecretKey,
        destIv: ByteArray,
        sink: suspend (ByteArray) -> Unit
    ) {
        require(destIv.size == IV_SIZE) { "IV di ${destIv.size} byte, attesi $IV_SIZE" }
        sourceFile.inputStream().buffered(BUFFER_SIZE).use { input ->
            val decrypt = Cipher.getInstance(TRANSFORMATION)
            decrypt.init(Cipher.DECRYPT_MODE, sourceKey, GCMParameterSpec(TAG_BITS, readIv(input)))

            val encrypt = Cipher.getInstance(TRANSFORMATION)
            encrypt.init(Cipher.ENCRYPT_MODE, destKey, GCMParameterSpec(TAG_BITS, destIv))

            sink(destIv)
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = fill(input, buffer)
                if (read > 0) {
                    val plain = decrypt.update(buffer, 0, read)
                    if (plain != null && plain.isNotEmpty()) {
                        encrypt.update(plain)?.takeIf { it.isNotEmpty() }?.let { sink(it) }
                    }
                }
                if (read < buffer.size) break
            }
            val lastPlain = decrypt.doFinal()
            if (lastPlain != null && lastPlain.isNotEmpty()) {
                encrypt.update(lastPlain)?.takeIf { it.isNotEmpty() }?.let { sink(it) }
            }
            encrypt.doFinal()?.takeIf { it.isNotEmpty() }?.let { sink(it) }
        }
    }

    /**
     * Stream che decifra al volo un file. Il chiamante deve chiuderlo: è la
     * chiusura a verificare il tag GCM.
     */
    fun decryptingStream(key: SecretKey, encryptedFile: File): InputStream {
        // Il buffer sotto non è un dettaglio: CipherInputStream legge a blocchi di
        // 512 byte, quindi senza di esso servirebbe una syscall ogni 512 byte.
        val input = encryptedFile.inputStream().buffered(BUFFER_SIZE)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, readIv(input)))
            CipherInputStream(input, cipher)
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    /**
     * Riempie [buffer] fino in fondo (o fino a EOF) prima di restituirlo.
     *
     * Non è un vezzo: il provider AEAD accumula l'input fino a `doFinal`, quindi
     * il costo cresce col **numero** di chiamate a `update`. Leggendo dalla rete,
     * una `read` può restituire pochi KB per volta: senza questo accorpamento un
     * brano da 7 MB genererebbe migliaia di update invece di un centinaio.
     */
    private fun fill(source: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = source.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private fun readIv(source: InputStream): ByteArray {
        val iv = ByteArray(IV_SIZE)
        var offset = 0
        while (offset < IV_SIZE) {
            val read = source.read(iv, offset, IV_SIZE - offset)
            if (read == -1) throw IOException("Stream troncato: IV incompleto")
            offset += read
        }
        return iv
    }
}
