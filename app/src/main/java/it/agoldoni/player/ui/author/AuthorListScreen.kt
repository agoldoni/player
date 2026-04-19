package it.agoldoni.player.ui.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.agoldoni.player.data.local.entity.ArtistSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorListScreen(
    onAuthorClick: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: AuthorListViewModel = hiltViewModel()
) {
    val artists by viewModel.artists.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Autori") },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            )
        }
    ) { padding ->
        if (artists.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("Nessun artista in libreria. Importa tracce per iniziare.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(artists, key = { it.name }) { artist ->
                    AuthorListItem(
                        artist = artist,
                        onClick = { onAuthorClick(artist.name) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AuthorListItem(
    artist: ArtistSummary,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val label = if (artist.trackCount == 1) "1 traccia" else "${artist.trackCount} tracce"
            Text(label)
        },
        leadingContent = {
            Icon(Icons.Default.Person, contentDescription = null)
        }
    )
}
