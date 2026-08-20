package it.agoldoni.player.domain

import android.util.Log
import it.agoldoni.player.data.repository.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

private const val TAG = "VerifyLibrary"

/** Un brano che non ha superato il controllo, con il motivo da mostrare. */
data class IntegrityProblem(
    val title: String,
    val artist: String,
    val reason: String
)

sealed interface VerifyProgress {
    data object Idle : VerifyProgress

    data class Running(
        val current: Int,
        val total: Int,
        val currentTitle: String,
        val ok: Int,
        val failed: Int
    ) : VerifyProgress

    data class Done(
        val ok: Int,
        val problems: List<IntegrityProblem>,
        val cancelled: Boolean = false
    ) : VerifyProgress

    data class Failed(val message: String) : VerifyProgress
}

/**
 * Verifica che ogni brano della libreria sia ancora decifrabile.
 *
 * Il controllo vero è il tag GCM: viene calcolato su tutto il contenuto e
 * verificato a fine decifratura, quindi un file troncato (trasferimento
 * interrotto, copia parziale) o alterato (memoria che degrada) fallisce.
 * Si controllano anche i file spariti e le dimensioni incoerenti col DB.
 *
 * Il chiaro non viene mai scritto da nessuna parte: i byte decifrati sono
 * contati e buttati.
 */
@Singleton
class VerifyLibraryUseCase @Inject constructor(
    private val trackRepository: TrackRepository,
    private val cryptoManager: CryptoManager
) {

    operator fun invoke(): Flow<VerifyProgress> = flow {
        val dek = cryptoManager.sessionDek
        if (dek == null) {
            emit(VerifyProgress.Failed("Sessione scaduta. Riavvia l'app per autenticarti."))
            return@flow
        }

        val tracks = trackRepository.getAllTracksOnce()
        if (tracks.isEmpty()) {
            emit(VerifyProgress.Done(ok = 0, problems = emptyList()))
            return@flow
        }

        val problems = mutableListOf<IntegrityProblem>()
        var ok = 0

        try {
            tracks.forEachIndexed { index, track ->
                coroutineContext.ensureActive()
                emit(
                    VerifyProgress.Running(
                        current = index + 1,
                        total = tracks.size,
                        currentTitle = track.title,
                        ok = ok,
                        failed = problems.size
                    )
                )

                val file = File(track.uri)
                val reason = when {
                    !file.isFile -> "file mancante sul dispositivo"
                    track.encryptedFileSize > 0 && file.length() != track.encryptedFileSize ->
                        "dimensione diversa da quella attesa " +
                            "(${file.length()} byte invece di ${track.encryptedFileSize})"
                    else -> verifyContent(track.uri, dek, track.originalFileSize)
                }

                if (reason == null) ok++ else {
                    problems += IntegrityProblem(track.title, track.artist, reason)
                    Log.w(TAG, "Brano non integro: ${track.title} — $reason")
                }
            }
            emit(VerifyProgress.Done(ok = ok, problems = problems))
        } catch (ce: CancellationException) {
            emit(VerifyProgress.Done(ok = ok, problems = problems, cancelled = true))
            throw ce
        }
    }.flowOn(Dispatchers.IO)

    /** Ritorna null se il brano è integro, altrimenti il motivo del problema. */
    private fun verifyContent(
        path: String,
        dek: javax.crypto.SecretKey,
        expectedOriginalSize: Long
    ): String? = try {
        val decrypted = cryptoManager.verifyFile(dek, File(path))
        // originalFileSize è 0 per i brani importati prima della migrazione 1→2:
        // in quel caso il confronto non ha nulla da dire.
        if (expectedOriginalSize > 0 && decrypted != expectedOriginalSize) {
            "contenuto più corto del previsto ($decrypted byte invece di $expectedOriginalSize)"
        } else {
            null
        }
    } catch (e: javax.crypto.AEADBadTagException) {
        "contenuto corrotto o incompleto (verifica crittografica fallita)"
    } catch (e: Exception) {
        Log.w(TAG, "Errore durante la verifica di $path", e)
        "impossibile leggere il file: ${e.message ?: e.javaClass.simpleName}"
    }
}
