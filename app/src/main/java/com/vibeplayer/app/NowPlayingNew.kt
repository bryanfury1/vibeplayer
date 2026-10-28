package com.vibeplayer.app

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether lyrics are shown instead of the album art. Remembered while the app stays open. */
object NowPlayingPrefs {
    var showLyrics by mutableStateOf(false)
}

private class LyricsLoad(val loading: Boolean, val lyrics: Lyrics?)

/** Turns "FLAC (96kHz/24bit)" into "FLAC · 24-bit · 96 kHz". Anything unexpected is shown as it is. */
private fun prettyFormat(text: String): String {
    val match = Regex("""^(\S+)\s*\((.*)\)$""").find(text.trim()) ?: return text
    val name = match.groupValues[1]
    val parts = match.groupValues[2].split('/').map { it.trim() }
    val bits = parts.firstOrNull { it.contains("bit", ignoreCase = true) }
        ?.let { Regex("""[\d.]+""").find(it)?.value }
        ?.let { "$it-bit" }
    val rate = parts.firstOrNull { it.contains("khz", ignoreCase = true) }
        ?.let { Regex("""[\d.]+""").find(it)?.value }
        ?.let { "$it kHz" }
    val pieces = listOfNotNull(name, bits, rate)
    return if (pieces.size > 1) pieces.joinToString(" · ") else text
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

// ---------------------------------------------------------------------------
// Colors taken from the album art
// ---------------------------------------------------------------------------

/** The main color (as a position on the color wheel, 0 to 360) of a picture, or null for gray pictures. */
private fun dominantHue(bitmap: Bitmap?): Float? {
    if (bitmap == null) return null
    try {
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val hsv = FloatArray(3)
        val weights = FloatArray(12)
        val hueSums = FloatArray(12)
        for (y in 0 until 24) {
            for (x in 0 until 24) {
                android.graphics.Color.colorToHSV(small.getPixel(x, y), hsv)
                val saturation = hsv[1]
                val brightness = hsv[2]
                if (saturation < 0.25f || brightness < 0.2f) continue // gray, white or black: no color to pick up
                val weight = saturation * brightness
                val bucket = (hsv[0] / 30f).toInt().coerceIn(0, 11)
                weights[bucket] += weight
                hueSums[bucket] += hsv[0] * weight
            }
        }
        var best = -1
        var bestWeight = 0f
        for (i in 0 until 12) {
            if (weights[i] > bestWeight) {
                bestWeight = weights[i]
                best = i
            }
        }
        if (best < 0 || bestWeight < 3f) return null
        return hueSums[best] / weights[best]
    } catch (e: Exception) {
        return null
    }
}

/** Gives everything inside the accent colors of the current song's album art. */
@Composable
private fun ArtColorTheme(track: Track?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val enabled = ExtraSettings.nowPlayingArtColors
    val hue by produceState<Float?>(initialValue = null, track?.uri, enabled) {
        value = if (!enabled || track == null) {
            null
        } else {
            val bitmap = try {
                ArtworkLoader.load(context, track, 128)
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Default) { dominantHue(bitmap) }
        }
    }
    val base = MaterialTheme.colorScheme
    val seed = hue
    val colors = if (seed == null) {
        base
    } else {
        val dark = base.background.luminance() < 0.5f
        // New accent colors, but the background stays the one you chose in Settings.
        schemeForHue(seed, dark).copy(
            background = base.background,
            surface = base.surface,
            onBackground = base.onBackground,
            onSurface = base.onSurface,
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// ---------------------------------------------------------------------------
// The Now Playing screen
// ---------------------------------------------------------------------------
@Composable
fun NowPlayingScreenV2(
    player: PlayerController,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAmbient: () -> Unit,
) {
    val track = player.currentTrack
    ArtColorTheme(track) {
        NowPlayingContent(player, track, onClose, onOpenQueue, onOpenAmbient)
    }
}

@Composable
private fun TransportButton(
    modifier: Modifier,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    icon: ImageVector,
    description: String,
    iconSize: Dp = 30.dp,
) {
    Box(
        modifier
            .padding(horizontal = 3.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = content, modifier = Modifier.size(iconSize))
    }
}

@Composable
private fun NowPlayingContent(
    player: PlayerController,
    track: Track?,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAmbient: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var formatText by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    var lyricsReload by remember { mutableIntStateOf(0) }
    var showFind by remember { mutableStateOf(false) }
    var hasAccess by remember { mutableStateOf(LyricsRepository.hasAllFilesAccess()) }

    // Update the seek bar position 10 times a second.
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) position = player.position()
            delay(100)
        }
    }
    // Work out the "FLAC · 24-bit · 96 kHz" text whenever the song changes.
    LaunchedEffect(track?.id) {
        formatText = if (track == null) "" else prettyFormat(AudioInfo.describe(context, track))
    }
    // Notice when you come back from Android's settings with file access switched on.
    LaunchedEffect(Unit) {
        while (true) {
            hasAccess = LyricsRepository.hasAllFilesAccess()
            delay(1000)
        }
    }
    val lyricsLoad by produceState(LyricsLoad(true, null), track?.uri, lyricsReload, hasAccess) {
        value = LyricsLoad(true, null)
        value = LyricsLoad(false, if (track == null) null else LyricsRepository.load(context, track))
    }

    val duration = (if (player.durationMs > 0) player.durationMs else (track?.durationMs ?: 0L))
        .coerceAtLeast(1L)
    val shownPosition = if (dragging) (dragFraction * duration).toLong() else position
    val fraction = if (dragging) dragFraction else (position.toFloat() / duration).coerceIn(0f, 1f)

    val favorite = track != null && PlaylistStore.isFavorite(track)
    val showLyrics = NowPlayingPrefs.showLyrics

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {

            // ---- Top bar: collapse arrow, title, queue, more ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close")
                }
                Text(
                    "Now Playing",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Idle screen with visualizer") },
                            leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenAmbient()
                            },
                        )
                        if (track != null) {
                            DropdownMenuItem(
                                text = { Text("Add to playlist") },
                                onClick = {
                                    menuOpen = false
                                    PlaylistPicker.track = track
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit info") },
                                onClick = {
                                    menuOpen = false
                                    TagEditorState.track = track
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Find lyrics online") },
                                onClick = {
                                    menuOpen = false
                                    showFind = true
                                },
                            )
                            if (lyricsLoad.lyrics != null) {
                                DropdownMenuItem(
                                    text = { Text("Save lyrics inside the song file") },
                                    onClick = {
                                        menuOpen = false
                                        scope.launch {
                                            val message = if (!hasAccess) {
                                                "Allow file access first (Settings), so lyrics can be written into the file."
                                            } else {
                                                val saved = LyricsRepository.loadSavedText(context, track)
                                                if (saved == null) {
                                                    "These lyrics are already inside the song file."
                                                } else {
                                                    try {
                                                        withContext(Dispatchers.IO) {
                                                            TagEditor.writeLyrics(context, track, saved)
                                                        }
                                                        "Lyrics saved inside the song file."
                                                    } catch (e: Exception) {
                                                        "Couldn't write the lyrics: ${e.message ?: e.javaClass.simpleName}"
                                                    }
                                                }
                                            }
                                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ---- Middle: album art, or the lyrics in its place ----
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showLyrics) {
                        val lyrics = lyricsLoad.lyrics
                        when {
                            track == null -> {}
                            lyricsLoad.loading -> Text(
                                "Loading lyrics…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            lyrics == null -> NoLyricsMessage(hasAccess, onFind = { showFind = true })
                            else -> LyricsPane(lyrics, player, Modifier.fillMaxSize())
                        }
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val side = minOf(maxWidth * 0.66f, maxHeight)
                            Artwork(track, sizePx = 1024, modifier = Modifier.size(side), corner = 28.dp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- Title and artist ----
                Text(
                    track?.title ?: "",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artist ?: "",
                    Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(20.dp))

                // ---- Seek bar, with the times and the format chip under it ----
                WavySeekBar(
                    fraction = fraction,
                    playing = player.isPlaying,
                    onChange = {
                        dragging = true
                        dragFraction = it
                    },
                    onFinished = {
                        val target = (dragFraction * duration).toLong()
                        player.seekTo(target)
                        position = target
                        dragging = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatClock(shownPosition),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (formatText.isNotEmpty()) {
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    formatText,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        formatClock(duration),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // ---- Transport: shuffle, previous, play/pause, next, repeat ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = player::toggleShuffle) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (player.shuffleOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    TransportButton(
                        Modifier.weight(1.1f),
                        onClick = player::previous,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = Icons.Rounded.SkipPrevious,
                        description = "Previous",
                    )
                    TransportButton(
                        Modifier.weight(1.7f),
                        onClick = player::togglePlayPause,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                        icon = if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        description = if (player.isPlaying) "Pause" else "Play",
                        iconSize = 38.dp,
                    )
                    TransportButton(
                        Modifier.weight(1.1f),
                        onClick = player::next,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                        icon = Icons.Rounded.SkipNext,
                        description = "Next",
                    )
                    Box(Modifier.weight(0.8f), contentAlignment = Alignment.Center) {
                        IconButton(onClick = player::cycleRepeat) {
                            Icon(
                                if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                                else Icons.Rounded.Repeat,
                                contentDescription = "Repeat",
                                tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ---- Volume ----
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = player::toggleMute) {
                        Icon(
                            if (player.muted || player.volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeDown,
                            contentDescription = "Mute",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PillSlider(
                        value = if (player.muted) 0f else player.volume,
                        onChange = player::changeVolume,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                    )
                    IconButton(onClick = { player.changeVolume(1f) }) {
                        Icon(
                            Icons.Rounded.VolumeUp,
                            contentDescription = "Maximum volume",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- Bottom row: lyrics on the left, queue in the middle, favorite on the right ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (showLyrics) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            )
                            .clickable { NowPlayingPrefs.showLyrics = !NowPlayingPrefs.showLyrics },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Lyrics,
                            contentDescription = "Lyrics",
                            tint = if (showLyrics) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenQueue, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = "Open queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { if (track != null) PlaylistStore.toggleFavorite(track) },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showFind && track != null) {
        FindLyricsDialog(
            track = track,
            onDismiss = { showFind = false },
            onSaved = {
                showFind = false
                lyricsReload++
            },
        )
    }
}
