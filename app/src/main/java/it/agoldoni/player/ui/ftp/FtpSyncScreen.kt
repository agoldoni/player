package it.agoldoni.player.ui.ftp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.domain.ftp.SyncProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpSyncScreen(
    onBack: () -> Unit,
    onOpenConfig: () -> Unit,
    viewModel: FtpSyncViewModel = hiltViewModel()
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val hasConfig by viewModel.hasConfig.collectAsStateWithLifecycle()
    var showCancelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshConfigStatus()
    }

    val running = when (progress) {
        SyncProgress.Connecting, SyncProgress.Scanning, is SyncProgress.Importing -> true
        else -> false
    }

    BackHandler(enabled = running) {
        showCancelDialog = true
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Annullare la sincronizzazione?") },
            text = { Text("Le tracce già importate rimarranno in libreria.") },
            confirmButton = {
                Button(onClick = {
                    showCancelDialog = false
                    viewModel.cancel()
                }) { Text("Annulla sync") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelDialog = false }) { Text("Continua") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronizza da FTP") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showCancelDialog = true else onBack()
                    }) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (hasConfig) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Caricamento…")
                }
                false -> NoConfigState(onOpenConfig = onOpenConfig)
                true -> SyncBody(
                    progress = progress,
                    running = running,
                    onStart = viewModel::start,
                    onCancelRequested = { showCancelDialog = true },
                    onReset = viewModel::reset
                )
            }
        }
    }
}

@Composable
private fun NoConfigState(onOpenConfig: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Configura prima il server FTP per poter sincronizzare.")
            Button(onClick = onOpenConfig) { Text("Apri configurazione FTP") }
        }
    }
}

@Composable
private fun SyncBody(
    progress: SyncProgress,
    running: Boolean,
    onStart: () -> Unit,
    onCancelRequested: () -> Unit,
    onReset: () -> Unit
) {
    if (running) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Non chiudere l'app durante la sincronizzazione.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    when (progress) {
        SyncProgress.Idle -> {
            Text(
                "Premi Avvia per connetterti al server FTP e scaricare tutti gli MP3 non ancora presenti in libreria.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(14.dp)
            ) { Text("Avvia sincronizzazione") }
        }

        SyncProgress.Connecting -> LabeledIndeterminate("Connessione al server…")
        SyncProgress.Scanning -> LabeledIndeterminate("Scansione delle cartelle remote…")

        is SyncProgress.Importing -> {
            val fraction = if (progress.total == 0) 0f else progress.current.toFloat() / progress.total
            Text(
                "${progress.current} / ${progress.total}",
                style = MaterialTheme.typography.titleMedium
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                progress.currentFileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Aggiunti: ${progress.added}   Saltati: ${progress.skipped}   Errori: ${progress.errors}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCancelRequested,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Annulla") }
        }

        is SyncProgress.Done -> {
            val title = if (progress.cancelled) "Sincronizzazione annullata" else "Sincronizzazione completata"
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                "Aggiunti: ${progress.added}   Saltati: ${progress.skipped}   Errori: ${progress.errors}",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("OK") }
        }

        is SyncProgress.Failed -> {
            Text(
                "Sincronizzazione fallita",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )
            Text(progress.message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("Riprova") }
        }
    }
}

@Composable
private fun LabeledIndeterminate(label: String) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}
