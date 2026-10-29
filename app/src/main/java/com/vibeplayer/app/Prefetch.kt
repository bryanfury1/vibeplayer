package com.vibeplayer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/** What the "Download everything now" job is doing. The Settings screen shows this. */
object PrefetchState {
    var running by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)

    /** For example "Artist photos". */
    var phase by mutableStateOf("")

    /** How the last run ended, for example "Finished". */
    var message by mutableStateOf("")
}

/**
 * Downloads and saves, in one go, everything the app would otherwise fetch bit by bit:
 * album pictures, artist photos, artist bios and (optionally) album descriptions.
 * It runs as a foreground service, so it keeps going when you leave the app, and shows a
 * progress bar in the notifications.
 */
class PrefetchService : Service() {
    companion object {
        const val ACTION_CANCEL = "com.vibeplayer.app.PREFETCH_CANCEL"
        private const val CHANNEL_ID = "offline_downloads"
        private const val NOTIFICATION_ID = 4711
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L
    private val doneCount = AtomicInteger(0)

    @Volatile
    private var stopReason: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            stopReason = "Cancelled."
            job?.cancel()
            if (job == null) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        createChannel()
        val firstNotification = buildNotification("Getting ready…", 0, 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, firstNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, firstNotification)
        }

        // Keep the processor awake for long downloads (it is released as soon as the job ends).
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VibePlayer:offline")
            wakeLock?.acquire(60L * 60L * 1000L)
        } catch (e: Exception) {
            // Not essential.
        }

        doneCount.set(0)
        PrefetchState.running = true
        PrefetchState.message = ""
        PrefetchState.done = 0
        PrefetchState.total = 0
        PrefetchState.phase = "Getting ready"

        job = scope.launch {
            var summary = ""
            try {
                summary = run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                summary = stopReason ?: "Cancelled."
            } catch (e: Exception) {
                summary = "Stopped because of an error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                finish(summary)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Nothing to do.
        }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // The work
    // -----------------------------------------------------------------------

    /** Runs the downloads and returns a sentence describing how it went. */
    private suspend fun run(): String {
        val library = LibraryIndex.source
        if (library.isEmpty()) return "Your music hasn't loaded yet. Open the app, wait for the scan, and try again."

        val app = applicationContext
        val artists = LibraryIndex.artistsFor(library)
        val albums = LibraryIndex.albumsFor(library)

        val notes = ArrayList<String>()

        // What is wanted, and what is possible right now.
        var wantArt = ExtraSettings.prefetchArt
        var wantPhotos = ExtraSettings.prefetchPhotos
        var wantBios = ExtraSettings.prefetchBios
        var wantAlbumBios = ExtraSettings.prefetchAlbumBios

        if (wantArt && (Build.VERSION.SDK_INT < 29 || !ExtraSettings.imageCacheEnabled)) {
            wantArt = false
            notes.add("Album pictures were skipped (turn on \"Keep pictures on the phone\" in Caching).")
        }
        val needsInternet = wantPhotos || wantBios || wantAlbumBios
        if (needsInternet && !internetAllowed()) {
            wantPhotos = false
            wantBios = false
            wantAlbumBios = false
            notes.add(
                if (ExtraSettings.prefetchWifiOnly) "Photos and bios need Wi-Fi, or turn off \"Only on Wi-Fi\"."
                else "Photos and bios need an internet connection."
            )
        }
        if (!wantArt && !wantPhotos && !wantBios && !wantAlbumBios) {
            return if (notes.isEmpty()) "Nothing is selected to download." else notes.joinToString(" ")
        }

        val sizes = listOf(192, 384)
        PrefetchState.total =
            (if (wantArt) albums.size else 0) +
                (if (wantPhotos) artists.size else 0) +
                (if (wantBios) artists.size else 0) +
                (if (wantAlbumBios) albums.size else 0)

        val limitBytes = ExtraSettings.imageCacheMb * 1_000_000L

        // 1. Album pictures (saved from your own files, so no internet needed)
        if (wantArt) {
            runPhase("Album pictures", albums, 4) { album ->
                val track = album.tracks.firstOrNull()
                if (track != null) for (size in sizes) ImageDiskCache.ensureSaved(app, track, size)
            }
        }
        // 2. Artist photos (three sizes each: lists, Home, and the big picture on the artist's page)
        if (wantPhotos && stopReason == null) {
            runPhase("Artist photos", artists, 3) { artist ->
                for (size in listOf(192, 384, 1000)) ArtistInfo.prefetchPicture(app, artist.title, size)
            }
        }
        // 3. Artist bios
        if (wantBios && stopReason == null) {
            runPhase("Artist bios", artists, 3) { artist -> ArtistInfo.loadBio(app, artist.title) }
        }
        // 4. Album descriptions
        if (wantAlbumBios && stopReason == null) {
            runPhase("Album descriptions", albums, 3) { album ->
                val artist = album.subtitle.substringBefore(" • ")
                ArtistInfo.loadAlbumBio(app, album.title, artist)
            }
        }

        val used = ImageDiskCache.sizeBytes(app) / 1_000_000.0
        val sentence = when {
            stopReason != null -> stopReason!!
            else -> "Finished. Everything is saved on your phone (${"%.0f".format(used)} MB used)."
        }
        return (listOf(sentence) + notes).joinToString(" ")
    }

    /** Does [work] for every item, a few at a time, keeping the progress and the notification up to date. */
    private suspend fun <T> runPhase(name: String, items: List<T>, parallel: Int, work: suspend (T) -> Unit) {
        PrefetchState.phase = name
        updateNotification(name)
        val gate = Semaphore(parallel)
        val limitBytes = ExtraSettings.imageCacheMb * 1_000_000L
        coroutineScope {
            items.map { item ->
                async {
                    gate.withPermit {
                        ensureActive()
                        if (stopReason == null) {
                            work(item)
                            val finished = doneCount.incrementAndGet()
                            PrefetchState.done = finished
                            // Every 25 items: stop politely if the cache is nearly full, and refresh the bar.
                            if (finished % 25 == 0) {
                                if (ImageDiskCache.sizeBytes(applicationContext) > limitBytes * 95 / 100) {
                                    stopReason = "Stopped: the cache is full. Raise the limit in Settings, under Caching."
                                }
                            }
                            updateNotification(name)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun internetAllowed(): Boolean {
        return try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
            val onWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            onWifi || !ExtraSettings.prefetchWifiOnly
        } catch (e: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // The notification
    // -----------------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Progress while VibePlayer saves pictures and bios for offline use"
        manager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildNotification(text: String, done: Int, total: Int, indeterminate: Boolean): android.app.Notification {
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, PrefetchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Saving pictures and bios for offline use")
            .setContentText(text)
            .setProgress(total, done, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    /** Refreshes the progress bar, at most about twice a second (Android ignores faster updates). */
    private fun updateNotification(phase: String) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 500) return
        lastNotifyAt = now
        val total = PrefetchState.total
        val done = PrefetchState.done
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification("$phase: $done of $total", done, total, indeterminate = false),
            )
        } catch (e: SecurityException) {
            // Notifications are switched off for the app. The download carries on without the bar.
        }
    }

    private fun finish(summary: String) {
        PrefetchState.running = false
        PrefetchState.message = summary
        try {
            // Leave a final message in the notifications, without the progress bar.
            stopForeground(STOP_FOREGROUND_DETACH)
            val last = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Offline download")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openAppIntent())
                .build()
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, last)
        } catch (e: Exception) {
            // Nothing to do.
        }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // Nothing to do.
        }
        stopSelf()
    }
}
