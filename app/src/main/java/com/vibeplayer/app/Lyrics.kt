package com.vibeplayer.app

import android.content.Context
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One line of lyrics. timeMs is when it should be highlighted, or -1 if the lyrics have no timing. */
data class LyricLine(val timeMs: Long, val text: String)

/** All the lyrics of a song. synced = true means every line has a time stamp. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean)

// ---------------------------------------------------------------------------
// Reading .lrc text
// ---------------------------------------------------------------------------
object LrcParser {
    // The time stamps at the very start of a line, like [01:23.45] or [01:23.45][02:30.10]
    private val leadingTags = Regex("""^(?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?])+""")
    private val oneTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    // Word-by-word stamps like <01:23.45> are ignored.
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    // Information lines such as [ar:Artist] or [ti:Title]
    private val infoLine = Regex("""^\[[A-Za-z#]+:.*]$""")
    private val offsetLine = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Lyrics? {
        var offsetMs = 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<LyricLine>()

        for (rawLine in text.lines()) {
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            val offsetMatch = offsetLine.find(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val leading = leadingTags.find(line)
            if (leading != null) {
                val content = wordTag.replace(line.substring(leading.range.last + 1), "").trim()
                for (tag in oneTag.findAll(leading.value)) {
                    val minutes = tag.groupValues[1].toLong()
                    val seconds = tag.groupValues[2].toLong()
                    val fraction = tag.groupValues[3].padEnd(3, '0').take(3).toLong()
                    val time = minutes * 60_000L + seconds * 1000L + fraction
                    timed.add(LyricLine(time, content))
                }
            } else if (!infoLine.matches(line)) {
                plain.add(LyricLine(-1L, line))
            }
        }

        return when {
            timed.isNotEmpty() -> Lyrics(
                timed
                    .map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }
                    .sortedBy { it.timeMs },
                synced = true,
            )
            plain.isNotEmpty() -> Lyrics(plain, synced = false)
            else -> null
        }
    }
}

/** Index of the line that should be highlighted at this moment, or -1 before the first line. */
fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].timeMs <= positionMs) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

// ---------------------------------------------------------------------------
// Where lyrics are stored
// ---------------------------------------------------------------------------
object LyricsRepository {

    /** True when Android's "All files access" is switched on for this app. */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** The .lrc file that sits next to the song, with the same name. */
    private fun sidecarFile(track: Track): File? {
        if (track.path.isBlank()) return null
        val song = File(track.path)
        val folder = song.parentFile ?: return null
        return File(folder, song.nameWithoutExtension + ".lrc")
    }

    /** A copy kept inside the app, used when we are not allowed to write next to the song. */
    private fun privateFile(context: Context, track: Track): File {
        val folder = File(context.filesDir, "lyrics")
        folder.mkdirs()
        return File(folder, "${Integer.toHexString(track.key.hashCode())}.lrc")
    }

    /** The naming used by earlier versions (kept so older saved lyrics are still found). */
    private fun legacyPrivateFile(context: Context, track: Track): File {
        val code = Integer.toHexString(track.uri.toString().hashCode())
        return File(File(context.filesDir, "lyrics"), "${track.id}_$code.lrc")
    }

    /** Looks for lyrics for this song. If two copies exist, the newer one wins. */
    suspend fun load(context: Context, track: Track): Lyrics? {
        if (ExtraSettings.lyricsCacheEnabled) {
            val remembered = LyricsCache.get(track.key)
            if (remembered != null) return remembered.lyrics
        }
        val loaded = loadUncached(context, track)
        if (ExtraSettings.lyricsCacheEnabled) LyricsCache.put(track.key, loaded)
        return loaded
    }

    private suspend fun loadUncached(context: Context, track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(sidecarFile(track), privateFile(context, track), legacyPrivateFile(context, track))
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
        for (file in candidates) {
            try {
                val parsed = LrcParser.parse(file.readText())
                if (parsed != null) return@withContext parsed
            } catch (e: Exception) {
                // Unreadable file: try the next one.
            }
        }
        // No separate .lrc file: look for lyrics stored inside the song file itself.
        TagEditor.readLyrics(track)?.let { LrcParser.parse(it) }
    }

    /** The text of the newest saved .lrc file (next to the song or inside the app), if any. */
    suspend fun loadSavedText(context: Context, track: Track): String? = withContext(Dispatchers.IO) {
        listOfNotNull(sidecarFile(track), privateFile(context, track), legacyPrivateFile(context, track))
            .filter { it.isFile }
            .maxByOrNull { it.lastModified() }
            ?.let { file ->
                try {
                    file.readText()
                } catch (e: Exception) {
                    null
                }
            }
    }

    /** True if the song already has lyrics: a .lrc file next to it, a copy in the app, or inside the file. */
    suspend fun hasAnyLyrics(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        val files = listOfNotNull(sidecarFile(track), privateFile(context, track))
        if (files.any { it.isFile }) return@withContext true
        TagEditor.readLyrics(track) != null
    }

    /** Saves lyrics as a .lrc file next to the song if allowed, otherwise inside the app. */
    suspend fun save(context: Context, track: Track, lrcText: String): Boolean {
        val saved = saveUncached(context, track, lrcText)
        LyricsCache.remove(track.key)
        return saved
    }

    private suspend fun saveUncached(context: Context, track: Track, lrcText: String): Boolean =
        withContext(Dispatchers.IO) {
            val next = sidecarFile(track)
            if (next != null && hasAllFilesAccess()) {
                try {
                    next.writeText(lrcText)
                    return@withContext true
                } catch (e: Exception) {
                    // Fall back to the app's own storage.
                }
            }
            try {
                privateFile(context, track).writeText(lrcText)
                true
            } catch (e: Exception) {
                false
            }
        }
}

// ---------------------------------------------------------------------------
// Searching LRCLIB (lrclib.net), a free online lyrics library
// ---------------------------------------------------------------------------
data class LrcLibResult(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val instrumental: Boolean,
    val plain: String?,
    val synced: String?,
)

object LrcLib {
    private const val BASE = "https://lrclib.net/api"

    /** Searches by song name and artist. This is the most accurate kind of search. */
    suspend fun searchFields(trackName: String, artistName: String): List<LrcLibResult> =
        withContext(Dispatchers.IO) {
            val url = "$BASE/search?track_name=${enc(trackName)}&artist_name=${enc(artistName)}"
            parseResults(httpGet(url))
        }

    /** Searches with free text, for when the names in your files don't match. */
    suspend fun searchQuery(query: String): List<LrcLibResult> =
        withContext(Dispatchers.IO) {
            parseResults(httpGet("$BASE/search?q=${enc(query)}"))
        }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun httpGet(address: String): String? {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "VibePlayer/1.0 (personal Android music player)")
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("LRCLIB answered with error $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun parseResults(json: String?): List<LrcLibResult> {
        if (json.isNullOrBlank()) return emptyList()
        val array = JSONArray(json)
        val results = ArrayList<LrcLibResult>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            results.add(
                LrcLibResult(
                    id = item.optLong("id"),
                    trackName = item.text("trackName") ?: "",
                    artistName = item.text("artistName") ?: "",
                    albumName = item.text("albumName") ?: "",
                    durationSec = item.optDouble("duration", 0.0),
                    instrumental = item.optBoolean("instrumental", false),
                    plain = item.text("plainLyrics")?.takeIf { it.isNotBlank() },
                    synced = item.text("syncedLyrics")?.takeIf { it.isNotBlank() },
                )
            )
        }
        return results
    }
}
