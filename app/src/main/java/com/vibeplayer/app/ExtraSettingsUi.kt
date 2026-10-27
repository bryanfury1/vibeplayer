package com.vibeplayer.app

import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

// ---------------------------------------------------------------------------
// Small building blocks (same look as the rest of the Settings screen)
// ---------------------------------------------------------------------------

@Composable
private fun ExtraHeader(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ExtraNote(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExtraSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A title and a row of chips where exactly one is selected. */
@Composable
private fun OptionChips(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Text(
        title,
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = { Text(label) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Appearance (shown at the top of Settings)
// ---------------------------------------------------------------------------
@Composable
fun AppearanceSettingsSection() {
    val context = LocalContext.current
    val dynamicAvailable = Build.VERSION.SDK_INT >= 31

    ExtraHeader("Appearance")
    OptionChips("Theme", listOf("Dark", "Light", "Follow system"), themeModeToChip(ExtraSettings.themeMode)) { chip ->
        ExtraSettings.themeMode = chipToThemeMode(chip)
        ExtraSettings.save(context)
    }
    if (dynamicAvailable) {
        ExtraSwitchRow(
            title = "Use wallpaper colors",
            subtitle = "Matches the app's colors to your wallpaper. Turn off to pick a color theme yourself.",
            checked = ExtraSettings.useDynamicColor,
            onChange = {
                ExtraSettings.useDynamicColor = it
                ExtraSettings.save(context)
            },
        )
    }
    if (!dynamicAvailable || !ExtraSettings.useDynamicColor) {
        Text(
            "Color theme",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            colorThemeNames.forEachIndexed { index, name ->
                FilterChip(
                    selected = ExtraSettings.colorTheme == index,
                    onClick = {
                        ExtraSettings.colorTheme = index
                        ExtraSettings.save(context)
                    },
                    label = { Text(name) },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .background(themePreviewColor(index), CircleShape)
                        )
                    },
                )
            }
        }
    }
    ExtraSwitchRow(
        title = "Pure black in dark mode",
        subtitle = "Uses true black backgrounds, which can save battery on OLED screens.",
        checked = ExtraSettings.amoledBlack,
        onChange = {
            ExtraSettings.amoledBlack = it
            ExtraSettings.save(context)
        },
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
}

// The Theme chips are listed as Dark, Light, Follow system, but the saved numbers are
// 1 = dark, 2 = light, 0 = follow system. These two helpers convert between them.
private fun themeModeToChip(mode: Int): Int = when (mode) {
    1 -> 0
    2 -> 1
    else -> 2
}

private fun chipToThemeMode(chip: Int): Int = when (chip) {
    0 -> 1
    1 -> 2
    else -> 0
}

// ---------------------------------------------------------------------------
// Home, library, playback and backup (shown above "Listening history")
// ---------------------------------------------------------------------------
@Composable
fun MoreSettingsSections() {
    val context = LocalContext.current

    // ---- Home screen ----
    ExtraHeader("Home screen")
    ExtraNote("Choose which sections appear on Home.")
    ExtraSwitchRow("Recently played songs", null, ExtraSettings.homeRecentSongs) {
        ExtraSettings.homeRecentSongs = it
        ExtraSettings.save(context)
    }
    ExtraSwitchRow("Recently played artists", null, ExtraSettings.homeRecentArtists) {
        ExtraSettings.homeRecentArtists = it
        ExtraSettings.save(context)
    }
    ExtraSwitchRow("Recently played albums", null, ExtraSettings.homeRecentAlbums) {
        ExtraSettings.homeRecentAlbums = it
        ExtraSettings.save(context)
    }
    ExtraSwitchRow("Recently added songs", null, ExtraSettings.homeAddedSongs) {
        ExtraSettings.homeAddedSongs = it
        ExtraSettings.save(context)
    }
    ExtraSwitchRow("Recently added albums", null, ExtraSettings.homeAddedAlbums) {
        ExtraSettings.homeAddedAlbums = it
        ExtraSettings.save(context)
    }
    ExtraSwitchRow("Most played songs", null, ExtraSettings.homeMostPlayed) {
        ExtraSettings.homeMostPlayed = it
        ExtraSettings.save(context)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Library filters ----
    ExtraHeader("Library filters")
    val lengths = listOf(0, 15, 30, 60, 120)
    OptionChips(
        "Hide songs shorter than",
        listOf("Off", "15 sec", "30 sec", "1 min", "2 min"),
        lengths.indexOf(ExtraSettings.minSongSeconds).coerceAtLeast(0),
    ) { index ->
        ExtraSettings.minSongSeconds = lengths[index]
        ExtraSettings.save(context)
        ExtraSettings.libraryRevision++
    }

    val blacklistPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                null
            }
            if (docId != null) {
                BlacklistStore.add(context, docId)
                ExtraSettings.libraryRevision++
            }
        }
    }
    Text(
        "Blacklisted folders",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    ExtraNote("Songs inside these folders are hidden, even if they are inside a folder you scan.")
    BlacklistStore.folders.forEach { docId ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                folderLabel(docId),
                Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = {
                    BlacklistStore.remove(context, docId)
                    ExtraSettings.libraryRevision++
                }
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove from blacklist")
            }
        }
    }
    OutlinedButton(
        onClick = { blacklistPicker.launch(null) },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Add a folder to the blacklist")
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Playback ----
    ExtraHeader("Playback")
    ExtraSwitchRow(
        title = "Pause when the volume reaches 0",
        subtitle = "Pauses the music when you turn the phone's volume down to zero, or the volume slider or mute button inside the app.",
        checked = ExtraSettings.pauseOnZeroVolume,
        onChange = {
            ExtraSettings.pauseOnZeroVolume = it
            ExtraSettings.save(context)
        },
    )
    ExtraSwitchRow(
        title = "Pause when headphones disconnect",
        subtitle = "Pauses when wired headphones are unplugged or Bluetooth headphones or speakers disconnect.",
        checked = ExtraSettings.pauseOnDisconnect,
        onChange = {
            ExtraSettings.pauseOnDisconnect = it
            ExtraSettings.save(context)
            ServiceHooks.apply()
        },
    )
    SleepTimerSetting()

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Equalizer ----
    EqualizerSettings()

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Artists ----
    ArtistSettings()

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Caching ----
    CachingSettings()

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // ---- Backup and restore ----
    BackupSettings()
}

// ---------------------------------------------------------------------------
// Equalizer
// ---------------------------------------------------------------------------
private fun formatHz(hz: Int): String =
    if (hz >= 1000) "%.1f kHz".format(hz / 1000f).replace(".0 ", " ") else "$hz Hz"

/** Changes one band, keeping the others as they are. Moving a slider by hand makes the preset "Custom". */
private fun setEqBand(band: Int, level: Int, info: EqInfo) {
    val updated = MutableList(info.bandCount) { ExtraSettings.eqLevels.getOrElse(it) { 0 } }
    updated[band] = level
    ExtraSettings.eqLevels = updated
    ExtraSettings.eqPreset = "Custom"
    ServiceHooks.applyEqualizer()
}

@Composable
private fun EqualizerSettings() {
    val context = LocalContext.current
    val info = ServiceHooks.eqInfo

    ExtraHeader("Equalizer")
    ExtraSwitchRow(
        title = "Use the equalizer",
        subtitle = null,
        checked = ExtraSettings.eqEnabled,
        onChange = {
            ExtraSettings.eqEnabled = it
            ExtraSettings.save(context)
            ServiceHooks.applyEqualizer()
        },
    )
    if (info == null) {
        ExtraNote(
            "The equalizer is set up once the music player starts. Play a song, then come back here " +
                "to shape the sound. If this message stays, your phone doesn't offer an equalizer."
        )
        return
    }

    Text(
        "Preset",
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EqPresets.names.forEach { name ->
            FilterChip(
                selected = ExtraSettings.eqPreset == name,
                onClick = {
                    ExtraSettings.eqLevels = EqPresets.levels(name, info)
                    ExtraSettings.eqPreset = name
                    ExtraSettings.save(context)
                    ServiceHooks.applyEqualizer()
                },
                label = { Text(name) },
            )
        }
    }

    Text(
        "Fine-tune each frequency",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    for (band in 0 until info.bandCount) {
        val level = ExtraSettings.eqLevels.getOrElse(band) { 0 }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatHz(info.centerHz[band]),
                Modifier.width(64.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = level.toFloat(),
                onValueChange = { setEqBand(band, it.roundToInt(), info) },
                onValueChangeFinished = { ExtraSettings.save(context) },
                valueRange = info.minLevel.toFloat()..info.maxLevel.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(
                "%+.0f dB".format(level / 100f),
                Modifier.width(56.dp),
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Artist pictures and bios
// ---------------------------------------------------------------------------
@Composable
private fun ArtistSettings() {
    val context = LocalContext.current
    ExtraHeader("Artists")
    ExtraSwitchRow(
        title = "Artist pictures and bios from the internet",
        subtitle = "Shows an artist's photo (from Deezer) and a short bio (from Wikipedia). " +
            "Artist names are sent to those services to look them up. Turn off to only use album art.",
        checked = ExtraSettings.artistInfoEnabled,
        onChange = {
            ExtraSettings.artistInfoEnabled = it
            ExtraSettings.save(context)
        },
    )
}

// ---------------------------------------------------------------------------
// Caching of album art and lyrics
// ---------------------------------------------------------------------------
@Composable
private fun CachingSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var imageBytes by remember { mutableLongStateOf(-1L) }
    var missCount by remember { mutableIntStateOf(0) }

    // Measure the cache whenever the screen opens or something changes.
    LaunchedEffect(refresh) {
        imageBytes = withContext(Dispatchers.IO) { ImageDiskCache.sizeBytes(context) }
        missCount = withContext(Dispatchers.IO) { LyricsMisses.count(context) }
    }

    ExtraHeader("Caching")

    ExtraSwitchRow(
        title = "Keep pictures on the phone",
        subtitle = "Saves small copies of album art, artist photos and bios, so they appear faster and still do after the app restarts.",
        checked = ExtraSettings.imageCacheEnabled,
        onChange = {
            ExtraSettings.imageCacheEnabled = it
            ExtraSettings.save(context)
        },
    )
    val limits = listOf(50, 100, 250, 500)
    OptionChips(
        "Picture cache limit",
        limits.map { "$it MB" },
        limits.indexOf(ExtraSettings.imageCacheMb).coerceAtLeast(0),
    ) { index ->
        ExtraSettings.imageCacheMb = limits[index]
        ExtraSettings.save(context)
        scope.launch {
            withContext(Dispatchers.IO) { ImageDiskCache.trim(context) }
            refresh++
        }
    }
    ExtraNote(
        if (imageBytes < 0) "Checking the cache size..."
        else "Saved pictures and bios are using %.1f MB right now.".format(imageBytes / 1_000_000.0)
    )
    OutlinedButton(
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { ImageDiskCache.clear(context) }
                ArtistInfo.clearMemory()
                refresh++
                Toast.makeText(context, "Picture cache cleared", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text("Clear the picture cache")
    }

    ExtraSwitchRow(
        title = "Remember lyrics for faster opening",
        subtitle = "Lyrics you download are always saved for good. This only keeps recently opened lyrics ready, " +
            "including songs that have none, so they open instantly.",
        checked = ExtraSettings.lyricsCacheEnabled,
        onChange = {
            ExtraSettings.lyricsCacheEnabled = it
            ExtraSettings.save(context)
            if (!it) LyricsCache.clear()
        },
    )
    ExtraSwitchRow(
        title = "Skip songs with no lyrics found recently",
        subtitle = "When downloading lyrics for all songs, songs that weren't found in the last 30 days are " +
            "skipped, so repeat runs are faster and gentler on the free lyrics service.",
        checked = ExtraSettings.lyricsMissCacheEnabled,
        onChange = {
            ExtraSettings.lyricsMissCacheEnabled = it
            ExtraSettings.save(context)
        },
    )
    ExtraNote("Songs on the not-found list: $missCount")
    OutlinedButton(
        onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { LyricsMisses.clear(context) }
                refresh++
                Toast.makeText(context, "Not-found list cleared", Toast.LENGTH_SHORT).show()
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text("Forget the not-found list")
    }
}

// ---------------------------------------------------------------------------
// Sleep timer
// ---------------------------------------------------------------------------
@Composable
private fun SleepTimerSetting() {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // While a timer runs, refresh the countdown every second.
    LaunchedEffect(SleepTimer.endsAtMs) {
        while (SleepTimer.endsAtMs > 0L) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    Text(
        "Sleep timer",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (SleepTimer.endsAtMs > 0L) {
        val secondsLeft = ((SleepTimer.endsAtMs - now) / 1000).coerceAtLeast(0)
        ExtraNote("The music will pause in %d:%02d.".format(secondsLeft / 60, secondsLeft % 60))
        OutlinedButton(
            onClick = { SleepTimer.cancel() },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text("Cancel the timer")
        }
    } else {
        ExtraNote("Pauses the music after the time you choose.")
    }
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(5, 15, 30, 45, 60, 90).forEach { minutes ->
            FilterChip(
                selected = false,
                onClick = {
                    SleepTimer.start(minutes)
                    Toast.makeText(context, "Music will pause in $minutes minutes", Toast.LENGTH_SHORT).show()
                },
                label = { Text("$minutes min") },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Backup and restore
// ---------------------------------------------------------------------------
@Composable
private fun BackupSettings() {
    val context = LocalContext.current
    var confirmRestore by remember { mutableStateOf(false) }
    var restoreDone by remember { mutableStateOf(false) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = PlaylistFiles.writeText(context, uri, AppBackup.create(context))
            Toast.makeText(
                context,
                if (ok) "Backup saved" else "Could not save the backup",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = PlaylistFiles.readText(context, uri)
            val error = if (text == null) "Could not read that file." else AppBackup.restore(context, text)
            if (error == null) {
                restoreDone = true
            } else {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    ExtraHeader("Backup and restore")
    ExtraNote(
        "Saves your settings, playlists, listening history and chosen folders into one file. " +
            "Your music files are not included."
    )
    OutlinedButton(
        onClick = {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            backupLauncher.launch("vibeplayer-backup-$day.json")
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text("Back up now")
    }
    OutlinedButton(
        onClick = { confirmRestore = true },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text("Restore from a backup")
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore from a backup?") },
            text = {
                Text(
                    "This replaces your current settings, playlists and listening history with " +
                        "the ones in the backup file. VibePlayer will close when it finishes, " +
                        "and you open it again afterwards."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = false
                        restoreLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text("Choose backup file")
                }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
        )
    }
    if (restoreDone) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Restore finished") },
            text = { Text("VibePlayer needs to close to load the restored data. Open it again afterwards.") },
            confirmButton = {
                TextButton(onClick = { exitProcess(0) }) { Text("Close VibePlayer") }
            },
        )
    }
}
