package com.vibeplayer.app

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * The newer settings (appearance, Home sections, library filters, playback rules).
 * They are saved on the phone and applied immediately, just like AppSettings.
 */
object ExtraSettings {
    private const val PREFS = "vibeplayer_extra"

    // ---- Appearance ----
    /** 0 = follow the phone's setting, 1 = always dark, 2 = always light. */
    var themeMode by mutableIntStateOf(1)
    /** Use the colors of your wallpaper (Android 12 and newer). */
    var useDynamicColor by mutableStateOf(true)
    /** Which color theme to use when wallpaper colors are off (a position in colorThemeNames). */
    var colorTheme by mutableIntStateOf(0)
    var amoledBlack by mutableStateOf(false)

    // ---- Home screen sections ----
    var homeRecentSongs by mutableStateOf(true)
    var homeRecentArtists by mutableStateOf(true)
    var homeRecentAlbums by mutableStateOf(true)
    var homeAddedSongs by mutableStateOf(true)
    var homeAddedAlbums by mutableStateOf(true)
    var homeMostPlayed by mutableStateOf(true)

    // ---- Library ----
    /** Songs shorter than this many seconds are hidden. 0 means show everything. */
    var minSongSeconds by mutableIntStateOf(0)
    /** Goes up whenever a library filter changes, which makes the app scan again. */
    var libraryRevision by mutableIntStateOf(0)

    // ---- Artists ----
    /** Fetch artist pictures and short bios from the internet. */
    var artistInfoEnabled by mutableStateOf(true)

    // ---- Equalizer ----
    var eqEnabled by mutableStateOf(false)
    /** The level of each frequency band, in hundredths of a decibel (300 = +3 dB). */
    var eqLevels by mutableStateOf<List<Int>>(emptyList())
    /** The name of the preset in use, or "Custom" once a slider has been moved. */
    var eqPreset by mutableStateOf("Flat")

    // ---- Caching ----
    var imageCacheEnabled by mutableStateOf(true)
    /** The most disk space the saved album art may use, in megabytes. */
    var imageCacheMb by mutableIntStateOf(100)
    var lyricsCacheEnabled by mutableStateOf(true)
    var lyricsMissCacheEnabled by mutableStateOf(true)

    // ---- Playback ----
    var pauseOnZeroVolume by mutableStateOf(false)
    var pauseOnDisconnect by mutableStateOf(true)

    fun load(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        themeMode = prefs.getInt("theme_mode", 1)
        useDynamicColor = prefs.getBoolean("use_dynamic_color", true)
        colorTheme = prefs.getInt("color_theme", 0)
        amoledBlack = prefs.getBoolean("amoled_black", false)
        homeRecentSongs = prefs.getBoolean("home_recent_songs", true)
        homeRecentArtists = prefs.getBoolean("home_recent_artists", true)
        homeRecentAlbums = prefs.getBoolean("home_recent_albums", true)
        homeAddedSongs = prefs.getBoolean("home_added_songs", true)
        homeAddedAlbums = prefs.getBoolean("home_added_albums", true)
        homeMostPlayed = prefs.getBoolean("home_most_played", true)
        minSongSeconds = prefs.getInt("min_song_seconds", 0)
        pauseOnZeroVolume = prefs.getBoolean("pause_on_zero_volume", false)
        pauseOnDisconnect = prefs.getBoolean("pause_on_disconnect", true)
        artistInfoEnabled = prefs.getBoolean("artist_info_enabled", true)
        eqEnabled = prefs.getBoolean("eq_enabled", false)
        eqLevels = prefs.getString("eq_levels", "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
        eqPreset = prefs.getString("eq_preset", "Flat") ?: "Flat"
        imageCacheEnabled = prefs.getBoolean("image_cache_enabled", true)
        imageCacheMb = prefs.getInt("image_cache_mb", 100)
        lyricsCacheEnabled = prefs.getBoolean("lyrics_cache_enabled", true)
        lyricsMissCacheEnabled = prefs.getBoolean("lyrics_miss_cache_enabled", true)
    }

    fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt("theme_mode", themeMode)
            .putBoolean("use_dynamic_color", useDynamicColor)
            .putInt("color_theme", colorTheme)
            .putBoolean("amoled_black", amoledBlack)
            .putBoolean("home_recent_songs", homeRecentSongs)
            .putBoolean("home_recent_artists", homeRecentArtists)
            .putBoolean("home_recent_albums", homeRecentAlbums)
            .putBoolean("home_added_songs", homeAddedSongs)
            .putBoolean("home_added_albums", homeAddedAlbums)
            .putBoolean("home_most_played", homeMostPlayed)
            .putInt("min_song_seconds", minSongSeconds)
            .putBoolean("pause_on_zero_volume", pauseOnZeroVolume)
            .putBoolean("pause_on_disconnect", pauseOnDisconnect)
            .putBoolean("artist_info_enabled", artistInfoEnabled)
            .putBoolean("eq_enabled", eqEnabled)
            .putString("eq_levels", eqLevels.joinToString(","))
            .putString("eq_preset", eqPreset)
            .putBoolean("image_cache_enabled", imageCacheEnabled)
            .putInt("image_cache_mb", imageCacheMb)
            .putBoolean("lyrics_cache_enabled", lyricsCacheEnabled)
            .putBoolean("lyrics_miss_cache_enabled", lyricsMissCacheEnabled)
            .apply()
    }
}

// ---------------------------------------------------------------------------
// Folder blacklist and library filters
// ---------------------------------------------------------------------------

/**
 * Folders whose songs are hidden, even if they sit inside a folder you scan.
 * Saved the same way as the scan folders (Android's own folder ID, e.g. "primary:Music/Podcasts").
 */
object BlacklistStore {
    private const val PREFS = "vibeplayer_extra"
    private const val KEY = "blacklist_folders"

    var folders by mutableStateOf<List<String>>(emptyList())
        private set

    fun load(context: Context) {
        folders = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .sorted()
    }

    private fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, folders.toSet())
            .apply()
    }

    fun add(context: Context, docId: String) {
        if (docId in folders) return
        folders = (folders + docId).sorted()
        save(context)
    }

    fun remove(context: Context, docId: String) {
        folders = folders - docId
        save(context)
    }
}

/** Turns a folder ID like "primary:Music/Podcasts" into the start of a file path. */
private fun folderPathPrefix(docId: String): String {
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "").trim('/')
    val root = if (volume == "primary") "/storage/emulated/0/" else "/storage/$volume/"
    return if (path.isEmpty()) root else "$root$path/"
}

/** Removes songs that are too short or that live in a blacklisted folder. */
fun applyLibraryFilters(tracks: List<Track>): List<Track> {
    // The library is reloaded after tags are edited, so lyrics remembered earlier may be out of date.
    LyricsCache.clear()
    val minMs = ExtraSettings.minSongSeconds * 1000L
    val blocked = BlacklistStore.folders.map { folderPathPrefix(it) }
    if (minMs <= 0L && blocked.isEmpty()) return tracks
    return tracks.filter { track ->
        (minMs <= 0L || track.durationMs >= minMs) &&
            blocked.none { prefix -> track.path.startsWith(prefix, ignoreCase = true) }
    }
}

// ---------------------------------------------------------------------------
// Playback rules and the sleep timer. These talk to the music player inside PlaybackService.
// ---------------------------------------------------------------------------

/** What the phone's equalizer can do: how many frequency bands, and how far each can be raised or lowered. */
class EqInfo(
    val bandCount: Int,
    /** The lowest level a band can be set to, in hundredths of a decibel. */
    val minLevel: Int,
    /** The highest level a band can be set to, in hundredths of a decibel. */
    val maxLevel: Int,
    /** The middle frequency of each band, in hertz. */
    val centerHz: List<Int>,
)

/** A small bridge to the player that lives in PlaybackService. */
object ServiceHooks {
    private var player: ExoPlayer? = null
    private var audioManager: AudioManager? = null
    private var listener: Player.Listener? = null
    private var equalizer: Equalizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastVolume = -1

    /** Set once the music service is running and the phone's equalizer is ready. Null before that. */
    var eqInfo by mutableStateOf<EqInfo?>(null)
        private set

    /**
     * Looks at the phone's own music volume (the one the volume buttons change) twice a second.
     * If it drops to 0 while music is playing, the music is paused.
     */
    private val volumeWatcher = object : Runnable {
        override fun run() {
            val currentPlayer = player
            val manager = audioManager
            if (currentPlayer != null && manager != null) {
                val now = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (ExtraSettings.pauseOnZeroVolume && currentPlayer.isPlaying && lastVolume > 0 && now == 0) {
                    currentPlayer.pause()
                }
                lastVolume = now
                handler.postDelayed(this, 500L)
            }
        }
    }

    /** Called by PlaybackService when it starts. */
    fun attach(context: Context, newPlayer: ExoPlayer) {
        ExtraSettings.load(context)
        detach()
        player = newPlayer
        val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager
        lastVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        newPlayer.setHandleAudioBecomingNoisy(ExtraSettings.pauseOnDisconnect)
        handler.postDelayed(volumeWatcher, 500L)

        // The equalizer is attached to the player's audio stream. If that stream changes, set it up again.
        val newListener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupEqualizer(audioSessionId)
            }
        }
        newPlayer.addListener(newListener)
        listener = newListener
        setupEqualizer(newPlayer.audioSessionId)
    }

    /** Called by PlaybackService when it stops. */
    fun detach() {
        SleepTimer.cancel()
        handler.removeCallbacks(volumeWatcher)
        val old = player
        val oldListener = listener
        if (old != null && oldListener != null) old.removeListener(oldListener)
        releaseEqualizer()
        player = null
        listener = null
        audioManager = null
    }

    /** Applies the settings that can change while music is playing. */
    fun apply() {
        player?.setHandleAudioBecomingNoisy(ExtraSettings.pauseOnDisconnect)
    }

    fun pause() {
        player?.pause()
    }

    // ---- Equalizer ----

    private fun setupEqualizer(sessionId: Int) {
        releaseEqualizer()
        if (sessionId == 0) return
        try {
            val newEqualizer = Equalizer(0, sessionId)
            val bands = newEqualizer.getNumberOfBands().toInt()
            val range = newEqualizer.getBandLevelRange()
            val centers = ArrayList<Int>()
            for (band in 0 until bands) {
                // Android reports milli-hertz, so divide by 1000 to get hertz.
                centers.add(newEqualizer.getCenterFreq(band.toShort()) / 1000)
            }
            equalizer = newEqualizer
            eqInfo = EqInfo(bands, range[0].toInt(), range[1].toInt(), centers)
            applyEqualizer()
        } catch (e: Exception) {
            // This phone doesn't offer an equalizer for this audio stream.
            equalizer = null
            eqInfo = null
        }
    }

    private fun releaseEqualizer() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            // Nothing to do.
        }
        equalizer = null
        eqInfo = null
    }

    /** Sends the saved equalizer settings (on or off, and each band's level) to the phone. */
    fun applyEqualizer() {
        val current = equalizer ?: return
        val info = eqInfo ?: return
        try {
            val levels = ExtraSettings.eqLevels
            for (band in 0 until info.bandCount) {
                val level = levels.getOrElse(band) { 0 }.coerceIn(info.minLevel, info.maxLevel)
                current.setBandLevel(band.toShort(), level.toShort())
            }
            current.setEnabled(ExtraSettings.eqEnabled)
        } catch (e: Exception) {
            // The setting couldn't be applied right now.
        }
    }
}

/** Ready-made equalizer settings. */
object EqPresets {
    val names = listOf("Flat", "Bass boost", "Treble boost", "Rock", "Pop", "Vocal", "Classical")

    // Gains in decibels for five spread-out areas of the sound, from bass (left) to treble (right).
    private val gains = mapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
        "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
        "Treble boost" to listOf(0f, 0f, 0f, 4f, 6f),
        "Rock" to listOf(4f, 2f, -2f, 2f, 4f),
        "Pop" to listOf(-1f, 2f, 4f, 2f, -1f),
        "Vocal" to listOf(-2f, 0f, 4f, 3f, 0f),
        "Classical" to listOf(3f, 2f, -1f, 2f, 3f),
    )

    /** The level for each of this phone's bands, spreading the preset evenly across them. */
    fun levels(name: String, info: EqInfo): List<Int> {
        val points = gains[name] ?: return List(info.bandCount) { 0 }
        return List(info.bandCount) { band ->
            val position = if (info.bandCount == 1) 0f else band * (points.size - 1f) / (info.bandCount - 1f)
            val low = position.toInt().coerceIn(0, points.size - 1)
            val high = (low + 1).coerceAtMost(points.size - 1)
            val fraction = position - low
            val decibels = points[low] + (points[high] - points[low]) * fraction
            (decibels * 100).roundToInt().coerceIn(info.minLevel, info.maxLevel)
        }
    }
}

/** Pauses the music after a chosen number of minutes. */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable {
        ServiceHooks.pause()
        endsAtMs = 0L
    }

    /** When the timer will pause the music (milliseconds since 1970), or 0 if no timer is running. */
    var endsAtMs by mutableLongStateOf(0L)
        private set

    fun start(minutes: Int) {
        handler.removeCallbacks(fire)
        val delayMs = minutes * 60_000L
        endsAtMs = System.currentTimeMillis() + delayMs
        handler.postDelayed(fire, delayMs)
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        endsAtMs = 0L
    }
}

// ---------------------------------------------------------------------------
// Backup and restore of the app's own data (settings, playlists, history). Not your music.
// ---------------------------------------------------------------------------
object AppBackup {
    private val prefFiles = listOf("vibeplayer_settings", "vibeplayer_extra", "vibeplayer_prefs")

    /** Collects everything into one piece of text (JSON), ready to be saved as a file. */
    fun create(context: Context): String {
        val root = JSONObject()
        root.put("app", "VibePlayer")
        root.put("version", 1)
        root.put("created", System.currentTimeMillis())

        val prefs = JSONObject()
        for (name in prefFiles) prefs.put(name, dumpPrefs(context, name))
        root.put("prefs", prefs)

        val playlistsFile = File(context.applicationContext.filesDir, "playlists.json")
        if (playlistsFile.exists()) {
            try {
                root.put("playlists", JSONArray(playlistsFile.readText()))
            } catch (e: Exception) {
                // A damaged playlists file is simply left out of the backup.
            }
        }
        val historyFile = File(context.applicationContext.filesDir, "play_history.txt")
        if (historyFile.exists()) root.put("history", historyFile.readText())
        return root.toString(2)
    }

    /**
     * Puts a backup back. Returns null if it worked, or a message explaining what went wrong.
     * The app must be closed and opened again afterwards to load the restored data.
     */
    fun restore(context: Context, text: String): String? {
        try {
            val root = JSONObject(text)
            if (root.optString("app") != "VibePlayer") return "That file is not a VibePlayer backup."

            val prefs = root.optJSONObject("prefs")
            if (prefs != null) {
                for (name in prefFiles) {
                    val section = prefs.optJSONObject(name)
                    if (section != null) restorePrefs(context, name, section)
                }
            }
            val playlists = root.optJSONArray("playlists")
            if (playlists != null) {
                File(context.applicationContext.filesDir, "playlists.json").writeText(playlists.toString())
            }
            if (root.has("history")) {
                File(context.applicationContext.filesDir, "play_history.txt").writeText(root.optString("history"))
            }
            return null
        } catch (e: Exception) {
            return "Could not read that backup file."
        }
    }

    private fun dumpPrefs(context: Context, name: String): JSONObject {
        val out = JSONObject()
        val all = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).all
        for ((key, value) in all) {
            val entry = JSONObject()
            when (value) {
                is Boolean -> {
                    entry.put("t", "b")
                    entry.put("v", value)
                }
                is Int -> {
                    entry.put("t", "i")
                    entry.put("v", value)
                }
                is Long -> {
                    entry.put("t", "l")
                    entry.put("v", value)
                }
                is Float -> {
                    entry.put("t", "f")
                    entry.put("v", value.toDouble())
                }
                is String -> {
                    entry.put("t", "s")
                    entry.put("v", value)
                }
                is Set<*> -> {
                    entry.put("t", "set")
                    entry.put("v", JSONArray(value.filterIsInstance<String>()))
                }
                else -> continue
            }
            out.put(key, entry)
        }
        return out
    }

    private fun restorePrefs(context: Context, name: String, section: JSONObject) {
        val editor = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit()
            .clear()
        for (key in section.keys()) {
            val entry = section.getJSONObject(key)
            when (entry.getString("t")) {
                "b" -> editor.putBoolean(key, entry.getBoolean("v"))
                "i" -> editor.putInt(key, entry.getInt("v"))
                "l" -> editor.putLong(key, entry.getLong("v"))
                "f" -> editor.putFloat(key, entry.getDouble("v").toFloat())
                "s" -> editor.putString(key, entry.getString("v"))
                "set" -> {
                    val array = entry.getJSONArray("v")
                    val set = HashSet<String>()
                    for (i in 0 until array.length()) set.add(array.getString(i))
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.commit()
    }
}
