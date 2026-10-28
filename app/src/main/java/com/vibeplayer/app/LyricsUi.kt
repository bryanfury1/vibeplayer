package com.vibeplayer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class LyricsState(val loading: Boolean, val lyrics: Lyrics?)

/** Opens Android's switch for "All files access" for this app. */
fun openAllFilesAccessSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 30) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    } catch (e: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e2: Exception) {
            // Nothing more we can do.
        }
    }
}

// ---------------------------------------------------------------------------
// The lyrics view (used inside Now Playing and in full screen)
// ---------------------------------------------------------------------------
@Composable
fun LyricsContent(
    player: PlayerController,
    track: Track?,
    modifier: Modifier,
    fullscreenIcon: ImageVector,
    onToggleFullscreen: () -> Unit,
    big: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var showFind by remember { mutableStateOf(false) }
    var hasAccess by remember { mutableStateOf(LyricsRepository.hasAllFilesAccess()) }

    // Notice when you come back from Android's settings with access switched on.
    LaunchedEffect(Unit) {
        while (true) {
            hasAccess = LyricsRepository.hasAllFilesAccess()
            delay(1000)
        }
    }

    val state by produceState(LyricsState(true, null), track?.uri, reload, hasAccess) {
        value = LyricsState(true, null)
        value = LyricsState(false, if (track == null) null else LyricsRepository.load(context, track))
    }

    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { showFind = true }) {
                Icon(Icons.Rounded.Search, contentDescription = "Find lyrics")
            }
            if (state.lyrics != null && track != null) {
                IconButton(onClick = {
                    scope.launch {
                        val message = if (!hasAccess) {
                            "Allow file access first (Settings), so lyrics can be written into the file."
                        } else {
                            val saved = LyricsRepository.loadSavedText(context, track)
                            if (saved == null) {
                                "These lyrics are already inside the song file."
                            } else {
                                try {
                                    withContext(Dispatchers.IO) { TagEditor.writeLyrics(context, track, saved) }
                                    "Lyrics saved inside the song file."
                                } catch (e: Exception) {
                                    "Couldn't write the lyrics: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = "Save lyrics inside the song file")
                }
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(fullscreenIcon, contentDescription = "Full screen")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val lyrics = state.lyrics
            when {
                track == null -> {}
                state.loading -> Text("Loading lyrics…")
                lyrics == null -> NoLyrics(hasAccess, onFind = { showFind = true })
                else -> LyricList(lyrics, player, big)
            }
        }
    }

    if (showFind && track != null) {
        FindLyricsDialog(
            track = track,
            onDismiss = { showFind = false },
            onSaved = {
                showFind = false
                reload++
            },
        )
    }
}

@Composable
private fun NoLyrics(hasAccess: Boolean, onFind: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No lyrics for this song yet.", textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onFind) { Text("Find lyrics online") }
        if (!hasAccess && Build.VERSION.SDK_INT >= 30) {
            Spacer(Modifier.height(16.dp))
            Text(
                "To use .lrc files stored next to your songs, allow access to all files.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openAllFilesAccessSettings(context) }) {
                Text("Allow file access")
            }
        }
    }
}

@Composable
private fun LyricList(lyrics: Lyrics, player: PlayerController, big: Boolean) {
    val listState = rememberLazyListState()
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            position = player.position()
            delay(200)
        }
    }
    val active by remember(lyrics) {
        derivedStateOf { if (lyrics.synced) activeLineIndex(lyrics.lines, position) else -1 }
    }

    // Keep the current line in view, unless you are scrolling yourself.
    LaunchedEffect(active) {
        if (active >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem((active - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == active
            val style = if (big) {
                if (isActive) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
            } else {
                if (isActive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
            }
            Text(
                text = if (lyrics.synced && line.text.isEmpty()) "♪" else line.text,
                style = style,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = lyrics.synced) { player.seekTo(line.timeMs) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Full-screen lyrics
// ---------------------------------------------------------------------------
@Composable
fun LyricsFullScreen(player: PlayerController, onClose: () -> Unit) {
    val track = player.currentTrack
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                track?.title.orEmpty(),
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track?.artist.orEmpty(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LyricsContent(
                player = player,
                track = track,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                fullscreenIcon = Icons.Rounded.FullscreenExit,
                onToggleFullscreen = onClose,
                big = true,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Pop-up: search LRCLIB and choose the right lyrics
// ---------------------------------------------------------------------------
private fun durationText(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
fun FindLyricsDialog(track: Track, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainArtist = remember(track.uri) { splitArtists(track.artist).first() }
    val trackSeconds = track.durationMs / 1000.0

    var query by remember { mutableStateOf("${track.title} $mainArtist") }
    var results by remember { mutableStateOf<List<LrcLibResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }

    fun runSearch(byFields: Boolean) {
        scope.launch {
            busy = true
            status = ""
            try {
                var found = if (byFields) LrcLib.searchFields(track.title, mainArtist)
                else LrcLib.searchQuery(query)
                if (found.isEmpty() && byFields) found = LrcLib.searchQuery("${track.title} $mainArtist")
                results = found
                    .filter { !it.instrumental && (it.synced != null || it.plain != null) }
                    .sortedWith(
                        compareBy<LrcLibResult>(
                            { if (abs(it.durationSec - trackSeconds) <= 3.0) 0 else 1 },
                            { if (it.synced != null) 0 else 1 },
                            { abs(it.durationSec - trackSeconds) },
                        )
                    )
                if (results.isEmpty()) status = "No lyrics found. Try changing the search text."
            } catch (e: Exception) {
                results = emptyList()
                status = "Couldn't reach LRCLIB. Check your internet connection."
            }
            busy = false
        }
    }

    fun choose(result: LrcLibResult) {
        scope.launch {
            busy = true
            val text = result.synced ?: result.plain
            if (text.isNullOrBlank()) {
                status = "That result has no lyrics."
                busy = false
            } else if (LyricsRepository.save(context, track, text)) {
                onSaved()
            } else {
                status = "Couldn't save the lyrics file."
                busy = false
            }
        }
    }

    LaunchedEffect(track.uri) { runSearch(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find lyrics") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch(false) }),
                    trailingIcon = {
                        IconButton(onClick = { runSearch(false) }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Search")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (busy) Text("Working…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.isNotEmpty()) Text(status, color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(results) { index, result ->
                        val difference = abs(result.durationSec - trackSeconds)
                        val tags = buildList {
                            if (index == 0 && result.synced != null && difference <= 3.0) add("Best match")
                            add(if (result.synced != null) "Synced" else "Plain text")
                            add(durationText(result.durationSec))
                        }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { choose(result) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                result.trackName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${result.artistName} • ${result.albumName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tags.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
