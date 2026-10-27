package com.vibeplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A bundle of songs shown as one row: an artist, an album, or a genre. */
data class TrackGroup(
    val title: String,
    val subtitle: String,
    val tracks: List<Track>,
    val key: String = "",
    val isArtist: Boolean = false,
    val isAlbum: Boolean = false,
)

private fun countText(count: Int, word: String): String =
    if (count == 1) "1 $word" else "$count ${word}s"

// ---------------------------------------------------------------------------
// Sorting the library into artists, albums and genres
// ---------------------------------------------------------------------------

/**
 * Artist names that contain a comma but are ONE artist, so they must not be cut in two.
 * To add more, put a comma and another name in quotes at the end of this list.
 */
private val keepTogether = listOf(
    "Tyler, The Creator",
    "Earth, Wind & Fire",
    "Blood, Sweat & Tears",
    "Crosby, Stills, Nash & Young",
    "Crosby, Stills & Nash",
    "Emerson, Lake & Palmer",
    "Peter, Paul and Mary",
)

/** Places where one artist name ends and the next begins: commas, semicolons, feat., ft., featuring. */
private val artistSeparators = Regex("""\s*(?:[,;]|\b(?:feat|ft|featuring)\b\.?)\s*""", RegexOption.IGNORE_CASE)

/**
 * Cuts an artist tag like "Brent Faiyaz, A$AP Rocky" into separate artists:
 * ["Brent Faiyaz", "A$AP Rocky"]. Each artist appears only once per song.
 */
fun splitArtists(raw: String): List<String> {
    var rest = raw
    val names = mutableListOf<String>()

    // First pull out any names that must stay in one piece.
    for (name in keepTogether) {
        val index = rest.indexOf(name, ignoreCase = true)
        if (index >= 0) {
            names.add(name)
            rest = rest.removeRange(index, index + name.length)
        }
    }

    // Brackets such as "(feat. X)" also act as separators.
    rest = rest.replace(Regex("[()\\[\\]]"), ",")
    for (piece in rest.split(artistSeparators)) {
        val name = piece.trim()
        if (name.isNotEmpty()) names.add(name)
    }

    // Remove repeats (ignoring capital letters) but keep the original order.
    val seen = HashSet<String>()
    val result = names.filter { seen.add(it.lowercase()) }
    return result.ifEmpty { listOf(raw.trim()) }
}

fun artistGroups(library: List<Track>): List<TrackGroup> {
    val songsByArtist = LinkedHashMap<String, MutableList<Track>>()
    val displayName = HashMap<String, String>()

    for (track in library) {
        // A song is added to every artist credited on it.
        for (name in splitArtists(track.artist)) {
            val key = name.lowercase()
            displayName.putIfAbsent(key, name)
            songsByArtist.getOrPut(key) { mutableListOf() }.add(track)
        }
    }

    return songsByArtist
        .map { (key, tracks) ->
            val albumCount = tracks.map { it.albumId }.distinct().size
            TrackGroup(
                title = displayName.getValue(key),
                key = key,
                isArtist = true,
                subtitle = "${countText(tracks.size, "song")} • ${countText(albumCount, "album")}",
                tracks = tracks.sortedWith(
                    compareBy<Track>({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() })
                ),
            )
        }
        .sortedBy { it.title.lowercase() }
}

fun albumGroups(library: List<Track>): List<TrackGroup> =
    library.groupBy { it.albumId }
        .values
        .map { tracks ->
            // The album's artist is the main (first-listed) artist of its songs.
            val mainArtists = tracks
                .map { splitArtists(it.artist).first() }
                .distinctBy { it.lowercase() }
            val artistText = if (mainArtists.size == 1) mainArtists.first() else "Various Artists"
            TrackGroup(
                title = tracks.first().album,
                key = tracks.first().albumId.toString(),
                isAlbum = true,
                subtitle = "$artistText • ${countText(tracks.size, "song")}",
                tracks = tracks.sortedWith(
                    compareBy<Track>({ it.trackNumber }, { it.title.lowercase() })
                ),
            )
        }
        .sortedBy { it.title.lowercase() }

fun genreGroups(library: List<Track>): List<TrackGroup> =
    library.groupBy { it.genre.trim().lowercase() }
        .values
        .map { tracks ->
            TrackGroup(
                title = tracks.first().genre,
                subtitle = countText(tracks.size, "song"),
                tracks = tracks.sortedWith(
                    compareBy<Track>({ it.artist.lowercase() }, { it.title.lowercase() })
                ),
            )
        }
        .sortedBy { it.title.lowercase() }

// ---------------------------------------------------------------------------
// The Artists / Albums / Genres tabs: a list of groups
// ---------------------------------------------------------------------------
@Composable
fun GroupListScreen(
    title: String,
    groups: List<TrackGroup>,
    padding: PaddingValues,
    roundArt: Boolean = false,
    onOpen: (TrackGroup) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        if (groups.isEmpty()) {
            item {
                Text(
                    "Nothing here yet. Choose your music folders on the Songs tab.",
                    Modifier.padding(16.dp),
                )
            }
        } else {
            items(groups) { group ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(group) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GroupArtwork(group, sizePx = 192, modifier = Modifier.size(56.dp), corner = if (roundArt) 28.dp else 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            group.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            group.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One artist / album / genre opened: Play and Shuffle buttons plus its songs
// ---------------------------------------------------------------------------
@Composable
fun GroupScreen(
    group: TrackGroup,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    var showAddAll by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupArtwork(group, sizePx = 384, modifier = Modifier.size(96.dp), corner = if (group.isArtist) 48.dp else 12.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        group.subtitle,
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
                Button(onClick = { player.playList(group.tracks, 0, shuffle = false) }) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play")
                }
                FilledTonalButton(
                    onClick = {
                        player.playList(group.tracks, group.tracks.indices.random(), shuffle = true)
                    }
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Shuffle")
                }
                IconButton(onClick = { showAddAll = true }) {
                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add all songs to a playlist")
                }
                if (showAddAll) {
                    AddToPlaylistDialog(group.tracks, onDismiss = { showAddAll = false })
                }
            }
        }
        if (group.isArtist) {
            item { ArtistBio(group.title) }
        }
        itemsIndexed(group.tracks, key = { _, track -> track.uri.toString() }) { index, track ->
            GroupTrackRow(group, player, index, track)
        }
    }
}
