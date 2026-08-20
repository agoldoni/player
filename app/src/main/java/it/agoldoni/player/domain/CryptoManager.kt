package it.agoldoni.player.domain

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import it.agoldoni.player.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gestisce la cifratura dei file audio tramite envelope encryption:
 * - KEK (Key Encryption Key): chiave AES in Android Keystore, protetta da autenticazione biometrica
 * - DEK (Data Encryption Key): chiave AES generata casualmente, cifrata/wrappata con la KEK
 *
 * I file audio vengono cifrati con la DEK usando AES/GCM/NoPadding.
 * La DEK è accessibile solo dopo autenticazione biometrica (per sbloccare la KEK).
 */
@Singleton
class CryptoManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEK_ALIAS = "player_kek"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_SIZE = 12
        private const val GCM_TAG_BITS = 128
        private const val DEK_SIZE = 32 // AES-256
        private const val STREAM_BUFFER_SIZE = 64 * 1024

        /** Dimensione dell'IV GCM: utile a chi costruisce i nonce (es. TransferCrypto). */
        const val IV_SIZE = GCM_IV_SIZE
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val wrappedDekFile: File
        get() = File(context.filesDir, "crypto/wrapped_dek").also { it.parentFile?.mkdirs() }

    val isDekInitialized: Boolean
        get() = wrappedDekFile.exists()

    /** DEK in memoria per la durata della sessione app (processo). */
    var sessionDek: SecretKey? = null
        private set

    /**
     * Solo in build debug installate su emulatore: consente di saltare il gate
     * biometrico (assente/non simulabile su emulatore) creando la KEK senza
     * vincolo di autenticazione utente, così da poter testare in autonomia.
     * SEMPRE false in release: [BuildConfig.DEBUG] è false e la condizione
     * non viene mai valutata true sui dispositivi reali.
     */
    val canBypassBiometric: Boolean = BuildConfig.DEBUG && isEmulator()

    private fun ensureKekExists() {
        if (keyStore.containsAlias(KEK_ALIAS)) return
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                // Su emulatore debug la KEK non è vincolata all'autenticazione,
                // altrimenti obtainDek lancerebbe UserNotAuthenticatedException.
                if (!canBypassBiometric) {
                    setUserAuthenticationRequired(true)
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()
        keyGen.init(spec)
        keyGen.generateKey()
    }

    /**
     * Sblocca la DEK senza autenticazione biometrica. Utilizzabile solo quando
     * [canBypassBiometric] è true (debug su emulatore). No-op altrimenti.
     */
    fun autoUnlockForDebug() {
        if (!canBypassBiometric) return
        val (cipher, isSetup) = prepareBiometricCipher()
        obtainDek(cipher, isSetup)
    }

    private fun getKek(): SecretKey {
        ensureKekExists()
        return (keyStore.getEntry(KEK_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Prepara un Cipher per l'autenticazione biometrica via BiometricPrompt.
     * Ritorna il cipher e un flag che indica se è il setup iniziale (DEK non ancora generata).
     */
    fun prepareBiometricCipher(): Pair<Cipher, Boolean> {
        val kek = getKek()
        return if (wrappedDekFile.exists()) {
            val data = wrappedDekFile.readBytes()
            val iv = data.sliceArray(0 until GCM_IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher to false
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, kek)
            cipher to true
        }
    }

    /**
     * Ottiene la DEK dopo autenticazione biometrica riuscita.
     * Al primo utilizzo genera una nuova DEK e la cifra con la KEK.
     * Nelle chiamate successive decifra la DEK salvata.
     */
    fun obtainDek(authenticatedCipher: Cipher, isInitialSetup: Boolean): SecretKey {
        val dek = if (isInitialSetup) {
            val dekBytes = ByteArray(DEK_SIZE).also { SecureRandom().nextBytes(it) }
            val encrypted = authenticatedCipher.doFinal(dekBytes)
            val iv = authenticatedCipher.iv
            wrappedDekFile.writeBytes(iv + encrypted)
            SecretKeySpec(dekBytes, "AES")
        } else {
            val data = wrappedDekFile.readBytes()
            val encrypted = data.sliceArray(GCM_IV_SIZE until data.size)
            val dekBytes = authenticatedCipher.doFinal(encrypted)
            SecretKeySpec(dekBytes, "AES")
        }
        sessionDek = dek
        return dek
    }

    /**
     * Cifra un file con la DEK.
     * Formato output: [IV (12 byte)] [dati cifrati + GCM tag]
     */
    fun encryptFile(dek: SecretKey, source: File, dest: File) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dek)
        val iv = cipher.iv

        dest.outputStream().buffered(STREAM_BUFFER_SIZE).use { out ->
            out.write(iv)
            source.inputStream().buffered(STREAM_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val encrypted = cipher.update(buffer, 0, bytesRead)
                    if (encrypted != null) out.write(encrypted)
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null) out.write(finalBlock)
            }
        }
    }

    /**
     * Cifra un array di byte con la DEK.
     * Formato output: [IV (12 byte)] [dati cifrati + GCM tag]
     * Pensato per payload piccoli (es. credenziali FTP), non per file.
     */
    fun encryptBytes(dek: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, dek)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    /**
     * Decifra un array di byte prodotto da [encryptBytes].
     */
    fun decryptBytes(dek: SecretKey, encrypted: ByteArray): ByteArray {
        val iv = encrypted.sliceArray(0 until GCM_IV_SIZE)
        val ciphertext = encrypted.sliceArray(GCM_IV_SIZE until encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Decifra un file cifrato con la DEK in un file temporaneo.
     * Formato input: [IV (12 byte)] [dati cifrati + GCM tag]
     * [extension] è l'estensione originale del file audio (senza punto): serve
     * a dare al file temporaneo un nome coerente col formato, così che
     * [android.media.MediaPlayer] possa individuare il decoder corretto.
     * Ritorna il file temporaneo decifrato.
     */
    fun decryptToTempFile(dek: SecretKey, encryptedFile: File, extension: String = "mp3"): File {
        val tempFile = File.createTempFile("playback_", ".$extension", context.cacheDir)

        encryptedFile.inputStream().buffered(STREAM_BUFFER_SIZE).use { input ->
            val iv = ByteArray(GCM_IV_SIZE)
            input.read(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))

            tempFile.outputStream().buffered(STREAM_BUFFER_SIZE).use { out ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val decrypted = cipher.update(buffer, 0, bytesRead)
                    if (decrypted != null) out.write(decrypted)
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null) out.write(finalBlock)
            }
        }

        return tempFile
    }

    /**
     * Cifra [source] su [dest] usando una chiave **arbitraria** (non la DEK) e un
     * [iv] fornito dal chiamante, che resta responsabile della sua unicità.
     * Formato output: [IV (12 byte)] [dati cifrati + GCM tag], identico a
     * [encryptFile], così che [decryptStream] sia il suo inverso esatto.
     *
     * Serve al trasferimento fra istanze: il brano viene decifrato con la DEK
     * locale e ricifrato con la chiave di sessione **mentre scorre verso il
     * socket**, senza materializzare il file in chiaro su disco.
     *
     * Né [source] né [dest] vengono chiusi: il ciclo di vita resta al chiamante.
     */
    fun encryptStream(key: SecretKey, iv: ByteArray, source: InputStream, dest: OutputStream) =
        AesGcmStreams.encrypt(key, iv, source, dest)

    /**
     * Verifica che [encryptedFile] sia decifrabile con [key] e ritorna la
     * dimensione del contenuto in chiaro. Lancia `AEADBadTagException` se il
     * file è troncato o alterato: è il controllo su cui si basa la verifica di
     * integrità della libreria.
     */
    fun verifyFile(key: SecretKey, encryptedFile: File): Long =
        AesGcmStreams.verify(key, encryptedFile)

    /**
     * Decifra un file con [sourceKey] e lo ricifra con [destKey] consegnando i
     * blocchi a [sink]: il percorso usato dal mittente del trasferimento per
     * alimentare il canale di rete.
     */
    suspend fun transcodeTo(
        sourceKey: SecretKey,
        sourceFile: File,
        destKey: SecretKey,
        destIv: ByteArray,
        sink: suspend (ByteArray) -> Unit
    ) = AesGcmStreams.transcodeTo(sourceKey, sourceFile, destKey, destIv, sink)

    /**
     * Variante di [encryptStream] che consegna i blocchi cifrati a [sink]
     * invece di scriverli su uno stream: usata dal trasferimento per alimentare
     * direttamente il canale di rete, senza ponti bloccanti.
     */
    suspend fun encryptStreamTo(
        key: SecretKey,
        iv: ByteArray,
        source: InputStream,
        sink: suspend (ByteArray) -> Unit
    ) = AesGcmStreams.encryptTo(key, iv, source, sink)

    /**
     * Inverso di [encryptStream]: legge l'IV dai primi 12 byte di [source] e
     * scrive il chiaro su [dest].
     *
     * Nota: come già accade per [decryptToTempFile], il chiaro viene emesso a
     * blocchi **prima** della verifica del tag GCM, che avviene solo alla fine.
     * Chi consuma l'output non deve fidarsi dei dati finché la chiamata non è
     * ritornata senza eccezioni.
     */
    fun decryptStream(key: SecretKey, source: InputStream, dest: OutputStream) =
        AesGcmStreams.decrypt(key, source, dest)

    /**
     * Ritorna uno stream che decifra al volo [encryptedFile] con [key].
     * Il chiamante deve chiuderlo: la chiusura verifica il tag GCM e lancia
     * `IOException` se il file è stato manomesso.
     */
    fun decryptingStream(key: SecretKey, encryptedFile: File): InputStream =
        AesGcmStreams.decryptingStream(key, encryptedFile)

    /**
     * Come [encryptBytes], ma con chiave e IV forniti dal chiamante.
     * Pensato per i payload piccoli del trasferimento (manifest, copertine),
     * dove i nonce sono gestiti centralmente da chi possiede la sessione.
     */
    fun encryptBytes(key: SecretKey, iv: ByteArray, plaintext: ByteArray): ByteArray =
        AesGcmStreams.encryptBytes(key, iv, plaintext)

    /** Riconosce l'esecuzione su emulatore Android (goldfish/ranchu o build SDK). */
    private fun isEmulator(): Boolean {
        return Build.HARDWARE.contains("goldfish")
            || Build.HARDWARE.contains("ranchu")
            || Build.FINGERPRINT.startsWith("generic")
            || Build.FINGERPRINT.contains("emulator")
            || Build.FINGERPRINT.contains("sdk_gphone")
            || Build.MODEL.contains("sdk_gphone")
            || Build.PRODUCT.contains("sdk")
    }
}
