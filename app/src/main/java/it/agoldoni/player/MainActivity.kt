package it.agoldoni.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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

    // La notifica media richiede POST_NOTIFICATIONS su Android 13+. Il rifiuto non blocca
    // la riproduzione: si perde solo la notifica visibile (i controlli lock screen restano).
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* esito ignorato */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        maybeRequestIgnoreBatteryOptimizations()
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

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Chiede l'esenzione dall'ottimizzazione batteria (whitelist Doze). Senza di essa i
    // sistemi aggressivi (es. MIUI) uccidono il processo dopo alcuni minuti a schermo
    // bloccato, fermando la riproduzione. Il dialog di sistema compare una sola volta:
    // se già esente non facciamo nulla. NB: su MIUI serve anche abilitare "Autostart" e
    // impostare la batteria su "Nessuna restrizione" dalle impostazioni di sistema.
    private fun maybeRequestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
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
