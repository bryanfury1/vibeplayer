package com.vibeplayer.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Theme: Material 3 colors. On Android 12+ the colors follow your wallpaper.
// ---------------------------------------------------------------------------
@Composable
fun VibeTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    VibeThemeImpl(content)
}

// ---------------------------------------------------------------------------
// Permissions
// ---------------------------------------------------------------------------
private fun audioPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(audioPermission(), Manifest.permission.POST_NOTIFICATIONS)
    else arrayOf(audioPermission())

private fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------------------
// The whole app screen
// ---------------------------------------------------------------------------
@Composable
fun VibeApp(player: PlayerController) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasAudioPermission(context)) }
    var loading by remember { mutableStateOf(false) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyricsFull by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAmbient by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Keep the screen on while music plays, so the idle screen can appear like an always-on display.
    val view = LocalView.current
    val keepScreenOn = (AppSettings.idleEnabled && player.isPlaying) || showAmbient
    SideEffect { view.keepScreenOn = keepScreenOn }

    // Show the idle screen once nothing has been touched for the chosen time.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val quietFor = System.currentTimeMillis() - lastInteraction
            if (AppSettings.idleEnabled && !showAmbient &&
                quietFor >= AppSettings.idleSeconds * 1000L &&
                player.currentTrack != null &&
                (!AppSettings.idleOnlyWhilePlaying || player.isPlaying)
            ) {
                showAmbient = true
            }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    var openGroup by remember { mutableStateOf<TrackGroup?>(null) }
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf(FolderStore.load(context)) }
    var showFolders by remember { mutableStateOf(false) }

    // Android's own folder picker. We only remember which folder you chose.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val docId = try {
                DocumentsContract.getTreeDocumentId(uri)
            } catch (e: Exception) {
                null
            }
            if (docId != null && docId !in folders) {
                folders = folders + docId
                FolderStore.save(context, folders)
                player.clearQueue() // the song list is about to change, so stop and empty the queue
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = hasAudioPermission(context)
    }

    // Ask for permission when the app opens.
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(requiredPermissions())
    }

    // Scan the chosen folders whenever we get permission or the folder list changes.
    LaunchedEffect(hasPermission, folders, LibraryEvents.version, ExtraSettings.libraryRevision) {
        if (hasPermission) {
            loading = true
            player.updateLibrary(applyLibraryFilters(LibraryRepository.load(context, folders)))
            loading = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        lastInteraction = System.currentTimeMillis()
                    }
                }
            }
    ) {
        val tabs = listOf(
            "Home" to Icons.Rounded.Home,
            "Songs" to Icons.Rounded.LibraryMusic,
            "Artists" to Icons.Rounded.Person,
            "Albums" to Icons.Rounded.Album,
            "Genres" to Icons.Rounded.Category,
            "Playlists" to Icons.Rounded.PlaylistPlay,
        )
        Scaffold(
            bottomBar = {
                Column {
                    if (player.currentTrack != null) {
                        MiniPlayer(player, onOpen = { showNowPlaying = true })
                    }
                    NavigationBar {
                        tabs.forEachIndexed { index, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = {
                                    tab = index
                                    openGroup = null
                                    openPlaylistId = null
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val group = openGroup
            if (group != null) {
                GroupScreen(group, player, padding, onBack = { openGroup = null })
            } else when (tab) {
                0 -> HomeScreen(player, padding, onOpenSettings = { showSettings = true }) { openGroup = it }
                1 -> LibraryScreen(
                    player = player,
                    hasPermission = hasPermission,
                    hasFolders = folders.isNotEmpty(),
                    loading = loading,
                    padding = padding,
                    onRequestPermission = { permissionLauncher.launch(requiredPermissions()) },
                    onAddFolder = { folderPicker.launch(null) },
                    onManageFolders = { showFolders = true },
                    onOpenSettings = { showSettings = true },
                )
                2 -> {
                    val artists = remember(player.library) { artistGroups(player.library) }
                    GroupListScreen("Artists", artists, padding, roundArt = true) { openGroup = it }
                }
                3 -> {
                    val albums = remember(player.library) { albumGroups(player.library) }
                    GroupListScreen("Albums", albums, padding) { openGroup = it }
                }
                4 -> {
                    val genres = remember(player.library) { genreGroups(player.library) }
                    GroupListScreen("Genres", genres, padding) { openGroup = it }
                }
                else -> {
                    val playlistId = openPlaylistId
                    if (playlistId != null) {
                        PlaylistDetailScreen(playlistId, player, padding, onBack = { openPlaylistId = null })
                    } else {
                        PlaylistsScreen(player, padding, onOpen = { openPlaylistId = it })
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            SettingsScreen(
                library = player.library,
                onClose = { showSettings = false },
                onManageFolders = { showFolders = true },
                onPreviewIdle = {
                    showSettings = false
                    showAmbient = true
                },
            )
        }

        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            NowPlayingScreen(
                player,
                onClose = { showNowPlaying = false },
                onOpenQueue = { showQueue = true },
                onOpenLyricsFullscreen = { showLyricsFull = true },
                onOpenAmbient = { showAmbient = true },
            )
        }

        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            QueueScreen(player, onClose = { showQueue = false })
        }

        AnimatedVisibility(
            visible = showLyricsFull,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            LyricsFullScreen(player, onClose = { showLyricsFull = false })
        }

        AnimatedVisibility(visible = showAmbient, enter = fadeIn(), exit = fadeOut()) {
            AmbientScreen(player, onDismiss = { showAmbient = false })
        }
    }

    TagEditorState.track?.let { editing ->
        EditTagsDialog(editing, onDismiss = { TagEditorState.track = null })
    }

    PlaylistPicker.track?.let { picking ->
        AddToPlaylistDialog(picking, onDismiss = { PlaylistPicker.track = null })
    }

    if (showFolders) {
        FoldersDialog(
            folders = folders,
            onAdd = { folderPicker.launch(null) },
            onRemove = { docId ->
                folders = folders - docId
                FolderStore.save(context, folders)
                player.clearQueue()
            },
            onDismiss = { showFolders = false },
        )
    }

    // Back closes an open artist/album/genre page.
    BackHandler(enabled = openGroup != null) { openGroup = null }
    BackHandler(enabled = tab == 5 && openPlaylistId != null) { openPlaylistId = null }
    // The phone's Back button closes the Now Playing screen first.
    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
    // Declared after the one above, so Back closes the Queue first.
    BackHandler(enabled = showQueue) { showQueue = false }
    // Declared last, so Back closes full-screen lyrics first.
    BackHandler(enabled = showLyricsFull) { showLyricsFull = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    // Declared last, so Back closes the idle screen first.
    BackHandler(enabled = showAmbient) { showAmbient = false }
}

// ---------------------------------------------------------------------------
// Library list
// ---------------------------------------------------------------------------
@Composable
private fun LibraryScreen(
    player: PlayerController,
    hasPermission: Boolean,
    hasFolders: Boolean,
    loading: Boolean,
    padding: PaddingValues,
    onRequestPermission: () -> Unit,
    onAddFolder: () -> Unit,
    onManageFolders: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "VibePlayer",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onManageFolders) {
                    Icon(Icons.Rounded.Folder, contentDescription = "Choose folders to scan")
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                }
            }
        }
        when {
            !hasPermission -> item {
                Column(Modifier.padding(16.dp)) {
                    Text("VibePlayer needs permission to read the music on your phone.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRequestPermission) { Text("Allow access") }
                }
            }
            !hasFolders -> item {
                Column(Modifier.padding(16.dp)) {
                    Text("Choose which folders VibePlayer is allowed to scan for music.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAddFolder) { Text("Choose a folder") }
                }
            }
            loading -> item {
                Text("Scanning your music…", Modifier.padding(16.dp))
            }
            player.library.isEmpty() -> item {
                Text("No songs found in the chosen folders.", Modifier.padding(16.dp))
            }
            else -> itemsIndexed(player.library, key = { _, track -> track.uri.toString() }) { index, track ->
                TrackRow(
                    track,
                    isCurrent = track.uri == player.currentTrack?.uri,
                    onClick = { player.playTrackAt(index) },
                    onPlayNext = { player.playNext(track) },
                    onAddToQueue = { player.addToQueue(track) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pop-up listing the folders being scanned
// ---------------------------------------------------------------------------
@Composable
private fun FoldersDialog(
    folders: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folders to scan") },
        text = {
            Column {
                if (folders.isEmpty()) Text("No folders chosen yet.")
                folders.forEach { docId ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folderLabel(docId),
                            Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onRemove(docId) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove folder")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text("Add folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, sizePx = 128, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${track.artist} • ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = {
                        menuOpen = false
                        onPlayNext()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = {
                        menuOpen = false
                        onAddToQueue()
                    },
                )
                DropdownMenuItem(
                    text = { Text(if (PlaylistStore.isFavorite(track)) "Remove from favorites" else "Add to favorites") },
                    onClick = {
                        menuOpen = false
                        PlaylistStore.toggleFavorite(track)
                    },
                )
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
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Queue screen: songs already played, the current song, and what is coming up
// ---------------------------------------------------------------------------
@Composable
private fun QueueScreen(player: PlayerController, onClose: () -> Unit) {
    val queue = player.queue
    // Open with the song just before the current one peeking in at the top,
    // so you can see that played songs are still there, one swipe up.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (player.queuePosition - 1).coerceAtLeast(0)
    )

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close")
                }
                Text(
                    "Queue",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.size(48.dp))
            }

            if (queue.isEmpty()) {
                Text("The queue is empty.", Modifier.padding(16.dp))
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                    itemsIndexed(queue, key = { _, entry -> entry.windowIndex }) { position, entry ->
                        QueueRow(
                            entry = entry,
                            isCurrent = position == player.queuePosition,
                            isPlayed = position < player.queuePosition,
                            onClick = { player.jumpToQueueItem(entry.windowIndex) },
                            onRemove = { player.removeFromQueue(entry.windowIndex) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    isPlayed: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val track = entry.track
    val background =
        if (isCurrent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track, sizePx = 128, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                // Songs already played look dimmed.
                color = if (isPlayed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove from queue")
        }
    }
}

// ---------------------------------------------------------------------------
// Album art picture (or a music-note placeholder)
// ---------------------------------------------------------------------------
@Composable
fun Artwork(track: Track?, sizePx: Int, modifier: Modifier, corner: Dp = 8.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, track?.uri) {
        value = if (track == null) null else ArtworkLoader.load(context, track, sizePx)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val picture = bitmap
        if (picture != null) {
            Image(
                bitmap = picture.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mini player (bar at the bottom): previous, play/pause, next
// ---------------------------------------------------------------------------
@Composable
private fun MiniPlayer(player: PlayerController, onOpen: () -> Unit) {
    val track = player.currentTrack ?: return
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, sizePx = 128, modifier = Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = player::previous) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = player::togglePlayPause) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = player::next) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Full-screen Now Playing
// ---------------------------------------------------------------------------
@Composable
private fun NowPlayingScreen(
    player: PlayerController,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyricsFullscreen: () -> Unit,
    onOpenAmbient: () -> Unit,
) {
    val context = LocalContext.current
    val track = player.currentTrack

    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var formatText by remember { mutableStateOf("") }
    var showLyrics by remember { mutableStateOf(false) }

    // Update the slider position 4 times a second.
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) position = player.position()
            delay(250)
        }
    }
    // Work out the "FLAC (96kHz/24bit)" text whenever the song changes.
    LaunchedEffect(track?.id) {
        formatText = if (track == null) "" else AudioInfo.describe(context, track)
    }

    val duration = (if (player.durationMs > 0) player.durationMs else (track?.durationMs ?: 0L))
        .coerceAtLeast(1L)
    val shownPosition = if (dragging) (dragFraction * duration).toLong() else position
    val sliderValue = if (dragging) dragFraction else (position.toFloat() / duration).coerceIn(0f, 1f)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top bar: close arrow and title
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close")
                }
                Text(
                    "Now Playing",
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onOpenAmbient) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = "Visualizer")
                }
                IconButton(onClick = { showLyrics = !showLyrics }) {
                    Icon(
                        Icons.Rounded.Lyrics,
                        contentDescription = "Lyrics",
                        tint = if (showLyrics) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Rounded.QueueMusic, contentDescription = "Open queue")
                }
            }

            // Album art takes whatever space is left in the middle.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (showLyrics) {
                    LyricsContent(
                        player = player,
                        track = track,
                        modifier = Modifier.fillMaxSize(),
                        fullscreenIcon = Icons.Rounded.Fullscreen,
                        onToggleFullscreen = onOpenLyricsFullscreen,
                        big = false,
                    )
                } else {
                    Artwork(track, sizePx = 1024, modifier = Modifier.aspectRatio(1f), corner = 24.dp)
                }
            }

            // Title and artist, with an edit button
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    track?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track?.artist ?: "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { if (track != null) PlaylistStore.toggleFavorite(track) }) {
                val favorite = track != null && PlaylistStore.isFavorite(track)
                Icon(
                    if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { if (track != null) PlaylistPicker.track = track }) {
                Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Add to playlist")
            }
            IconButton(onClick = { if (track != null) TagEditorState.track = track }) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit info")
            }
            }

            // Seek slider
            Slider(
                value = sliderValue,
                onValueChange = {
                    dragging = true
                    dragFraction = it
                },
                onValueChangeFinished = {
                    val target = (dragFraction * duration).toLong()
                    player.seekTo(target)
                    position = target
                    dragging = false
                },
            )

            // Elapsed time | format badge (centered) | total time
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(shownPosition), style = MaterialTheme.typography.labelMedium)
                Text(
                    formatText,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }

            Spacer(Modifier.height(16.dp))

            // Transport controls: shuffle, previous, play/pause, next, repeat
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (player.shuffleOn) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = player::previous, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = player::togglePlayPause, Modifier.size(72.dp)) {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (player.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = player::next, Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next", Modifier.size(36.dp))
                }
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

            // Volume: mute button and slider
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::toggleMute) {
                    Icon(
                        if (player.muted || player.volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                        contentDescription = "Mute",
                    )
                }
                Slider(
                    value = if (player.muted) 0f else player.volume,
                    onValueChange = player::changeVolume,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
