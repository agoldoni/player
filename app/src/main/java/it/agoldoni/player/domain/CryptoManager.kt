package it.agoldoni.player.domain

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private val wrappedDekFile: File
        get() = File(context.filesDir, "crypto/wrapped_dek").also { it.parentFile?.mkdirs() }

    val isDekInitialized: Boolean
        get() = wrappedDekFile.exists()

    /** DEK in memoria per la durata della sessione app (processo). */
    var sessionDek: SecretKey? = null
        private set

    private fun ensureKekExists() {
        if (keyStore.containsAlias(KEK_ALIAS)) return
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        keyGen.generateKey()
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

        dest.outputStream().use { out ->
            out.write(iv)
            source.inputStream().use { input ->
                val buffer = ByteArray(8192)
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

        encryptedFile.inputStream().use { input ->
            val iv = ByteArray(GCM_IV_SIZE)
            input.read(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))

            tempFile.outputStream().use { out ->
                val buffer = ByteArray(8192)
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
}
