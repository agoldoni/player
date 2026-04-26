package it.agoldoni.player.ui.stats

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

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
