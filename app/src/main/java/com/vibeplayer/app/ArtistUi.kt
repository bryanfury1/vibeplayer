package com.vibeplayer.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The picture for an artist, album or genre. Artists get their own photo when one can be found
 * (and the setting is on). Everything else, and artists without a photo, show album art as before.
 */
@Composable
fun GroupArtwork(group: TrackGroup, sizePx: Int, modifier: Modifier, corner: Dp = 8.dp) {
    if (group.isArtist) {
        ArtistArtwork(group.title, group.tracks.firstOrNull(), sizePx, modifier, corner)
    } else {
        Artwork(group.tracks.firstOrNull(), sizePx, modifier, corner)
    }
}

/** An artist's photo. While it loads, or if there is none, the album art of one of their songs is shown. */
@Composable
fun ArtistArtwork(name: String, fallback: Track?, sizePx: Int, modifier: Modifier, corner: Dp = 8.dp) {
    val context = LocalContext.current
    val enabled = ExtraSettings.artistInfoEnabled
    val picture by produceState<Bitmap?>(
        initialValue = if (enabled) ArtistInfo.peekPicture(name, sizePx) else null,
        name, sizePx, enabled,
    ) {
        value = if (enabled) ArtistInfo.loadPicture(context, name, sizePx) else null
    }
    val bitmap = picture
    if (bitmap != null) {
        Box(
            modifier
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    } else {
        Artwork(fallback, sizePx, modifier, corner)
    }
}

/** A short biography on an artist's page, with a "Read more" button. Shows nothing if none is found. */
@Composable
fun ArtistBio(name: String) {
    val context = LocalContext.current
    val enabled = ExtraSettings.artistInfoEnabled
    var expanded by remember { mutableStateOf(false) }
    val bio by produceState<ArtistBioInfo?>(initialValue = null, name, enabled) {
        value = if (enabled) ArtistInfo.loadBio(context, name) else null
    }
    val info = bio
    if (info != null) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                info.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Show less" else "Read more")
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Source: Wikipedia",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A song row inside an artist, album or genre page. In an album, the track number is shown
 * to the left of the song's art. Anywhere else it is the usual song row.
 */
@Composable
fun GroupTrackRow(group: TrackGroup, player: PlayerController, index: Int, track: Track) {
    val isCurrent = track.uri == player.currentTrack?.uri
    val play: () -> Unit = { player.playList(group.tracks, index) }
    if (!group.isAlbum) {
        TrackRow(
            track,
            isCurrent = isCurrent,
            onClick = play,
            onPlayNext = { player.playNext(track) },
            onAddToQueue = { player.addToQueue(track) },
        )
    } else {
        // Some phones store "disc number x 1000 + track number", so only the last three digits are the track.
        val number = track.trackNumber % 1000
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (number > 0) number.toString() else "–",
                Modifier
                    .width(44.dp)
                    .clickable(onClick = play)
                    .padding(start = 12.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(Modifier.weight(1f)) {
                TrackRow(
                    track,
                    isCurrent = isCurrent,
                    onClick = play,
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }
    }
}
