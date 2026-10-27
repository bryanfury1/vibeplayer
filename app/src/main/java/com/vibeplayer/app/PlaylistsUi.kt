package com.vibeplayer.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Which song is waiting to be put into a playlist. The "Add to playlist" menu item sets this. */
object PlaylistPicker {
    var track by mutableStateOf<Track?>(null)
}

private fun songCountText(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun playlistSubtitle(total: Int, missing: Int): String =
    if (missing > 0) "${songCountText(total)} • $missing not found" else songCountText(total)

// ---------------------------------------------------------------------------
// Export and import buttons' behaviour (they use Android's own file picker)
// ---------------------------------------------------------------------------

/** Returns a function that asks where to save a playlist file, then writes the playlist there. */
@Composable
fun rememberPlaylistExporter(): (Playlist) -> Unit {
    val context = LocalContext.current
    var target by remember { mutableStateOf<Playlist?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl")
    ) { uri ->
        val playlist = target
        target = null
        if (uri != null && playlist != null) {
            val ok = PlaylistFiles.writeText(context, uri, PlaylistM3u.export(playlist))
            Toast.makeText(
                context,
                if (ok) "Exported \"${playlist.name}\"" else "Could not save the file",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    return { playlist ->
        target = playlist
        launcher.launch(PlaylistFiles.fileNameFor(playlist.name))
    }
}

/** Returns a function that asks for a playlist file, then turns it into a new playlist. */
@Composable
fun rememberPlaylistImporter(
    player: PlayerController,
    onImported: (Playlist) -> Unit = {},
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = PlaylistFiles.readText(context, uri)
            if (text == null) {
                Toast.makeText(context, "Could not read that file", Toast.LENGTH_LONG).show()
            } else {
                val fileName = PlaylistFiles.displayName(context, uri).substringBeforeLast('.')
                val result = PlaylistM3u.parse(text, player.library, fileName)
                if (result.entries.isEmpty()) {
                    Toast.makeText(context, "No songs found in that file", Toast.LENGTH_LONG).show()
                } else {
                    val playlist = PlaylistStore.create(result.name, result.entries)
                    val missing = result.entries.size - result.found
                    val message =
                        if (missing == 0) "Imported \"${playlist.name}\": ${songCountText(result.found)}"
                        else "Imported \"${playlist.name}\": ${result.found} of ${result.entries.size} songs found"
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    onImported(playlist)
                }
            }
        }
    }
    return {
        if (player.library.isEmpty()) {
            Toast.makeText(
                context,
                "Choose your music folders on the Songs tab first, so songs can be matched.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            launcher.launch(arrayOf("*/*"))
        }
    }
}

// ---------------------------------------------------------------------------
// Small pop-ups
// ---------------------------------------------------------------------------

/** A pop-up with a text box, used for naming and renaming playlists. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Playlist name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeletePlaylistDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete playlist?") },
        text = { Text("\"$name\" will be deleted. Your songs are not touched.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The pop-up behind "Add to playlist" for one song. */
@Composable
fun AddToPlaylistDialog(track: Track, onDismiss: () -> Unit) {
    AddToPlaylistDialog(listOf(track), onDismiss)
}

/** The same pop-up for several songs at once (for example a whole album): pick a playlist, or make a new one. */
@Composable
fun AddToPlaylistDialog(tracks: List<Track>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    val playlists = PlaylistStore.playlists

    if (creating) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                val playlist = PlaylistStore.create(name, tracks.map { it.toEntry() })
                Toast.makeText(context, "Added to \"${playlist.name}\"", Toast.LENGTH_SHORT).show()
                onDismiss()
            },
            onDismiss = { creating = false },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Add to playlist") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New playlist")
                    }
                    playlists.forEach { playlist ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val added = PlaylistStore.addTracks(playlist.id, tracks)
                                    Toast.makeText(
                                        context,
                                        if (added > 0) "Added $added to \"${playlist.name}\""
                                        else "Already in \"${playlist.name}\"",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onDismiss()
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                playlist.name,
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                songCountText(playlist.entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------
// The Playlists tab: a list of your playlists
// ---------------------------------------------------------------------------
@Composable
fun PlaylistsScreen(
    player: PlayerController,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
) {
    // Favorite songs always comes first.
    val playlists = PlaylistStore.playlists.sortedBy { if (it.id == FAVORITES_ID) 0 else 1 }
    val byKey = remember(player.library) { player.library.associateBy { it.key } }
    var showCreate by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Playlist?>(null) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    val exportPlaylist = rememberPlaylistExporter()
    val importPlaylist = rememberPlaylistImporter(player) { onOpen(it.id) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Playlists",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = importPlaylist) { Text("Import") }
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New playlist")
                }
            }
        }

        if (playlists.size <= 1) {
            item {
                Text(
                    "Tap + to make a playlist, or Import to load a .m3u or .m3u8 playlist file. " +
                        "Use the heart on a song to add it to Favorite songs.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "No playlists yet. Tap + to make one, or Import to load a .m3u or .m3u8 playlist file. " +
                        "You can also add any song with the ⋮ menu on a song, then Add to playlist.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(playlists) { playlist ->
                val tracks = playlist.entries.mapNotNull { byKey[it.key] }
                val missing = playlist.entries.size - tracks.size
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(playlist.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(tracks.firstOrNull(), sizePx = 192, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            playlistSubtitle(playlist.entries.size, missing),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Play") },
                                onClick = {
                                    menuOpen = false
                                    if (tracks.isNotEmpty()) player.playList(tracks, 0, shuffle = false)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Shuffle") },
                                onClick = {
                                    menuOpen = false
                                    if (tracks.isNotEmpty()) {
                                        player.playList(tracks, tracks.indices.random(), shuffle = true)
                                    }
                                },
                            )
                            if (playlist.id != FAVORITES_ID) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        menuOpen = false
                                        renaming = playlist
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Export as .m3u8 file") },
                                onClick = {
                                    menuOpen = false
                                    exportPlaylist(playlist)
                                },
                            )
                            if (playlist.id != FAVORITES_ID) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        menuOpen = false
                                        deleting = playlist
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                PlaylistStore.create(name)
                showCreate = false
            },
            onDismiss = { showCreate = false },
        )
    }
    renaming?.let { target ->
        NameDialog(
            title = "Rename playlist",
            initial = target.name,
            confirmLabel = "Save",
            onConfirm = { name ->
                PlaylistStore.rename(target.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { target ->
        DeletePlaylistDialog(
            name = target.name,
            onConfirm = {
                PlaylistStore.delete(target.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

// ---------------------------------------------------------------------------
// One playlist opened: Play and Shuffle buttons plus its songs
// ---------------------------------------------------------------------------
@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val playlist = PlaylistStore.playlists.firstOrNull { it.id == playlistId }
    if (playlist == null) {
        // The playlist was deleted, so go back to the list.
        LaunchedEffect(Unit) { onBack() }
    } else {
        PlaylistDetailContent(playlist, player, padding, onBack)
    }
}

@Composable
private fun PlaylistDetailContent(
    playlist: Playlist,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    val byKey = remember(player.library) { player.library.associateBy { it.key } }
    // Only the songs that exist in your library can be played.
    val playable = remember(playlist, byKey) { playlist.entries.mapNotNull { byKey[it.key] } }
    val exportPlaylist = rememberPlaylistExporter()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val lastIndex = playlist.entries.lastIndex

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Playlist options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (playlist.id != FAVORITES_ID) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    renaming = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Export as .m3u8 file") },
                            onClick = {
                                menuOpen = false
                                exportPlaylist(playlist)
                            },
                        )
                        if (playlist.id != FAVORITES_ID) {
                            DropdownMenuItem(
                                text = { Text("Delete playlist") },
                                onClick = {
                                    menuOpen = false
                                    deleting = true
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    playable.firstOrNull(),
                    sizePx = 384,
                    modifier = Modifier.size(96.dp),
                    corner = 12.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playlistSubtitle(playlist.entries.size, playlist.entries.size - playable.size),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { player.playList(playable, 0, shuffle = false) },
                    enabled = playable.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                FilledTonalButton(
                    onClick = { player.playList(playable, playable.indices.random(), shuffle = true) },
                    enabled = playable.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle")
                }
            }
        }
        if (playlist.entries.isEmpty()) {
            item {
                Text(
                    "This playlist is empty. Use the ⋮ menu on any song and choose Add to playlist.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(playlist.entries) { index, entry ->
            val track = byKey[entry.key]
            if (track != null) {
                PlaylistTrackRow(
                    track = track,
                    isCurrent = track.uri == player.currentTrack?.uri,
                    canMoveUp = index > 0,
                    canMoveDown = index < lastIndex,
                    onClick = {
                        // Songs that can't be found are skipped, so count only the playable ones before this.
                        val start = playlist.entries.take(index).count { it.key in byKey }
                        player.playList(playable, start)
                    },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                    onMoveUp = { PlaylistStore.move(playlist.id, index, index - 1) },
                    onMoveDown = { PlaylistStore.move(playlist.id, index, index + 1) },
                    onRemove = { PlaylistStore.removeAt(playlist.id, index) },
                )
            } else {
                MissingSongRow(entry, onRemove = { PlaylistStore.removeAt(playlist.id, index) })
            }
        }
    }

    if (renaming) {
        NameDialog(
            title = "Rename playlist",
            initial = playlist.name,
            confirmLabel = "Save",
            onConfirm = { name ->
                PlaylistStore.rename(playlist.id, name)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
    if (deleting) {
        DeletePlaylistDialog(
            name = playlist.name,
            onConfirm = {
                deleting = false
                PlaylistStore.delete(playlist.id)
            },
            onDismiss = { deleting = false },
        )
    }
}

/** A song row inside a playlist. Like a normal song row, plus move and remove options. */
@Composable
private fun PlaylistTrackRow(
    track: Track,
    isCurrent: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, sizePx = 128, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = {
                        menuOpen = false
                        onPlayNext()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        menuOpen = false
                        onAddToQueue()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to another playlist") },
                    onClick = {
                        menuOpen = false
                        PlaylistPicker.track = track
                    },
                )
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        onClick = {
                            menuOpen = false
                            onMoveUp()
                        },
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        onClick = {
                            menuOpen = false
                            onMoveDown()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Edit info") },
                    onClick = {
                        menuOpen = false
                        TagEditorState.track = track
                    },
                )
            }
        }
    }
}

/** Shown for a playlist song whose file isn't in your library (moved, deleted, or from another phone). */
@Composable
private fun MissingSongRow(entry: PlaylistEntry, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title.ifBlank { entry.key.substringAfterLast('/') },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (entry.artist.isBlank()) "Not found in your library"
                else "${entry.artist} • Not found in your library",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove from playlist")
        }
    }
}
