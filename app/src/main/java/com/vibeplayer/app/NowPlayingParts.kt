package com.vibeplayer.app

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Seek bar: the played part is a moving wave, the rest is a straight line
// ---------------------------------------------------------------------------
@Composable
fun WavySeekBar(
    fraction: Float,
    playing: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinished by rememberUpdatedState(onFinished)

    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase",
    )
    // The wave flattens out while the music is paused.
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(400), label = "waveAmplitude")

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                    currentOnFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> currentOnChange((offset.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { currentOnFinished() },
                    onDragCancel = { currentOnFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            }
    ) {
        val width = size.width
        val centerY = size.height / 2f
        val thumbX = width * fraction.coerceIn(0f, 1f)
        val strokeWidth = 4.dp.toPx()
        val gap = 7.dp.toPx()
        val waveHeight = 3.5.dp.toPx() * amplitude
        val wavelength = 26.dp.toPx()
        val step = 3.dp.toPx()

        // The part already played: a wave.
        val waveEnd = (thumbX - gap).coerceAtLeast(0f)
        if (waveEnd > 0f) {
            val path = Path()
            path.moveTo(0f, centerY + waveHeight * sin(-phase))
            var x = 0f
            while (x < waveEnd) {
                x = (x + step).coerceAtMost(waveEnd)
                path.lineTo(x, centerY + waveHeight * sin(x / wavelength * 2f * PI.toFloat() - phase))
            }
            drawPath(
                path,
                color,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        // The part still to play: a straight, fainter line with a dot at the end.
        val restStart = (thumbX + gap).coerceAtMost(width)
        drawLine(
            color.copy(alpha = 0.3f),
            Offset(restStart, centerY),
            Offset(width, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(width - 4.dp.toPx(), centerY))

        // The handle: a tall thin pill.
        val thumbWidth = 4.dp.toPx()
        val thumbHeight = 26.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(thumbX - thumbWidth / 2f, centerY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f),
        )
    }
}

// ---------------------------------------------------------------------------
// Volume slider: a thick bar with a tall thin handle
// ---------------------------------------------------------------------------
@Composable
fun PillSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MaterialTheme.colorScheme.primary
    val currentOnChange by rememberUpdatedState(onChange)

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnChange((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> currentOnChange((offset.x / size.width).coerceIn(0f, 1f)) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                    },
                )
            }
    ) {
        val width = size.width
        val centerY = size.height / 2f
        val thumbX = width * value.coerceIn(0f, 1f)
        val trackWidth = 8.dp.toPx()
        val gap = 7.dp.toPx()

        val activeEnd = (thumbX - gap).coerceAtLeast(0f)
        if (activeEnd > 0f) {
            drawLine(color, Offset(0f, centerY), Offset(activeEnd, centerY), strokeWidth = trackWidth, cap = StrokeCap.Round)
        }
        val restStart = (thumbX + gap).coerceAtMost(width)
        drawLine(
            color.copy(alpha = 0.3f),
            Offset(restStart, centerY),
            Offset(width, centerY),
            strokeWidth = trackWidth,
            cap = StrokeCap.Round,
        )
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(width - 5.dp.toPx(), centerY))

        val thumbWidth = 4.dp.toPx()
        val thumbHeight = 30.dp.toPx()
        drawRoundRect(
            color,
            topLeft = Offset(thumbX - thumbWidth / 2f, centerY - thumbHeight / 2f),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f),
        )
    }
}

// ---------------------------------------------------------------------------
// Lyrics: big left-aligned lines, the current line bright. With word-by-word
// lyrics, the words light up one after another as they are sung.
// ---------------------------------------------------------------------------
@Composable
fun LyricsPane(lyrics: Lyrics, player: PlayerController, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val position = remember { mutableLongStateOf(0L) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val wordSynced = lyrics.wordSynced

    // Follow the song's position. Word-by-word lyrics need a quicker update.
    LaunchedEffect(lyrics) {
        while (true) {
            position.longValue = player.position()
            delay(if (wordSynced) 50L else 200L)
        }
    }
    val active by remember(lyrics) {
        derivedStateOf { if (lyrics.synced) activeLineIndex(lyrics.lines, position.longValue) else -1 }
    }

    // Keep the current line about a third of the way down, unless you are scrolling yourself.
    LaunchedEffect(active, viewportHeight) {
        if (active >= 0 && !listState.isScrollInProgress) {
            listState.animateScrollToItem(active, scrollOffset = -(viewportHeight * 0.3f).toInt())
        }
    }

    LazyColumn(
        modifier
            .onSizeChanged { viewportHeight = it.height }
            // Fade the lines out softly at the top and bottom edges.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.07f to Color.Black,
                        0.9f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        state = listState,
        contentPadding = PaddingValues(top = 32.dp, bottom = 160.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            LyricLineView(
                line = line,
                index = index,
                active = active,
                synced = lyrics.synced,
                position = position,
                onClick = { player.seekTo(line.timeMs) },
            )
        }
    }
}

@Composable
private fun LyricLineView(
    line: LyricLine,
    index: Int,
    active: Int,
    synced: Boolean,
    position: MutableLongState,
    onClick: () -> Unit,
) {
    val bright = MaterialTheme.colorScheme.onBackground
    val isActive = index == active
    val hasWords = isActive && line.words.isNotEmpty()

    val lineAlpha = when {
        !synced -> 0.85f
        isActive -> 1f
        index < active -> 0.28f
        else -> (0.55f - 0.04f * (index - active - 1)).coerceAtLeast(0.3f)
    }
    val style = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
    )

    val text: AnnotatedString = if (hasWords) {
        // Words already sung are bright, words still to come are dim, and the one being sung fades in.
        val now = position.longValue
        val dim = bright.copy(alpha = 0.4f)
        buildAnnotatedString {
            for (word in line.words) {
                val fraction = when {
                    now >= word.endMs -> 1f
                    now <= word.startMs -> 0f
                    else -> (now - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1L)
                }
                withStyle(SpanStyle(color = lerp(dim, bright, fraction))) { append(word.text) }
            }
        }
    } else {
        AnnotatedString(if (synced && line.text.isEmpty()) "♪" else line.text)
    }

    Text(
        text = text,
        style = style,
        color = if (hasWords) Color.Unspecified else bright.copy(alpha = lineAlpha),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = synced, onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

/** Shown in place of the lyrics when the song has none. */
@Composable
fun NoLyricsMessage(hasAccess: Boolean, onFind: () -> Unit) {
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
