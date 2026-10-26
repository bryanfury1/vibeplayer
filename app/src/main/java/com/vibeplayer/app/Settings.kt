package com.vibeplayer.app

import android.content.Context
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** The app's settings. They are saved on the phone and applied immediately. */
object AppSettings {
    private const val PREFS = "vibeplayer_settings"

    var idleEnabled by mutableStateOf(true)
    var idleSeconds by mutableIntStateOf(30)
    var idleOnlyWhilePlaying by mutableStateOf(true)
    var showClock by mutableStateOf(true)
    var batchAcceptPlain by mutableStateOf(true)
    var batchEmbed by mutableStateOf(false)

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        idleEnabled = prefs.getBoolean("idle_enabled", true)
        idleSeconds = prefs.getInt("idle_seconds", 30)
        idleOnlyWhilePlaying = prefs.getBoolean("idle_only_playing", true)
        showClock = prefs.getBoolean("show_clock", true)
        batchAcceptPlain = prefs.getBoolean("batch_accept_plain", true)
        batchEmbed = prefs.getBoolean("batch_embed", false)
    }

    fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("idle_enabled", idleEnabled)
            .putInt("idle_seconds", idleSeconds)
            .putBoolean("idle_only_playing", idleOnlyWhilePlaying)
            .putBoolean("show_clock", showClock)
            .putBoolean("batch_accept_plain", batchAcceptPlain)
            .putBoolean("batch_embed", batchEmbed)
            .apply()
    }
}

@Composable
fun SettingsScreen(
    library: List<Track>,
    onClose: () -> Unit,
    onManageFolders: () -> Unit,
    onPreviewIdle: () -> Unit,
) {
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var showRecover by remember { mutableStateOf(false) }
    var hasFileAccess by remember { mutableStateOf(LyricsRepository.hasAllFilesAccess()) }

    // Notice when you come back from Android's settings with file access switched on.
    LaunchedEffect(Unit) {
        while (true) {
            hasFileAccess = LyricsRepository.hasAllFilesAccess()
            delay(1000)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            // ---- Idle screen ----
            SettingsHeader("Idle screen")
            SwitchRow(
                title = "Show the idle screen",
                subtitle = "A dark screen with album art and moving bars appears when you haven't touched the app for a while. The screen stays on while music plays.",
                checked = AppSettings.idleEnabled,
                onChange = {
                    AppSettings.idleEnabled = it
                    AppSettings.save(context)
                },
            )
            Text(
                "Start after",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(15, 30, 60, 120, 300).forEach { seconds ->
                    FilterChip(
                        selected = AppSettings.idleSeconds == seconds,
                        onClick = {
                            AppSettings.idleSeconds = seconds
                            AppSettings.save(context)
                        },
                        label = { Text(if (seconds < 60) "$seconds sec" else "${seconds / 60} min") },
                    )
                }
            }
            SwitchRow(
                title = "Only while music is playing",
                subtitle = "Turn off to show the idle screen even when paused.",
                checked = AppSettings.idleOnlyWhilePlaying,
                onChange = {
                    AppSettings.idleOnlyWhilePlaying = it
                    AppSettings.save(context)
                },
            )
            SwitchRow(
                title = "Show a clock",
                subtitle = "Shows the time at the top of the idle screen.",
                checked = AppSettings.showClock,
                onChange = {
                    AppSettings.showClock = it
                    AppSettings.save(context)
                },
            )
            OutlinedButton(
                onClick = onPreviewIdle,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Show the idle screen now")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Library ----
            SettingsHeader("Library")
            OutlinedButton(
                onClick = onManageFolders,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text("Choose music folders")
            }

            // ---- File access ----
            if (Build.VERSION.SDK_INT >= 30) {
                Text(
                    if (hasFileAccess) "File access: on (lyrics are saved next to your songs)"
                    else "File access: off (lyrics are kept inside the app)",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!hasFileAccess) {
                    OutlinedButton(
                        onClick = { openAllFilesAccessSettings(context) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text("Allow file access")
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- Lyrics ----
            SettingsHeader("Lyrics")
            SwitchRow(
                title = "Accept lyrics without timing",
                subtitle = "When no synced version exists, plain text lyrics are used.",
                checked = AppSettings.batchAcceptPlain,
                onChange = {
                    AppSettings.batchAcceptPlain = it
                    AppSettings.save(context)
                },
            )
            SwitchRow(
                title = "Also save inside the song files",
                subtitle = "Writes the lyrics into each file's tags as well. Needs file access, and changes your files.",
                checked = AppSettings.batchEmbed,
                onChange = {
                    AppSettings.batchEmbed = it
                    AppSettings.save(context)
                },
            )
            if (BatchLyrics.running) {
                LinearProgressIndicator(
                    progress = {
                        if (BatchLyrics.total == 0) 0f else BatchLyrics.done / BatchLyrics.total.toFloat()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Text(
                    "${BatchLyrics.done} of ${BatchLyrics.total} songs checked, ${BatchLyrics.found} lyrics downloaded",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    BatchLyrics.status,
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                OutlinedButton(
                    onClick = { BatchLyrics.cancel() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Stop")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        BatchLyrics.start(
                            context,
                            library,
                            AppSettings.batchAcceptPlain,
                            AppSettings.batchEmbed && hasFileAccess,
                        )
                    },
                    enabled = library.isNotEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Download missing lyrics for all songs")
                }
                if (BatchLyrics.summary.isNotEmpty()) {
                    Text(
                        BatchLyrics.summary,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            // ---- History ----
            SettingsHeader("Listening history")
            Text(
                "${PlayHistory.events.size} plays recorded",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmClear = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Clear listening history")
            }
            OutlinedButton(
                onClick = { showRecover = true },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Recover lost plays")
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showRecover) {
        RecoverPlaysDialog(library, onDismiss = { showRecover = false })
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear listening history?") },
            text = { Text("Recently played and most played lists on Home will start over.") },
            confirmButton = {
                Button(onClick = {
                    PlayHistory.clear()
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}
