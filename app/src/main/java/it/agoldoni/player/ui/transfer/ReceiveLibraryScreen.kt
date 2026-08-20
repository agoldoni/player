package it.agoldoni.player.ui.transfer

import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.domain.transfer.PeerDiscovery
import it.agoldoni.player.domain.transfer.TransferProgress

/**
 * "Ricevi libreria": trova il telefono mittente sulla rete (o accetta i dati a
 * mano), confronta il codice di verifica e importa quanto arriva.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveLibraryScreen(
    onBack: () -> Unit,
    viewModel: ReceiveLibraryViewModel = hiltViewModel()
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val discoveryError by viewModel.discoveryError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startDiscovery() }

    // La chiave del dispositivo può non esistere ancora: sul telefono appena
    // installato la libreria è vuota e il gate biometrico all'avvio non compare.
    // In quel caso il ViewModel chiede l'autenticazione, che crea la chiave.
    val context = LocalContext.current
    var authError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReceiveLibraryEvent.RequestBiometricAuth -> {
                    val activity = context.findActivity() as? FragmentActivity ?: return@collect
                    authError = null
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(context),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: BiometricPrompt.AuthenticationResult
                            ) {
                                val cipher = result.cryptoObject?.cipher ?: return
                                viewModel.onBiometricSuccess(cipher, event.isSetup)
                            }

                            override fun onAuthenticationError(
                                errorCode: Int,
                                errString: CharSequence
                            ) {
                                viewModel.onBiometricError(errString.toString())
                            }
                        }
                    )
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Autenticazione richiesta")
                            .setSubtitle("Autenticati per ricevere i brani")
                            .setNegativeButtonText("Annulla")
                            .build(),
                        event.cryptoObject
                    )
                }

                is ReceiveLibraryEvent.ShowError -> authError = event.message
            }
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.stopDiscovery()
            viewModel.cancel()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ricevi libreria") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (val current = progress) {
                is TransferProgress.Idle, is TransferProgress.Failed -> {
                    ((current as? TransferProgress.Failed)?.message ?: authError)?.let { messaggio ->
                        ErrorBanner(messaggio)
                        Spacer(Modifier.height(16.dp))
                    }
                    DiscoverySection(
                        peers = peers,
                        discoveryError = discoveryError,
                        onPeerClick = { peer ->
                            viewModel.connect(peer.host, peer.port, peer.token)
                        },
                        onManualConnect = { host, port, token ->
                            viewModel.connect(host, port, token)
                        }
                    )
                }

                is TransferProgress.Connecting -> Centered("Collegamento in corso…")

                is TransferProgress.AwaitingConfirmation -> ConfirmationSection(
                    code = current.code,
                    peerDevice = current.peerDevice,
                    onConfirm = viewModel::confirm,
                    onReject = viewModel::rejectPairing
                )

                is TransferProgress.WaitingForSender ->
                    Centered("In attesa della conferma sull'altro telefono…")

                is TransferProgress.Importing -> ImportingSection(current, onCancel = viewModel::cancel)

                is TransferProgress.Done -> DoneSection(current, onClose = onBack)
            }
        }
    }
}

@Composable
private fun ColumnScope.DiscoverySection(
    peers: List<PeerDiscovery.Peer>,
    discoveryError: String?,
    onPeerClick: (PeerDiscovery.Peer) -> Unit,
    onManualConnect: (String, Int, String) -> Unit
) {
    Text(
        "Apri \"Invia libreria\" sull'altro telefono: comparirà qui sotto.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))

    if (discoveryError != null) {
        Text(
            discoveryError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
    }

    if (peers.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                "  Ricerca in corso…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(peers, key = { it.token + it.host }) { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPeerClick(peer) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null)
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(peer.device, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${peer.host}:${peer.port}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))
    ManualEntry(onConnect = onManualConnect)
}

@Composable
private fun ManualEntry(onConnect: (String, Int, String) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Text("Collegamento manuale", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    Text(
        "Usa i dati mostrati dall'altro telefono se la ricerca automatica non lo trova.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = host,
        onValueChange = { host = it.trim() },
        label = { Text("Indirizzo IP") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() } },
            label = { Text("Porta") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Codice di accesso") },
            singleLine = true,
            modifier = Modifier.weight(1.5f)
        )
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { onConnect(host, port.toIntOrNull() ?: 0, token) },
        enabled = host.isNotBlank() && port.toIntOrNull() != null && token.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Collega")
    }
}

@Composable
private fun ColumnScope.ConfirmationSection(
    code: String,
    peerDevice: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Collegato a $peerDevice", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                code,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Verifica che lo stesso codice compaia sull'altro telefono, poi conferma su entrambi.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
    Spacer(Modifier.weight(1f))
    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
        Text("I codici coincidono")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
        Text("Annulla")
    }
}

@Composable
private fun ColumnScope.ImportingSection(state: TransferProgress.Importing, onCancel: () -> Unit) {
    Text("Ricezione in corso", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))

    val overall = if (state.total > 0) (state.current - 1).toFloat() / state.total else 0f
    LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("${state.current}/${state.total} brani", style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(16.dp))
    Text(
        state.currentTitle,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (state.fileTotalBytes > 0) {
        val fileFraction = (state.fileBytes.toFloat() / state.fileTotalBytes).coerceIn(0f, 1f)
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { fileFraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatBytes(state.fileBytes)} / ${formatBytes(state.fileTotalBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Aggiunti ${state.added} · già presenti ${state.skipped} · errori ${state.errors}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Interrompi")
    }
}

@Composable
private fun ColumnScope.DoneSection(state: TransferProgress.Done, onClose: () -> Unit) {
    Text(
        if (state.cancelled) "Ricezione interrotta" else "Ricezione completata",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(12.dp))
    Text("Brani aggiunti: ${state.added}")
    Text("Già presenti: ${state.skipped}")
    Text("Errori: ${state.errors}")
    Text("Playlist create o aggiornate: ${state.playlists}")
    if (state.cancelled) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Puoi rilanciare il trasferimento: i brani già ricevuti verranno saltati.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.weight(1f))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("Chiudi")
    }
}

@Composable
private fun ColumnScope.ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun Centered(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
