package com.vibeplayer.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which song is being edited right now (null means no editor is open). */
object TagEditorState {
    var track by mutableStateOf<Track?>(null)
}

@Composable
fun EditTagsDialog(track: Track, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasAccess by remember { mutableStateOf(LyricsRepository.hasAllFilesAccess()) }
    var loading by remember { mutableStateOf(true) }
    var readFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var albumArtist by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var trackNumber by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }

    // Notice when you come back from Android's settings with file access switched on.
    LaunchedEffect(Unit) {
        while (true) {
            hasAccess = LyricsRepository.hasAllFilesAccess()
            delay(1000)
        }
    }

    // Read the tags that are inside the file.
    LaunchedEffect(track.uri) {
        loading = true
        val values = withContext(Dispatchers.IO) { TagEditor.read(track) }
        if (values == null) {
            readFailed = true
        } else {
            title = values.title
            artist = values.artist
            album = values.album
            albumArtist = values.albumArtist
            genre = values.genre
            year = values.year
            trackNumber = values.track
            lyrics = values.lyrics
        }
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    !hasAccess -> {
                        Text("To change the tags inside song files, VibePlayer needs access to all files.")
                        TextButton(onClick = { openAllFilesAccessSettings(context) }) {
                            Text("Allow file access")
                        }
                    }
                    loading -> Text("Reading tags…")
                    readFailed -> Text(
                        "This file's tags can't be read, so it can't be edited here.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        OutlinedButton(onClick = { showSearch = true }) {
                            Text("Search online (MusicBrainz)")
                        }
                        TagField("Title", title) { title = it }
                        TagField("Artist", artist) { artist = it }
                        TagField("Album", album) { album = it }
                        TagField("Album artist", albumArtist) { albumArtist = it }
                        TagField("Genre", genre) { genre = it }
                        Row(Modifier.fillMaxWidth()) {
                            TagField("Year", year, Modifier.weight(1f), number = true) { year = it }
                            Text("  ")
                            TagField("Track no.", trackNumber, Modifier.weight(1f), number = true) { trackNumber = it }
                        }
                        TagField("Lyrics", lyrics, multiline = true) { lyrics = it }
                    }
                }
                message?.let {
                    Text(
                        it,
                        Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = hasAccess && !loading && !readFailed && !saving,
                onClick = {
                    scope.launch {
                        saving = true
                        message = null
                        try {
                            withContext(Dispatchers.IO) {
                                TagEditor.write(
                                    context,
                                    track,
                                    TagValues(title, artist, album, albumArtist, genre, year, trackNumber, lyrics),
                                )
                            }
                            onDismiss()
                        } catch (e: Exception) {
                            message = "Couldn't save: ${e.message ?: e.javaClass.simpleName}"
                        }
                        saving = false
                    }
                },
            ) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
        },
    )

    if (showSearch) {
        FindMetadataDialog(
            initialTitle = title.ifBlank { track.title },
            initialArtist = splitArtists(artist.ifBlank { track.artist }).first(),
            trackSeconds = track.durationMs / 1000.0,
            onPick = { result ->
                // Only fills in the form. Nothing is written to the file until you press Save.
                if (result.title.isNotBlank()) title = result.title
                if (result.artist.isNotBlank()) artist = result.artist
                if (result.album.isNotBlank()) album = result.album
                if (result.albumArtist.isNotBlank()) albumArtist = result.albumArtist
                if (result.year.isNotBlank()) year = result.year
                if (result.trackNumber.isNotBlank()) trackNumber = result.trackNumber
                if (result.genre.isNotBlank() && genre.isBlank()) genre = result.genre
                showSearch = false
            },
            onDismiss = { showSearch = false },
        )
    }
}

@Composable
private fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        maxLines = if (multiline) 6 else 1,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
