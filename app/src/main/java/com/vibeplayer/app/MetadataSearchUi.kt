package com.vibeplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun lengthLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Searches MusicBrainz and lets you choose the right match.
 * Choosing one only fills in the edit form; nothing is saved until you press Save there.
 */
@Composable
fun FindMetadataDialog(
    initialTitle: String,
    initialArtist: String,
    trackSeconds: Double,
    onPick: (MbResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var results by remember { mutableStateOf<List<MbResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun runSearch() {
        scope.launch {
            busy = true
            status = ""
            try {
                results = MusicBrainz.search(title, artist)
                    .sortedWith(
                        compareBy<MbResult>(
                            { if (it.lengthSec > 0 && abs(it.lengthSec - trackSeconds) <= 3.0) 0 else 1 },
                            { -it.score },
                        )
                    )
                    .take(20)
                if (results.isEmpty()) status = "Nothing found. Try changing the title or artist."
            } catch (e: Exception) {
                results = emptyList()
                status = "Couldn't reach MusicBrainz. Check your internet connection."
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search MusicBrainz") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Song title") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Button(
                    onClick = { runSearch() },
                    enabled = !busy,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(if (busy) "Searching…" else "Search")
                }
                if (status.isNotEmpty()) {
                    Text(status, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    itemsIndexed(results) { _, result ->
                        val lengthMatches = result.lengthSec > 0 && abs(result.lengthSec - trackSeconds) <= 3.0
                        val details = listOfNotNull(
                            result.album.takeIf { it.isNotBlank() },
                            result.year.takeIf { it.isNotBlank() },
                            result.trackNumber.takeIf { it.isNotBlank() }?.let { "track $it" },
                            if (result.lengthSec > 0) lengthLabel(result.lengthSec) else null,
                        ).joinToString(" • ")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(result) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                result.title,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                result.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                details,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (lengthMatches) {
                                Text(
                                    "Length matches your file",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
