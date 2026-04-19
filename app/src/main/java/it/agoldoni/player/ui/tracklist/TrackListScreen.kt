package it.agoldoni.player.ui.tracklist

import android.content.Intent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.data.local.entity.Track
import it.agoldoni.player.util.rememberMusicFilePicker
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackListScreen(
    onTrackClick: (String) -> Unit,
    onNavigateToPlaylists: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    viewModel: TrackListViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val playingTrackId by viewModel.playingTrackId.collectAsStateWithLifecycle()
    val pickFiles = rememberMusicFilePicker { uris -> viewModel.importTracks(uris) }
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToDelete by remember { mutableStateOf<Track?>(null) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(tracks, playingTrackId) {
        val playingId = playingTrackId ?: return@LaunchedEffect
        if (tracks.isEmpty()) return@LaunchedEffect
        val index = tracks.indexOfFirst { it.id == playingId }
        if (index >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val isVisible = visible.any { it.index == index }
            if (!isVisible) {
                listState.animateScrollToItem(index)
            }
        }
    }

    // Gestione eventi biometrici dal ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TrackListEvent.RequestBiometricAuth -> {
                    val activity = context as? FragmentActivity ?: return@collect
                    val prompt = BiometricPrompt(
                        activity,
                        ContextCompat.getMainExecutor(context),
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                val cipher = result.cryptoObject?.cipher ?: return
                                viewModel.onBiometricSuccess(cipher, event.isSetup)
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                viewModel.onBiometricError(errString.toString())
                            }
                        }
                    )
                    prompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Autenticazione richiesta")
                            .setSubtitle("Autenticati per importare i brani")
                            .setNegativeButtonText("Annulla")
                            .build(),
                        event.cryptoObject
                    )
                }

                is TrackListEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }

                is TrackListEvent.ShareCsvFile -> {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        event.file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Invia CSV"))
                }
            }
        }
    }

    trackToDelete?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Elimina traccia") },
            text = { Text("Eliminare \"${track.title}\"? L'operazione non può essere annullata.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTrack(track)
                    trackToDelete = null
                    selectedTrack = null
                }) {
                    Text("Elimina")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Annulla")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("La mia Libreria") },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.exportCsv() }) {
                            Icon(Icons.Default.Share, contentDescription = "Esporta CSV")
                        }
                    }
                )
                TabRow(selectedTabIndex = 0) {
                    Tab(selected = true, onClick = {}, text = { Text("Tracce") })
                    Tab(selected = false, onClick = onNavigateToPlaylists, text = { Text("Playlist") })
                }
            }
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedTrack != null) {
                    val isPlayingSelected = playingTrackId == selectedTrack!!.id
                    SmallFloatingActionButton(
                        onClick = { viewModel.togglePlayTrack(selectedTrack!!) },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Icon(
                            if (isPlayingSelected) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlayingSelected) "Ferma riproduzione" else "Riproduci traccia"
                        )
                    }
                    SmallFloatingActionButton(
                        onClick = { trackToDelete = selectedTrack },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Elimina traccia")
                    }
                }
                FloatingActionButton(onClick = pickFiles) {
                    Icon(Icons.Default.Add, contentDescription = "Importa MP3")
                }
            }
        }
    ) { padding ->
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text("Nessuna traccia. Tocca + per importare file MP3.")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(
                        track = track,
                        isSelected = selectedTrack?.id == track.id,
                        isCurrentlyPlaying = track.id == playingTrackId,
                        onClick = {
                            if (selectedTrack != null) {
                                selectedTrack = null
                            } else {
                                onTrackClick(track.id)
                            }
                        },
                        onLongClick = {
                            selectedTrack = if (selectedTrack?.id == track.id) null else track
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackListItem(
    track: Track,
    isSelected: Boolean,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrentlyPlaying) FontWeight.Bold else FontWeight.Normal
            )
        },
        supportingContent = {
            Text(
                "${track.artist} • ${track.album}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingContent = {
            Text(
                formatDuration(track.duration),
                style = MaterialTheme.typography.bodySmall
            )
        },
        leadingContent = {
            if (isCurrentlyPlaying) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "In riproduzione",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = null)
            }
        },
        colors = when {
            isSelected -> ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
            isCurrentlyPlaying -> ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
            else -> ListItemDefaults.colors()
        }
    )
}

private fun formatDuration(ms: Long): String {
    val min = TimeUnit.MILLISECONDS.toMinutes(ms)
    val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(min, sec)
}
