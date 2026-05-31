package it.agoldoni.player.ui.author

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
    var selectedAuthor by remember { mutableStateOf<ArtistSummary?>(null) }
    var authorToDelete by remember { mutableStateOf<ArtistSummary?>(null) }

    // Deseleziona se l'autore selezionato sparisce dalla lista (es. dopo la cancellazione)
    LaunchedEffect(artists) {
        val current = selectedAuthor ?: return@LaunchedEffect
        if (artists.none { it.name == current.name }) {
            selectedAuthor = null
        }
    }

    authorToDelete?.let { author ->
        val tracksLabel = if (author.trackCount == 1) "1 brano" else "${author.trackCount} brani"
        AlertDialog(
            onDismissRequest = { authorToDelete = null },
            title = { Text("Elimina autore") },
            text = {
                Text(
                    "Eliminare tutti i $tracksLabel di \"${author.name}\"? " +
                        "L'autore verrà rimosso e l'operazione non può essere annullata."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAuthor(author.name)
                    authorToDelete = null
                    selectedAuthor = null
                }) {
                    Text("Elimina tutto")
                }
            },
            dismissButton = {
                TextButton(onClick = { authorToDelete = null }) {
                    Text("Annulla")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            selectedAuthor?.let { author ->
                SmallFloatingActionButton(
                    onClick = { authorToDelete = author },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Elimina autore")
                }
            }
        },
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
                        isSelected = selectedAuthor?.name == artist.name,
                        onClick = {
                            if (selectedAuthor != null) {
                                selectedAuthor = null
                            } else {
                                onAuthorClick(artist.name)
                            }
                        },
                        onLongClick = {
                            selectedAuthor = if (selectedAuthor?.name == artist.name) null else artist
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
private fun AuthorListItem(
    artist: ArtistSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        headlineContent = {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val label = if (artist.trackCount == 1) "1 traccia" else "${artist.trackCount} tracce"
            Text(label)
        },
        leadingContent = {
            Icon(Icons.Default.Person, contentDescription = null)
        },
        colors = if (isSelected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            ListItemDefaults.colors()
        }
    )
}
