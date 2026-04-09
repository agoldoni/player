package it.agoldoni.player.util

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Ritorna una lambda che apre il file picker per selezionare più file audio.
 * Chiama [onFilesPicked] con la lista di URI selezionati.
 */
@Composable
fun rememberMusicFilePicker(
    onFilesPicked: (List<Uri>) -> Unit
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) onFilesPicked(uris)
    }
    return remember { { launcher.launch("audio/mpeg") } }
}
