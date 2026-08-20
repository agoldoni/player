package it.agoldoni.player.ui.transfer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.domain.transfer.TransferSelection
import it.agoldoni.player.domain.transfer.TransferServerState

private enum class SelectionMode { LIBRARY, PLAYLISTS, TRACKS }

/**
 * "Invia libreria": espone i brani sulla rete locale e guida l'utente nel
 * confronto del codice di verifica col telefono ricevente.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLibraryScreen(
    onBack: () -> Unit,
    viewModel: SendLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()


    // La chiave del dispositivo può non esistere ancora: sul telefono appena
    // installato la libreria è vuota e il gate biometrico all'avvio non compare.
    // In quel caso il ViewModel chiede l'autenticazione, che crea la chiave.
    val context = LocalContext.current
    var authError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SendLibraryEvent.RequestBiometricAuth -> {
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
                            .setSubtitle("Autenticati per inviare i brani")
                            .setNegativeButtonText("Annulla")
                            .build(),
                        event.cryptoObject
                    )
                }

                is SendLibraryEvent.ShowError -> authError = event.message
            }
        }
    }

    var mode by rememberSaveable { mutableStateOf(SelectionMode.LIBRARY) }
    var selectedPlaylists by remember { mutableStateOf(emptySet<String>()) }
    var selectedTracks by remember { mutableStateOf(emptySet<String>()) }

    // Un trasferimento può durare minuti: lo schermo resta acceso finché la
    // schermata è aperta, come in "Ricevi via Wi-Fi".
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Invia libreria") },
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
            when (val current = state) {
                is TransferServerState.Idle, is TransferServerState.Failed -> {
                    ((current as? TransferServerState.Failed)?.message ?: authError)?.let { messaggio ->
                        ErrorCard(messaggio)
                        Spacer(Modifier.height(16.dp))
                    }
                    SelectionSection(
                        mode = mode,
                        onModeChange = { mode = it },
                        playlists = playlists.map { it.id to it.name },
                        selectedPlaylists = selectedPlaylists,
                        onPlaylistToggle = { id ->
                            selectedPlaylists = selectedPlaylists.toggle(id)
                        },
                        tracks = tracks.map { Triple(it.id, it.title, it.artist) },
                        selectedTracks = selectedTracks,
                        onTrackToggle = { id -> selectedTracks = selectedTracks.toggle(id) },
                        onStart = {
                            viewModel.start(
                                when (mode) {
                                    SelectionMode.LIBRARY -> TransferSelection.WholeLibrary
                                    SelectionMode.PLAYLISTS -> TransferSelection.Playlists(selectedPlaylists)
                                    SelectionMode.TRACKS -> TransferSelection.Tracks(selectedTracks)
                                }
                            )
                        },
                        canStart = when (mode) {
                            SelectionMode.LIBRARY -> tracks.isNotEmpty()
                            SelectionMode.PLAYLISTS -> selectedPlaylists.isNotEmpty()
                            SelectionMode.TRACKS -> selectedTracks.isNotEmpty()
                        }
                    )
                }

                is TransferServerState.Starting -> CenteredProgress("Preparazione dell'invio…")

                is TransferServerState.Ready -> ReadySection(current, onCancel = viewModel::stop)

                is TransferServerState.Pairing -> PairingSection(
                    code = current.code,
                    peerDevice = current.peerDevice,
                    onConfirm = viewModel::confirm,
                    onReject = viewModel::reject
                )

                is TransferServerState.Sending -> SendingSection(current)

                is TransferServerState.Done -> DoneSection(current, onClose = onBack)
            }
        }
    }
}

@Composable
private fun ColumnScope.SelectionSection(
    mode: SelectionMode,
    onModeChange: (SelectionMode) -> Unit,
    playlists: List<Pair<String, String>>,
    selectedPlaylists: Set<String>,
    onPlaylistToggle: (String) -> Unit,
    tracks: List<Triple<String, String, String>>,
    selectedTracks: Set<String>,
    onTrackToggle: (String) -> Unit,
    onStart: () -> Unit,
    canStart: Boolean
) {
    Text(
        "Scegli cosa inviare all'altro telefono. I brani viaggiano cifrati e il " +
            "destinatario li ricifra con la propria chiave.",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(16.dp))

    ModeRow("Tutta la libreria", mode == SelectionMode.LIBRARY) { onModeChange(SelectionMode.LIBRARY) }
    ModeRow("Playlist selezionate", mode == SelectionMode.PLAYLISTS) { onModeChange(SelectionMode.PLAYLISTS) }
    ModeRow("Brani selezionati", mode == SelectionMode.TRACKS) { onModeChange(SelectionMode.TRACKS) }

    Spacer(Modifier.height(8.dp))

    when (mode) {
        SelectionMode.LIBRARY -> {
            Text(
                "${tracks.size} brani in libreria",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
        }

        SelectionMode.PLAYLISTS -> LazyColumn(modifier = Modifier.weight(1f)) {
            items(playlists, key = { it.first }) { (id, name) ->
                CheckRow(
                    checked = id in selectedPlaylists,
                    title = name,
                    subtitle = null,
                    onToggle = { onPlaylistToggle(id) }
                )
            }
        }

        SelectionMode.TRACKS -> LazyColumn(modifier = Modifier.weight(1f)) {
            items(tracks, key = { it.first }) { (id, title, artist) ->
                CheckRow(
                    checked = id in selectedTracks,
                    title = title,
                    subtitle = artist,
                    onToggle = { onTrackToggle(id) }
                )
            }
        }
    }

    Button(
        onClick = onStart,
        enabled = canStart,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Avvia invio")
    }
}

@Composable
private fun ModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(checked: Boolean, title: String, subtitle: String?, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadySection(state: TransferServerState.Ready, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("In attesa dell'altro telefono", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Sull'altro telefono apri \"Ricevi libreria\": questo dispositivo dovrebbe " +
                    "comparire nell'elenco. Se non compare, inserisci a mano questi dati:",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            LabeledValue("Indirizzo", "${state.host}:${state.port}")
            LabeledValue("Codice di accesso", state.token)
            Spacer(Modifier.height(12.dp))
            Text(
                "${state.trackCount} brani · ${formatBytes(state.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Spacer(Modifier.height(16.dp))
    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    Spacer(Modifier.weight(1f))
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text("Annulla invio")
    }
}

@Composable
private fun ColumnScope.PairingSection(
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
            Text("Richiesta da $peerDevice", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                code,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Conferma solo se lo stesso codice compare sull'altro telefono: " +
                    "è ciò che garantisce che i brani vadano al dispositivo giusto.",
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
        Text("Non coincidono")
    }
}

@Composable
private fun ColumnScope.SendingSection(state: TransferServerState.Sending) {
    Text("Invio in corso", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    val fraction = if (state.total > 0) state.served.toFloat() / state.total else 0f
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(
        "${state.served}/${state.total} brani consegnati",
        style = MaterialTheme.typography.bodyMedium
    )
    state.lastTitle?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(Modifier.weight(1f))
    Text(
        "Tieni questa schermata aperta fino alla fine del trasferimento.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ColumnScope.DoneSection(state: TransferServerState.Done, onClose: () -> Unit) {
    Text(
        if (state.cancelled) "Trasferimento interrotto" else "Trasferimento completato",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(12.dp))
    Text("Aggiunti sull'altro telefono: ${state.added}")
    Text("Già presenti: ${state.skipped}")
    Text("Errori: ${state.errors}")
    Spacer(Modifier.weight(1f))
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
        Text("Chiudi")
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ErrorCard(message: String) {
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
private fun CenteredProgress(message: String) {
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

internal fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id

internal fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
}

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
