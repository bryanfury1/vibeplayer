package com.vibeplayer.app

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

/** A small bridge to the player that lives in PlaybackService. */
object ServiceHooks {
    private var player: ExoPlayer? = null
    private var audioManager: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastVolume = -1

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
    }

    /** Called by PlaybackService when it stops. */
    fun detach() {
        SleepTimer.cancel()
        handler.removeCallbacks(volumeWatcher)
        player = null
        audioManager = null
    }

    /** Applies the settings that can change while music is playing. */
    fun apply() {
        player?.setHandleAudioBecomingNoisy(ExtraSettings.pauseOnDisconnect)
    }

    fun pause() {
        player?.pause()
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
