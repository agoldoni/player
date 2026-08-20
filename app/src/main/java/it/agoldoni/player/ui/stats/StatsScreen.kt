package it.agoldoni.player.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import it.agoldoni.player.domain.VerifyProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val trackCount by viewModel.trackCount.collectAsState()
    val totalDuration by viewModel.totalDuration.collectAsState()
    val totalOriginalSize by viewModel.totalOriginalFileSize.collectAsState()
    val totalEncryptedSize by viewModel.totalEncryptedFileSize.collectAsState()
    val freeSpace by viewModel.freeSpace.collectAsState()
    val albumCount by viewModel.albumCount.collectAsState()
    val artistCount by viewModel.artistCount.collectAsState()
    val verifyProgress by viewModel.verifyProgress.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Statistiche") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Indietro"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatCard("Numero tracce", "$trackCount")
            StatCard("Durata totale", formatDuration(totalDuration))
            StatCard("Dimensione originale", formatFileSize(totalOriginalSize))
            StatCard("Dimensione cifrata", formatFileSize(totalEncryptedSize))
            StatCard("Spazio libero", formatFileSize(freeSpace))
            StatCard("Album", "$albumCount")
            StatCard("Autori", "$artistCount")

            IntegrityCard(
                progress = verifyProgress,
                enabled = trackCount > 0,
                onVerify = viewModel::verifyLibrary,
                onCancel = viewModel::cancelVerify
            )
        }
    }
}

/**
 * Verifica di integrità della libreria: decifra ogni brano e controlla il tag
 * GCM, così un file troncato (trasferimento interrotto) o alterato viene a galla.
 */
@Composable
private fun IntegrityCard(
    progress: VerifyProgress,
    enabled: Boolean,
    onVerify: () -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Verifica integrità",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (progress) {
                is VerifyProgress.Idle -> {
                    Text(
                        "Decifra ogni brano per controllare che sia completo e leggibile. " +
                            "Non modifica nulla e può richiedere qualche minuto su librerie grandi.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onVerify,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Verifica adesso")
                    }
                }

                is VerifyProgress.Running -> {
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.current.toFloat() / progress.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${progress.current}/${progress.total} · ${progress.currentTitle}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Interrompi")
                    }
                }

                is VerifyProgress.Done -> {
                    if (progress.problems.isEmpty()) {
                        Text(
                            if (progress.cancelled) {
                                "Verifica interrotta: ${progress.ok} brani controllati, tutti integri."
                            } else {
                                "Tutti i ${progress.ok} brani sono integri."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "${progress.problems.size} brani con problemi " +
                                "(${progress.ok} integri):",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        progress.problems.forEach { problem ->
                            Text(
                                "• ${problem.title} — ${problem.artist}: ${problem.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Text(
                            "Reimportali dal telefono d'origine o dalla sorgente: " +
                                "un brano corrotto non è recuperabile.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                        Text("Verifica di nuovo")
                    }
                }

                is VerifyProgress.Failed -> {
                    Text(
                        progress.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onVerify, modifier = Modifier.fillMaxWidth()) {
                        Text("Riprova")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d h %02d min %02d s".format(hours, minutes, seconds)
    } else {
        "%d min %02d s".format(minutes, seconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
