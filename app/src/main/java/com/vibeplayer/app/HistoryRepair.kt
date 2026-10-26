package com.vibeplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** A group of plays that no longer match any song in the library. */
private data class LostPlays(val key: String, val count: Int, val lastMs: Long)

/**
 * Lets you reattach plays that were lost when a song's file changed (for example after
 * editing its info in an older version of the app). Pick the group, then pick the song.
 */
@Composable
fun RecoverPlaysDialog(library: List<Track>, onDismiss: () -> Unit) {
    val events = PlayHistory.events
    val known = remember(library) {
        val keys = HashSet<String>()
        for (track in library) {
            keys.add(track.uri.toString())
            keys.add(track.key)
        }
        keys
    }
    val lost = remember(events, known, library) {
        if (library.isEmpty()) {
            emptyList()
        } else {
            events
                .filter { it.uri !in known }
                .groupBy { it.uri }
                .map { (key, list) -> LostPlays(key, list.size, list.maxOf { it.timeMs }) }
                .sortedByDescending { it.count }
        }
    }
    var picking by remember { mutableStateOf<LostPlays?>(null) }
    var query by remember { mutableStateOf("") }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (picking == null) "Recover lost plays" else "Which song is this?") },
        text = {
            val chosen = picking
            if (chosen == null) {
                Column {
                    Text(
                        "These plays belong to songs whose files changed (for example after editing their info), so they no longer match. Tap a group, then say which song it is. The group with the most plays is usually the favourite song you edited.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (lost.isEmpty()) Text("No lost plays found.")
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(lost) { group ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picking = group
                                        query = ""
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(
                                    if (group.count == 1) "1 play" else "${group.count} plays",
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "Last played ${dateFormat.format(Date(group.lastMs))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                Column {
                    Text(
                        "${chosen.count} plays will be attached to the song you choose.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Type part of the title or artist") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                    val matches = remember(query, library) {
                        if (query.isBlank()) emptyList()
                        else library
                            .filter {
                                it.title.contains(query, ignoreCase = true) ||
                                    it.artist.contains(query, ignoreCase = true)
                            }
                            .take(30)
                    }
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(matches) { track ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        PlayHistory.remap(chosen.key, track.key)
                                        picking = null
                                    }
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    track.title,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${track.artist} • ${track.album}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            if (picking != null) {
                TextButton(onClick = { picking = null }) { Text("Back") }
            }
        },
    )
}
