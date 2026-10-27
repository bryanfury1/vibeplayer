package com.vibeplayer.app

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** One row of the queue: which slot it occupies in the player, and which song it is. */
data class QueueEntry(val windowIndex: Int, val track: Track)

/**
 * The "remote control" the screens use. It talks to PlaybackService (the part that
 * really plays music) and keeps simple values like isPlaying and currentTrack up to
 * date, so the screens redraw automatically when something changes.
 */
class PlayerController(private val context: Context) {

    private var controller: MediaController? = null

    // Remembers which list of songs was last loaded into the player as the queue.
    private var queueSource: List<Track>? = null
    // True once the queue has been edited (play next, add to queue, remove).
    private var queueModified = false
    // Lets us turn a song's address back into a Track quickly.
    private var trackByKey: Map<String, Track> = emptyMap()
    // The address each queued song was loaded with, so a changed address can be refreshed.
    private val loadedUris = HashMap<String, Uri>()

    // ---- Values the screens read ----
    var library by mutableStateOf<List<Track>>(emptyList())
        private set
    var currentTrack by mutableStateOf<Track?>(null)
        private set
    /** The queue in the order it will play (follows shuffle). Songs already played come first. */
    var queue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    /** Where the current song sits inside [queue]. Everything before it has been played. */
    var queuePosition by mutableIntStateOf(-1)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffleOn by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var volume by mutableFloatStateOf(1f)
        private set
    var muted by mutableStateOf(false)
        private set

    /** Called after scanning folders. Replaces the list of songs the app knows about. */
    fun updateLibrary(tracks: List<Track>) {
        library = tracks
        trackByKey = tracks.associateBy { it.key }
        // Older history entries were saved by Android's song ID; switch them to the file path.
        PlayHistory.migrate(tracks.associate { it.uri.toString() to it.key })
        controller?.let {
            refreshQueueAddresses(it)
            sync(it, rebuildQueue = true)
        }
    }

    /**
     * If a queued song's file was edited, Android may have given it a new address.
     * Swap the new address in so it still plays. The song playing right now is left alone.
     */
    private fun refreshQueueAddresses(c: MediaController) {
        val playingIndex = c.currentMediaItemIndex
        for (i in 0 until c.mediaItemCount) {
            if (i == playingIndex) continue
            val key = c.getMediaItemAt(i).mediaId
            val track = trackByKey[key] ?: continue
            val known = loadedUris[key] ?: continue
            if (known != track.uri) c.replaceMediaItem(i, track.toMediaItem())
        }
    }

    // ---- Connecting to the service ----
    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    val c = future.get()
                    controller = c
                    c.addListener(listener)
                    applyVolume()
                    sync(c, rebuildQueue = true)
                } catch (e: Exception) {
                    // Could not connect; the buttons simply do nothing.
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val rebuild = events.containsAny(
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
            )
            sync(player, rebuild)
        }
    }

    private fun sync(p: Player, rebuildQueue: Boolean) {
        isPlaying = p.isPlaying
        shuffleOn = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration
        currentTrack = p.currentMediaItem?.let { trackByKey[it.mediaId] }
        if (rebuildQueue) rebuildQueueList(p)
        queuePosition =
            if (p.mediaItemCount == 0) -1
            else queue.indexOfFirst { it.windowIndex == p.currentMediaItemIndex }
    }

    /** Walks through the player's playlist in play order (shuffled if shuffle is on). */
    private fun rebuildQueueList(p: Player) {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) {
            queue = emptyList()
            return
        }
        val shuffle = p.shuffleModeEnabled
        val window = Timeline.Window()
        val entries = ArrayList<QueueEntry>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(shuffle)
        var steps = 0
        while (index != C.INDEX_UNSET && steps < timeline.windowCount) {
            val mediaId = timeline.getWindow(index, window).mediaItem.mediaId
            val track = trackByKey[mediaId]
            if (track != null) entries.add(QueueEntry(index, track))
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
            steps++
        }
        queue = entries
    }

    // ---- Buttons ----

    /** Plays the song at this position in the library. The whole library becomes the queue. */
    fun playTrackAt(index: Int) {
        playList(library, index)
    }

    /**
     * Plays a list of songs (an artist, an album, a genre...) starting at one of them.
     * The whole list becomes the queue, like in YouTube Music.
     * shuffle: null keeps the current shuffle setting, true forces shuffle on, false forces it off.
     */
    fun playList(tracks: List<Track>, startIndex: Int, shuffle: Boolean? = null) {
        val c = controller ?: return
        if (startIndex !in tracks.indices) return
        val wantShuffle = shuffle ?: c.shuffleModeEnabled
        // Turning shuffle off here and back on at the end makes the player build a fresh
        // shuffled order (PlaybackService puts the playing song first).
        if (c.shuffleModeEnabled) c.shuffleModeEnabled = false
        // (Re)load the queue if it isn't the untouched list we loaded last time.
        if (queueSource !== tracks || queueModified || c.mediaItemCount != tracks.size) {
            c.setMediaItems(tracks.map { it.toMediaItem() })
            queueSource = tracks
            queueModified = false
        }
        c.seekTo(startIndex, 0L)
        c.prepare()
        c.play()
        if (wantShuffle) c.shuffleModeEnabled = true
    }

    /** Stops playback and empties the queue. Used when the list of scanned folders changes. */
    fun clearQueue() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        queueSource = null
        queueModified = false
    }

    // ---- Queue editing ----

    /** Jumps to a song in the queue. Songs already played stay in the queue above it. */
    fun jumpToQueueItem(windowIndex: Int) {
        val c = controller ?: return
        if (windowIndex !in 0 until c.mediaItemCount) return
        c.seekTo(windowIndex, 0L)
        c.prepare()
        c.play()
    }

    fun removeFromQueue(windowIndex: Int) {
        val c = controller ?: return
        if (windowIndex !in 0 until c.mediaItemCount) return
        c.removeMediaItem(windowIndex)
        queueModified = true
    }

    /** Puts a song right after the current one. If nothing is playing, plays it now. */
    fun playNext(track: Track) {
        val c = controller ?: return
        queueModified = true
        if (c.mediaItemCount == 0) {
            c.setMediaItem(track.toMediaItem())
            c.prepare()
            c.play()
        } else {
            c.addMediaItem(c.currentMediaItemIndex + 1, track.toMediaItem())
        }
    }

    /** Puts a song at the very end of the queue. If nothing is playing, plays it now. */
    fun addToQueue(track: Track) {
        val c = controller ?: return
        queueModified = true
        if (c.mediaItemCount == 0) {
            c.setMediaItem(track.toMediaItem())
            c.prepare()
            c.play()
        } else {
            c.addMediaItem(track.toMediaItem())
        }
    }

    // ---- Transport ----

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playTrackAt(0)
        } else if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0L)
            c.play()
        }
    }

    fun next() {
        controller?.seekToNext()
    }

    /** Like most players: restarts the song if you are a few seconds in, otherwise goes back one. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun position(): Long = controller?.currentPosition ?: 0L

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Cycles: repeat off, then repeat all, then repeat one, then off again. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun changeVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        muted = false
        applyVolume()
    }

    fun toggleMute() {
        muted = !muted
        applyVolume()
    }

    private fun applyVolume() {
        controller?.volume = if (muted) 0f else volume
        if (ExtraSettings.pauseOnZeroVolume && (muted || volume == 0f)) controller?.pause()
    }

    private fun Track.toMediaItem(): MediaItem {
        loadedUris[key] = uri
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()
    }
}
