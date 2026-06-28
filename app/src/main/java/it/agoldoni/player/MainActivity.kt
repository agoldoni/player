package it.agoldoni.player

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import it.agoldoni.player.domain.CryptoManager
import it.agoldoni.player.ui.BiometricGateScreen
import it.agoldoni.player.ui.PlayerApp
import it.agoldoni.player.ui.theme.PlayerTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var cryptoManager: CryptoManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Su build debug installate su emulatore salta il gate biometrico
        // (non simulabile) sbloccando direttamente la DEK, per poter testare.
        if (cryptoManager.canBypassBiometric) {
            runCatching { cryptoManager.autoUnlockForDebug() }
        }
        setContent {
            PlayerTheme {
                BiometricGate()
            }
        }
    }

    @Composable
    private fun BiometricGate() {
        // Sbloccato se: bypass emulatore già eseguito (sessionDek presente),
        // oppure primo utilizzo (DEK non ancora creata).
        var isUnlocked by remember {
            mutableStateOf(cryptoManager.sessionDek != null || !cryptoManager.isDekInitialized)
        }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        if (isUnlocked) {
            PlayerApp()
        } else {
            // Lancia il prompt biometrico automaticamente al primo render
            LaunchedEffect(Unit) {
                showBiometricPrompt(
                    onSuccess = { isUnlocked = true },
                    onError = { errorMessage = it }
                )
            }

            BiometricGateScreen(
                onUnlockClick = {
                    errorMessage = null
                    showBiometricPrompt(
                        onSuccess = { isUnlocked = true },
                        onError = { errorMessage = it }
                    )
                },
                errorMessage = errorMessage
            )
        }
    }

    private fun showBiometricPrompt(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val (cipher, isSetup) = cryptoManager.prepareBiometricCipher()
            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authedCipher = result.cryptoObject?.cipher ?: return
                        try {
                            cryptoManager.obtainDek(authedCipher, isSetup)
                            onSuccess()
                        } catch (e: Exception) {
                            onError("Errore durante lo sblocco della chiave")
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onError(errString.toString())
                    }
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Autenticazione richiesta")
                    .setSubtitle("Autenticati per accedere alla libreria")
                    .setNegativeButtonText("Annulla")
                    .build(),
                BiometricPrompt.CryptoObject(cipher)
            )
        } catch (e: Exception) {
            onError("Errore di autenticazione biometrica")
        }
    }
}
