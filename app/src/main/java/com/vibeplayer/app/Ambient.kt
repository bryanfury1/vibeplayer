package com.vibeplayer.app

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.exp
import kotlin.math.max

// ---------------------------------------------------------------------------
// The moving bars
// ---------------------------------------------------------------------------
@Composable
fun SpectrumView(player: PlayerController, modifier: Modifier, color: Color) {
    val barCount = VisualizerData.BANDS
    val smooth = remember { FloatArray(barCount) }
    var tick by remember { mutableLongStateOf(0L) }

    // Every screen frame: move each bar quickly up to its target, and let it fall back slowly.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val seconds = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
                last = now
                val rise = 1f - exp(-seconds * 35f)
                val fall = exp(-seconds * 6f)
                val target = VisualizerData.bands
                val playing = player.isPlaying
                for (i in 0 until barCount) {
                    val goal = if (playing && i < target.size) target[i] else 0f
                    val current = smooth[i]
                    smooth[i] = if (goal > current) current + (goal - current) * rise else current * fall
                }
                tick = now
            }
        }
    }

    Canvas(modifier) {
        val frame = tick // reading this makes the bars redraw every frame
        if (frame == Long.MIN_VALUE) return@Canvas
        val slot = size.width / barCount
        val barWidth = slot * 0.7f
        for (i in 0 until barCount) {
            val barHeight = max(smooth[i] * size.height, barWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The idle screen: black background, clock, album art in the middle, bars at the bottom
// ---------------------------------------------------------------------------
@Composable
fun AmbientScreen(player: PlayerController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val track = player.currentTrack
    var clock by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val format = DateFormat.getTimeFormat(context)
        while (true) {
            clock = format.format(Date())
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                onDismiss()
            }
    ) {
        if (AppSettings.showClock) {
            Text(
                clock,
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 24.dp),
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Light,
            )
        }

        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(track, sizePx = 1024, modifier = Modifier.size(260.dp), corner = 24.dp)
            Spacer(Modifier.height(24.dp))
            Text(
                track?.title.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track?.artist.orEmpty(),
                color = Color(0xFFAAAAAA),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SpectrumView(
            player = player,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
