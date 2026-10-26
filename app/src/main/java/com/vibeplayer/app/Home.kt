package com.vibeplayer.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The time frames you can pick for "Most played songs". */
enum class TimeFrame(val label: String, val days: Long?) {
    WEEK("Week", 7L),
    MONTH("Month", 30L),
    YEAR("Year", 365L),
    ALL("All time", null),
}

// ---------------------------------------------------------------------------
// Working out what to show
// ---------------------------------------------------------------------------

/** Songs you played, newest first, each song once. */
private fun recentSongs(events: List<PlayEvent>, tracks: Map<String, Track>, limit: Int): List<Track> {
    val seen = HashSet<String>()
    val result = ArrayList<Track>()
    for (i in events.indices.reversed()) {
        val track = tracks[events[i].uri] ?: continue
        if (seen.add(track.key)) {
            result.add(track)
            if (result.size >= limit) break
        }
    }
    return result
}

/** Artists of the songs you played, newest first. Only the main (first-listed) artist counts. */
private fun recentArtists(
    events: List<PlayEvent>,
    tracks: Map<String, Track>,
    artistByKey: Map<String, TrackGroup>,
    limit: Int,
): List<TrackGroup> {
    val seen = HashSet<String>()
    val result = ArrayList<TrackGroup>()
    for (i in events.indices.reversed()) {
        val track = tracks[events[i].uri] ?: continue
        val key = splitArtists(track.artist).first().lowercase()
        val group = artistByKey[key] ?: continue
        if (seen.add(key)) {
            result.add(group)
            if (result.size >= limit) break
        }
    }
    return result
}

/** Albums of the songs you played, newest first. */
private fun recentAlbums(
    events: List<PlayEvent>,
    tracks: Map<String, Track>,
    albumByKey: Map<String, TrackGroup>,
    limit: Int,
): List<TrackGroup> {
    val seen = HashSet<String>()
    val result = ArrayList<TrackGroup>()
    for (i in events.indices.reversed()) {
        val track = tracks[events[i].uri] ?: continue
        val key = track.albumId.toString()
        val group = albumByKey[key] ?: continue
        if (seen.add(key)) {
            result.add(group)
            if (result.size >= limit) break
        }
    }
    return result
}

/** Songs ranked by how many times you played them inside the chosen time frame. */
private fun mostPlayed(
    events: List<PlayEvent>,
    tracks: Map<String, Track>,
    frame: TimeFrame,
    limit: Int,
): List<Pair<Track, Int>> {
    val cutoff = frame.days?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L } ?: 0L
    // Plays saved by file path and older plays saved by song ID both count for the same song.
    val counts = HashMap<Track, Int>()
    for (event in events) {
        if (event.timeMs < cutoff) continue
        val track = tracks[event.uri] ?: continue
        counts[track] = (counts[track] ?: 0) + 1
    }
    return counts.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
        .take(limit)
}

// ---------------------------------------------------------------------------
// The Home screen
// ---------------------------------------------------------------------------
@Composable
fun HomeScreen(
    player: PlayerController,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    onOpenGroup: (TrackGroup) -> Unit,
) {
    val library = player.library
    val events = PlayHistory.events
    var timeFrame by remember { mutableStateOf(TimeFrame.WEEK) }

    // Plays are saved by file path (newer) or by song address (older), so accept both names.
    val trackByUri = remember(library) {
        val map = HashMap<String, Track>()
        for (track in library) {
            map[track.uri.toString()] = track
            map[track.key] = track
        }
        map
    }
    val artists = remember(library) { artistGroups(library) }
    val albums = remember(library) { albumGroups(library) }
    val artistByKey = remember(artists) { artists.associateBy { it.key } }
    val albumByKey = remember(albums) { albums.associateBy { it.key } }

    val playedSongs = remember(events, trackByUri) { recentSongs(events, trackByUri, 20) }
    val playedArtists = remember(events, trackByUri, artistByKey) {
        recentArtists(events, trackByUri, artistByKey, 12)
    }
    val playedAlbums = remember(events, trackByUri, albumByKey) {
        recentAlbums(events, trackByUri, albumByKey, 12)
    }
    val addedSongs = remember(library) { library.sortedByDescending { it.dateAdded }.take(20) }
    val addedAlbums = remember(albums) {
        albums.sortedByDescending { group -> group.tracks.maxOf { it.dateAdded } }.take(12)
    }
    val topSongs = remember(events, trackByUri, timeFrame) { mostPlayed(events, trackByUri, timeFrame, 10) }
    val topTracks = remember(topSongs) { topSongs.map { it.first } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Home",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }

        if (library.isEmpty()) {
            item {
                Text(
                    "Choose your music folders on the Songs tab to get started.",
                    Modifier.padding(16.dp),
                )
            }
        } else {
            if (playedSongs.isEmpty()) {
                item {
                    Text(
                        "Play some music and your recently played songs, artists and albums will show up here.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                if (ExtraSettings.homeRecentSongs) {
                    item { SectionTitle("Recently played songs") }
                    item { SongCarousel(playedSongs, player) }
                }
            }
            if (playedArtists.isNotEmpty() && ExtraSettings.homeRecentArtists) {
                item { SectionTitle("Recently played artists") }
                item { GroupCarousel(playedArtists, round = true, onOpenGroup = onOpenGroup) }
            }
            if (playedAlbums.isNotEmpty() && ExtraSettings.homeRecentAlbums) {
                item { SectionTitle("Recently played albums") }
                item { GroupCarousel(playedAlbums, round = false, onOpenGroup = onOpenGroup) }
            }

            if (ExtraSettings.homeAddedSongs) {
                item { SectionTitle("Recently added songs") }
                item { SongCarousel(addedSongs, player) }
            }
            if (ExtraSettings.homeAddedAlbums) {
                item { SectionTitle("Recently added albums") }
                item { GroupCarousel(addedAlbums, round = false, onOpenGroup = onOpenGroup) }
            }

            if (ExtraSettings.homeMostPlayed) {
                item { SectionTitle("Most played songs") }
            item {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimeFrame.entries.forEach { frame ->
                        FilterChip(
                            selected = timeFrame == frame,
                            onClick = { timeFrame = frame },
                            label = { Text(frame.label) },
                        )
                    }
                }
            }
            if (topSongs.isEmpty()) {
                item {
                    Text(
                        "No plays in this time frame yet.",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                itemsIndexed(topSongs) { index, (track, plays) ->
                    MostPlayedRow(
                        rank = index + 1,
                        track = track,
                        plays = plays,
                        onClick = { player.playList(topTracks, index) },
                    )
                }
            }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SongCarousel(tracks: List<Track>, player: PlayerController) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(tracks) { index, track ->
            Column(
                Modifier
                    .width(140.dp)
                    .clickable { player.playList(tracks, index) }
            ) {
                Artwork(track, sizePx = 384, modifier = Modifier.size(140.dp), corner = 12.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun GroupCarousel(
    groups: List<TrackGroup>,
    round: Boolean,
    onOpenGroup: (TrackGroup) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(groups) { _, group ->
            Column(
                Modifier
                    .width(140.dp)
                    .clickable { onOpenGroup(group) },
                horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
            ) {
                Artwork(
                    group.tracks.first(),
                    sizePx = 384,
                    modifier = Modifier.size(140.dp),
                    corner = if (round) 70.dp else 12.dp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    group.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = if (round) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    group.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (round) TextAlign.Center else TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MostPlayedRow(rank: Int, track: Track, plays: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            Modifier.width(28.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Artwork(track, sizePx = 128, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (plays == 1) "1 play" else "$plays plays",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
