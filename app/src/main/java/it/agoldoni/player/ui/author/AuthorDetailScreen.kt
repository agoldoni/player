package it.agoldoni.player.ui.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.data.local.entity.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorDetailScreen(
    onTrackClick: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: AuthorDetailViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val currentPlayingTrackId by viewModel.currentPlayingTrackId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthorDetailEvent.ShowError -> {
                    snackbarHostState.showSnackbar(event.message)
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(viewModel.artistName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    if (tracks.isNotEmpty()) {
                        FilledIconToggleButton(
                            checked = shuffleEnabled,
                            onCheckedChange = { viewModel.toggleShuffle() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = if (shuffleEnabled) "Disattiva shuffle" else "Attiva shuffle"
                            )
                        }
                        IconButton(onClick = { viewModel.togglePlayback() }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pausa" else "Riproduci tutto"
                            )
                        }
                        if (isPlaying || currentPlayingTrackId != null) {
                            IconButton(onClick = { viewModel.skipToNext() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Brano successivo"
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nessuna traccia per questo artista.")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                val countLabel = if (tracks.size == 1) "1 traccia" else "${tracks.size} tracce"
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(tracks, key = { it.id }) { track ->
                        AuthorTrackItem(
                            track = track,
                            isCurrentlyPlaying = track.id == currentPlayingTrackId,
                            onClick = { onTrackClick(track.id) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorTrackItem(
    track: Track,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
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
                track.album,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        colors = if (isCurrentlyPlaying) {
            ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        } else {
            ListItemDefaults.colors()
        }
    )
}
