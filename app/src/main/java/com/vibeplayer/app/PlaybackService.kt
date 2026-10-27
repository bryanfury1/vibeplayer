package com.vibeplayer.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * This is the part of the app that actually plays music. It runs in the background,
 * so music keeps playing when you leave the app or turn the screen off.
 * Media3 automatically shows the notification and lock-screen controls for it.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // ---- Listening history: a song counts as "played" after 10 seconds of playing ----
    private val handler = Handler(Looper.getMainLooper())
    private var historyPlayer: ExoPlayer? = null
    private var listenedMs = 0L
    private var playRecorded = false

    private val historyTicker = object : Runnable {
        override fun run() {
            val player = historyPlayer
            if (player != null && player.isPlaying && !playRecorded) {
                listenedMs += 1000L
                if (listenedMs >= 10_000L) {
                    player.currentMediaItem?.mediaId?.let { PlayHistory.record(it) }
                    playRecorded = true
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlayHistory.load(applicationContext)

        // The same player as before, but the sound passes through the visualizer's
        // analyser on its way to the speakers (the music itself is not changed).
        val renderersFactory = object : DefaultRenderersFactory(this@PlaybackService) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf<AudioProcessor>(VisualizerProcessor()))
                    .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true, // pauses when a call or another app starts playing
            )
            .setHandleAudioBecomingNoisy(true) // pauses when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_LOCAL)    // keeps playing with the screen off
            .build()

        player.addListener(object : Player.Listener {
            // Like YouTube Music: when shuffle is switched on, the song that is playing
            // stays first and everything else is shuffled after it.
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) shuffleWithCurrentFirst(player)
            }

            // A new song (or the same song repeating) starts counting from zero.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listenedMs = 0L
                playRecorded = false
            }
        })

        historyPlayer = player
        ServiceHooks.attach(applicationContext, player)
        handler.postDelayed(historyTicker, 1000L)

        mediaSession = MediaSession.Builder(this, player).build()
    }

    /** Builds a random play order in which the current song comes first. */
    private fun shuffleWithCurrentFirst(player: ExoPlayer) {
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex
        val rest = (0 until count).filter { it != current }.shuffled()
        val order = (listOf(current) + rest).toIntArray()
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.nanoTime()))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // If you swipe the app away while nothing is playing, shut the service down.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(historyTicker)
        ServiceHooks.detach()
        historyPlayer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
