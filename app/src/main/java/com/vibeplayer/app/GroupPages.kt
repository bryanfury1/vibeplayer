package com.vibeplayer.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
private fun songCountText(count: Int): String = if (count == 1) "1 song" else "$count songs"

private fun releaseKind(songs: Int): String = when {
    songs <= 1 -> "Single"
    songs <= 6 -> "EP"
    else -> "Album"
}

private fun albumYear(album: TrackGroup): Int = album.tracks.maxOfOrNull { it.year } ?: 0

private fun albumAdded(album: TrackGroup): Long = album.tracks.maxOfOrNull { it.dateAdded } ?: 0L

private fun minutesText(ms: Long): String {
    val minutes = (ms / 60_000L).toInt()
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

/** A bright color taken from a picture's main color, used for the artist name and the Play button. */
private fun accentFor(hue: Float?, fallback: Color): Color =
    if (hue == null) fallback else Color.hsl(hue, 0.85f, 0.62f)

// ---------------------------------------------------------------------------
// The page for an artist, album or genre
// ---------------------------------------------------------------------------

/** Artists and albums get the new design. Genres keep the older simple page. */
@Composable
fun GroupScreenV2(
    group: TrackGroup,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
    onOpenGroup: (TrackGroup) -> Unit,
) {
    when {
        group.isArtist -> ArtistPage(group, player, padding, onBack, onOpenGroup)
        group.isAlbum -> AlbumPage(group, player, padding, onBack, onOpenGroup)
        else -> GroupScreen(group, player, padding, onBack)
    }
}

// ---------------------------------------------------------------------------
// The big picture at the top: full width, with the bottom softly blurred
// ---------------------------------------------------------------------------
@Composable
private fun PageRoundButton(
    onClick: () -> Unit,
    size: Dp = 40.dp,
    container: Color = Color.Black.copy(alpha = 0.35f),
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun PageHero(
    picture: Bitmap?,
    aspect: Float,
    onBack: () -> Unit,
    menuContent: @Composable (close: () -> Unit) -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    val pageBackground = MaterialTheme.colorScheme.background
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
    ) {
        if (picture != null) {
            val image = remember(picture) { picture.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
            )
            // A blurred copy of the picture that fades in over the bottom part.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.5f to Color.Transparent,
                                0.78f to Color.Black,
                                1f to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(28.dp),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        // A darker top (so the buttons show up) and a bottom that melts into the page.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.35f),
                        0.22f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to pageBackground.copy(alpha = 0.95f),
                    )
                )
        )

        // Back and more buttons
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageRoundButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Box {
                PageRoundButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options", tint = Color.White)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    menuContent { menuOpen = false }
                }
            }
        }

        // Name, details and buttons, at the bottom
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = bottom,
        )
    }
}

/** The round buttons under the name: About (i), Play, and Favorite (star). */
@Composable
private fun HeroActions(
    accent: Color,
    favorite: Boolean,
    onInfo: () -> Unit,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
) {
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageRoundButton(onClick = onInfo, size = 48.dp, container = Color.White.copy(alpha = 0.16f)) {
            Icon(Icons.Rounded.Info, contentDescription = "About", tint = Color.White)
        }
        PageRoundButton(onClick = onPlay, size = 68.dp, container = accent) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Play",
                tint = onAccent,
                modifier = Modifier.size(38.dp),
            )
        }
        PageRoundButton(onClick = onFavorite, size = 48.dp, container = Color.White.copy(alpha = 0.16f)) {
            Icon(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "Favorite",
                tint = if (favorite) accent else Color.White,
            )
        }
    }
}

/**
 * The artist's name in a stylish "signature" look, in a color taken from their photo.
 * There is no signature picture to download for artists, so this uses a lettering style
 * chosen from the artist's name: the same artist always gets the same style.
 */
@Composable
private fun ArtistSignature(name: String, color: Color) {
    val variant = (name.lowercase(Locale.ROOT).hashCode() and 0x7fffffff) % 4
    val base = when {
        name.length > 22 -> 28.sp
        name.length > 14 -> 36.sp
        else -> 46.sp
    }
    val shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 8f)
    when (variant) {
        0 -> Text(
            name.uppercase(Locale.ROOT),
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
                letterSpacing = 2.sp,
                fontSize = base,
                shadow = shadow,
            ),
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        1 -> Text(
            name,
            style = TextStyle(
                fontFamily = FontFamily.Cursive,
                fontWeight = FontWeight.Bold,
                fontSize = base * 1.25f,
                shadow = shadow,
            ),
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        2 -> Text(
            name.lowercase(Locale.ROOT),
            style = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = base,
                shadow = shadow,
            ),
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        else -> Text(
            name,
            style = TextStyle(
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.Medium,
                fontSize = base,
                shadow = shadow,
            ),
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun PageSectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

// ---------------------------------------------------------------------------
// Bio pop-up (opened with the "i" button)
// ---------------------------------------------------------------------------
private class BioState(val loading: Boolean, val info: ArtistBioInfo?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BioSheet(
    title: String,
    subtitle: String,
    state: BioState,
    enabled: Boolean,
    emptyMessage: String,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            val info = state.info
            when {
                !enabled -> Text(
                    "Pictures and bios from the internet are turned off. You can turn them on in Settings, under Artists.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                info == null -> Text(emptyMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(info.text, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Source: Wikipedia",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (info.pageUrl.isNotBlank()) {
                            TextButton(onClick = { uriHandler.openUri(info.pageUrl) }) {
                                Text("Read more")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistBioSheet(name: String, details: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled = ExtraSettings.artistInfoEnabled
    val state by produceState(BioState(true, null), name, enabled) {
        value = if (enabled) BioState(false, ArtistInfo.loadBio(context, name)) else BioState(false, null)
    }
    BioSheet(name, details, state, enabled, "No biography was found for this artist.", onDismiss)
}

@Composable
private fun AlbumBioSheet(album: String, artist: String, details: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val enabled = ExtraSettings.artistInfoEnabled
    val state by produceState(BioState(true, null), album, artist, enabled) {
        value = if (enabled) BioState(false, ArtistInfo.loadAlbumBio(context, album, artist))
        else BioState(false, null)
    }
    BioSheet(album, details, state, enabled, "No description was found for this album.", onDismiss)
}

// ---------------------------------------------------------------------------
// Artist page
// ---------------------------------------------------------------------------
@Composable
private fun ArtistPage(
    group: TrackGroup,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
    onOpenGroup: (TrackGroup) -> Unit,
) {
    val context = LocalContext.current
    val enabled = ExtraSettings.artistInfoEnabled

    // The artist's albums, newest first.
    val albums = remember(group) {
        albumGroups(group.tracks).sortedWith(
            compareByDescending<TrackGroup> { albumYear(it) }.thenByDescending { albumAdded(it) }
        )
    }
    val latest = albums.firstOrNull()

    // Songs of this artist that have been played, most played first.
    val playEvents = PlayHistory.events
    val topSongs = remember(group, playEvents) {
        val counts = playEvents.groupingBy { it.uri }.eachCount()
        group.tracks
            .filter { (counts[it.key] ?: 0) > 0 }
            .sortedByDescending { counts[it.key] ?: 0 }
            .take(5)
    }

    // The picture at the top: the artist's photo, or album art until the photo arrives (or if there is none).
    val photo by produceState<Bitmap?>(
        initialValue = if (enabled) ArtistInfo.peekPicture(group.title, 1000) else null,
        group.title, enabled,
    ) {
        value = if (enabled) ArtistInfo.loadPicture(context, group.title, 1000) else null
    }
    val artTrack = latest?.tracks?.firstOrNull() ?: group.tracks.firstOrNull()
    val art by produceState<Bitmap?>(
        initialValue = artTrack?.let { ArtworkLoader.peek(it, 1024) },
        artTrack?.uri,
    ) {
        value = if (artTrack == null) null else ArtworkLoader.load(context, artTrack, 1024)
    }
    val shown = photo ?: art
    val hue by produceState<Float?>(initialValue = null, shown) {
        value = withContext(Dispatchers.Default) { dominantHue(shown) }
    }
    val accent = accentFor(hue, MaterialTheme.colorScheme.primary)

    var showBio by remember { mutableStateOf(false) }
    var showAddAll by remember { mutableStateOf(false) }
    var showAddLatest by remember { mutableStateOf(false) }
    val favorite = FavoriteGroups.isFavorite(group)
    val details = "${songCountText(group.tracks.size)} • " +
        (if (albums.size == 1) "1 release" else "${albums.size} releases")

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
    ) {
        item {
            PageHero(
                picture = shown,
                aspect = 0.85f,
                onBack = onBack,
                menuContent = { close ->
                    DropdownMenuItem(
                        text = { Text("Shuffle") },
                        onClick = {
                            close()
                            player.playList(group.tracks, group.tracks.indices.random(), shuffle = true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add all songs to a playlist") },
                        onClick = {
                            close()
                            showAddAll = true
                        },
                    )
                },
                bottom = {
                    ArtistSignature(group.title, accent)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(14.dp))
                    HeroActions(
                        accent = accent,
                        favorite = favorite,
                        onInfo = { showBio = true },
                        onPlay = { player.playList(group.tracks, 0, shuffle = false) },
                        onFavorite = { FavoriteGroups.toggle(context, group) },
                    )
                },
            )
        }

        if (latest != null) {
            item { PageSectionTitle("Latest release") }
            item {
                val year = albumYear(latest)
                Row(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onOpenGroup(latest) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(latest.tracks.firstOrNull(), sizePx = 256, modifier = Modifier.size(64.dp), corner = 10.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (year > 0) "$year" else "Recently added",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            latest.title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${releaseKind(latest.tracks.size)} • ${songCountText(latest.tracks.size)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showAddLatest = true }) {
                        Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add to a playlist")
                    }
                }
            }
        }

        if (topSongs.isNotEmpty()) {
            item { PageSectionTitle("Top songs") }
            itemsIndexed(topSongs) { index, track ->
                TrackRow(
                    track,
                    isCurrent = track.uri == player.currentTrack?.uri,
                    onClick = { player.playList(topSongs, index) },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }

        if (albums.isNotEmpty()) {
            item { PageSectionTitle("Albums") }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums) { album ->
                        Column(
                            Modifier
                                .width(150.dp)
                                .clickable { onOpenGroup(album) }
                        ) {
                            Artwork(
                                album.tracks.firstOrNull(),
                                sizePx = 384,
                                modifier = Modifier.size(150.dp),
                                corner = 12.dp,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                album.title,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val year = albumYear(album)
                            Text(
                                if (year > 0) "$year • ${releaseKind(album.tracks.size)}"
                                else releaseKind(album.tracks.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "All songs",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = {
                        player.playList(group.tracks, group.tracks.indices.random(), shuffle = true)
                    }
                ) {
                    Text("Shuffle")
                }
            }
        }
        itemsIndexed(group.tracks) { index, track ->
            GroupTrackRow(group, player, index, track)
        }
    }

    if (showBio) ArtistBioSheet(group.title, details, onDismiss = { showBio = false })
    if (showAddAll) AddToPlaylistDialog(group.tracks, onDismiss = { showAddAll = false })
    if (showAddLatest && latest != null) {
        AddToPlaylistDialog(latest.tracks, onDismiss = { showAddLatest = false })
    }
}

// ---------------------------------------------------------------------------
// Album page
// ---------------------------------------------------------------------------
@Composable
private fun AlbumPage(
    group: TrackGroup,
    player: PlayerController,
    padding: PaddingValues,
    onBack: () -> Unit,
    onOpenGroup: (TrackGroup) -> Unit,
) {
    val context = LocalContext.current

    val artTrack = group.tracks.firstOrNull()
    val art by produceState<Bitmap?>(
        initialValue = artTrack?.let { ArtworkLoader.peek(it, 1024) },
        artTrack?.uri,
    ) {
        value = if (artTrack == null) null else ArtworkLoader.load(context, artTrack, 1024)
    }
    val hue by produceState<Float?>(initialValue = null, art) {
        value = withContext(Dispatchers.Default) { dominantHue(art) }
    }
    val accent = accentFor(hue, MaterialTheme.colorScheme.primary)

    // The artist shown under the title opens that artist's page.
    val artistName = group.subtitle.substringBefore(" • ")
    val primaryArtist = remember(group) {
        splitArtists(artTrack?.artist.orEmpty()).firstOrNull() ?: artistName
    }
    val allArtists = rememberArtists(player.library)
    val artistGroup = remember(allArtists, primaryArtist) {
        allArtists.firstOrNull { it.title.equals(primaryArtist, ignoreCase = true) }
    }

    val year = remember(group) { albumYear(group) }
    val totalMs = remember(group) { group.tracks.sumOf { it.durationMs } }
    val details = listOfNotNull(
        releaseKind(group.tracks.size),
        if (year > 0) "$year" else null,
        songCountText(group.tracks.size),
    ).joinToString(" • ")

    var showBio by remember { mutableStateOf(false) }
    var showAddAll by remember { mutableStateOf(false) }
    val favorite = FavoriteGroups.isFavorite(group)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
    ) {
        item {
            PageHero(
                picture = art,
                aspect = 0.92f,
                onBack = onBack,
                menuContent = { close ->
                    DropdownMenuItem(
                        text = { Text("Shuffle") },
                        onClick = {
                            close()
                            player.playList(group.tracks, group.tracks.indices.random(), shuffle = true)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Add all songs to a playlist") },
                        onClick = {
                            close()
                            showAddAll = true
                        },
                    )
                    if (artistGroup != null) {
                        DropdownMenuItem(
                            text = { Text("Go to artist") },
                            onClick = {
                                close()
                                onOpenGroup(artistGroup)
                            },
                        )
                    }
                },
                bottom = {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artistName,
                        Modifier.clickable(enabled = artistGroup != null) { artistGroup?.let(onOpenGroup) },
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(14.dp))
                    HeroActions(
                        accent = accent,
                        favorite = favorite,
                        onInfo = { showBio = true },
                        onPlay = { player.playList(group.tracks, 0, shuffle = false) },
                        onFavorite = { FavoriteGroups.toggle(context, group) },
                    )
                },
            )
        }

        itemsIndexed(group.tracks) { index, track ->
            GroupTrackRow(group, player, index, track)
        }

        item {
            Text(
                "${songCountText(group.tracks.size)}, ${minutesText(totalMs)}" +
                    (if (year > 0) " • Released $year" else ""),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showBio) AlbumBioSheet(group.title, artistName, details, onDismiss = { showBio = false })
    if (showAddAll) AddToPlaylistDialog(group.tracks, onDismiss = { showAddAll = false })
}

// ---------------------------------------------------------------------------
// The Artists and Albums lists, now with a Favorites filter
// ---------------------------------------------------------------------------
@Composable
fun GroupListScreenV2(
    title: String,
    groups: List<TrackGroup>,
    padding: PaddingValues,
    roundArt: Boolean = false,
    onOpen: (TrackGroup) -> Unit,
) {
    var onlyFavorites by remember { mutableStateOf(false) }
    val shown = if (onlyFavorites) groups.filter { FavoriteGroups.isFavorite(it) } else groups

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Text(
                title,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !onlyFavorites,
                    onClick = { onlyFavorites = false },
                    label = { Text("All") },
                )
                FilterChip(
                    selected = onlyFavorites,
                    onClick = { onlyFavorites = true },
                    label = { Text("Favorites") },
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    if (onlyFavorites) "No favorites yet. Open one and tap the star." else "Nothing here yet.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(shown) { group ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(group) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupArtwork(
                    group,
                    sizePx = 192,
                    modifier = Modifier.size(56.dp),
                    corner = if (roundArt) 28.dp else 8.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.title,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        group.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (FavoriteGroups.isFavorite(group)) {
                    Icon(
                        Icons.Rounded.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
