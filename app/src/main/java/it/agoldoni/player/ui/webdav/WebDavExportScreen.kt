package it.agoldoni.player.ui.webdav

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.domain.webdav.WebDavServerState
import it.agoldoni.player.ui.transfer.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavExportScreen(
    onBack: () -> Unit,
    viewModel: WebDavExportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Avvia il server all'ingresso e fermalo all'uscita dalla schermata.
    LaunchedEffect(Unit) { viewModel.start() }

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
                title = { Text("Esporta su PC") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val s = state) {
                WebDavServerState.Idle,
                WebDavServerState.Starting -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("Avvio del server…")
                    }
                }

                is WebDavServerState.Running -> RunningBody(s)

                is WebDavServerState.Failed -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Impossibile avviare l'esportazione",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                        Text("Riprova")
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningBody(state: WebDavServerState.Running) {
    val clipboard = LocalClipboardManager.current

    val configCommand = "rclone config create player webdav url=${state.url} vendor=other"
    val copyCommand = "rclone copy player: ~/Musica --size-only --progress"

    Text(
        "La libreria è visibile sulla rete locale come cartella di sola lettura. " +
            "Sul PC, collegato alla stessa Wi-Fi, i brani arrivano organizzati in " +
            "Artista/Album/Titolo, e vengono scaricati solo quelli che non hai già.",
        style = MaterialTheme.typography.bodyMedium
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Indirizzo", style = MaterialTheme.typography.labelMedium)
            Text(
                state.url,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${state.trackCount} brani · ${formatBytes(state.totalBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.url)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copia indirizzo")
            }
        }
    }

    CommandCard(
        title = "1. Configura il collegamento (una volta sola)",
        command = configCommand,
        onCopy = { clipboard.setText(AnnotatedString(configCommand)) }
    )

    CommandCard(
        title = "2. Scarica i brani mancanti (ogni volta)",
        command = copyCommand,
        onCopy = { clipboard.setText(AnnotatedString(copyCommand)) },
        footnote = "Rilanciandolo scarica solo ciò che nella cartella non c'è ancora. " +
            "In alternativa: rclone mount player: /mnt/player --read-only e poi rsync."
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Tieni questa schermata aperta durante il trasferimento: chiudendola il server si " +
                "ferma. L'indirizzo cambia ogni volta che riapri questa pagina, quindi va " +
                "riconfigurato. I brani viaggiano in chiaro sulla rete locale.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun CommandCard(
    title: String,
    command: String,
    onCopy: () -> Unit,
    footnote: String? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(
                command,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            footnote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Copia comando")
            }
        }
    }
}

private fun android.content.Context.findActivity(): Activity? {
    var ctx: android.content.Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
