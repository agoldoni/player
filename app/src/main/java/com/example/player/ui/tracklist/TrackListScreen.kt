package com.example.player.ui.tracklist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.player.data.local.entity.Track
import com.example.player.util.rememberMusicFilePicker
import java.util.concurrent.TimeUnit

@Composable
fun TrackListScreen(
    onTrackClick: (String) -> Unit,
    viewModel: TrackListViewModel = hiltViewModel()
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val pickFiles = rememberMusicFilePicker { uris -> viewModel.importTracks(uris) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("La mia Libreria") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = pickFiles) {
                Icon(Icons.Default.Add, contentDescription = "Importa MP3")
            }
        }
    ) { padding ->
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nessuna traccia. Tocca + per importare file MP3.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackListItem(track = track, onClick = { onTrackClick(track.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TrackListItem(track: Track, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Icon(Icons.Default.MusicNote, contentDescription = null)
        }
    )
}

private fun formatDuration(ms: Long): String {
    val min = TimeUnit.MILLISECONDS.toMinutes(ms)
    val sec = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return "%d:%02d".format(min, sec)
}
