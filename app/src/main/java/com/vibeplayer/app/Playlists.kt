package com.vibeplayer.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/**
 * One song inside a playlist.
 * The key is the same file-path name the rest of the app uses (Track.key), so a playlist
 * survives tag edits. The title, artist and length are kept as a backup description, which
 * lets the app still show a song's name if its file can't be found any more.
 */
data class PlaylistEntry(
    val key: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

/** A named, ordered list of songs. */
data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry>,
)

/** Turns a library song into a playlist entry. */
fun Track.toEntry(): PlaylistEntry = PlaylistEntry(key, title, artist, durationMs)

/** The built-in "Favorite songs" playlist always has this fixed id. */
const val FAVORITES_ID = "favorites"
const val FAVORITES_NAME = "Favorite songs"

/**
 * All your playlists. They are saved in one small file (playlists.json) inside the app's
 * private storage. The screens redraw automatically whenever the list changes.
 */
object PlaylistStore {
    private var file: File? = null

    /** Every playlist, in the order they were made. */
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    /** Reads the playlists file. Safe to call more than once. */
    fun load(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "playlists.json")
        file = f
        playlists = try {
            if (f.exists()) parse(f.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        ensureFavorites()
    }

    private fun parse(text: String): List<Playlist> {
        val result = ArrayList<Playlist>()
        val array = JSONArray(text)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val entries = ArrayList<PlaylistEntry>()
            val songs = obj.optJSONArray("songs") ?: JSONArray()
            for (j in 0 until songs.length()) {
                val song = songs.getJSONObject(j)
                entries.add(
                    PlaylistEntry(
                        key = song.optString("key"),
                        title = song.optString("title"),
                        artist = song.optString("artist"),
                        durationMs = song.optLong("durationMs"),
                    )
                )
            }
            result.add(
                Playlist(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    name = obj.optString("name", "Playlist"),
                    entries = entries,
                )
            )
        }
        return result
    }

    private fun save() {
        try {
            val array = JSONArray()
            for (playlist in playlists) {
                val obj = JSONObject()
                obj.put("id", playlist.id)
                obj.put("name", playlist.name)
                val songs = JSONArray()
                for (entry in playlist.entries) {
                    val song = JSONObject()
                    song.put("key", entry.key)
                    song.put("title", entry.title)
                    song.put("artist", entry.artist)
                    song.put("durationMs", entry.durationMs)
                    songs.put(song)
                }
                obj.put("songs", songs)
                array.put(obj)
            }
            val target = file ?: return
            // Write to a temporary file first, then swap it in, so a crash
            // in the middle of saving can't leave a half-written playlists file.
            val temp = File(target.parentFile, "playlists.json.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(target)) {
                target.writeText(array.toString())
                temp.delete()
            }
        } catch (e: Exception) {
            // The playlists stay in memory until the next successful save.
        }
    }

    private fun update(newList: List<Playlist>) {
        playlists = newList
        save()
    }

    /** Makes a new playlist (optionally with songs already in it) and returns it. */
    fun create(name: String, entries: List<PlaylistEntry> = emptyList()): Playlist {
        val clean = name.trim().ifEmpty { "New playlist" }
        val playlist = Playlist(UUID.randomUUID().toString(), clean, entries)
        update(playlists + playlist)
        return playlist
    }

    fun rename(id: String, name: String) {
        if (id == FAVORITES_ID) return
        val clean = name.trim()
        if (clean.isEmpty()) return
        update(playlists.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun delete(id: String) {
        if (id == FAVORITES_ID) return
        update(playlists.filter { it.id != id })
    }

    /** Makes sure the built-in Favorite songs playlist exists. It can't be renamed or deleted. */
    fun ensureFavorites() {
        if (playlists.none { it.id == FAVORITES_ID }) {
            update(listOf(Playlist(FAVORITES_ID, FAVORITES_NAME, emptyList())) + playlists)
        }
    }

    fun isFavorite(track: Track): Boolean =
        playlists.firstOrNull { it.id == FAVORITES_ID }?.entries?.any { it.key == track.key } == true

    /** Adds the song to Favorite songs, or removes it if it is already there. Returns true if it is now a favorite. */
    fun toggleFavorite(track: Track): Boolean {
        ensureFavorites()
        if (isFavorite(track)) {
            update(
                playlists.map { playlist ->
                    if (playlist.id == FAVORITES_ID) {
                        playlist.copy(entries = playlist.entries.filter { it.key != track.key })
                    } else {
                        playlist
                    }
                }
            )
            return false
        }
        addTracks(FAVORITES_ID, listOf(track))
        return true
    }

    /** Adds songs to the end of a playlist, skipping ones already in it. Returns how many were added. */
    fun addTracks(id: String, tracks: List<Track>): Int {
        var added = 0
        update(
            playlists.map { playlist ->
                if (playlist.id != id) {
                    playlist
                } else {
                    val have = playlist.entries.map { it.key }.toHashSet()
                    val fresh = tracks.filter { have.add(it.key) }.map { it.toEntry() }
                    added = fresh.size
                    playlist.copy(entries = playlist.entries + fresh)
                }
            }
        )
        return added
    }

    /** Removes the song at this position from a playlist. */
    fun removeAt(id: String, index: Int) {
        update(
            playlists.map { playlist ->
                if (playlist.id == id && index in playlist.entries.indices) {
                    playlist.copy(entries = playlist.entries.filterIndexed { i, _ -> i != index })
                } else {
                    playlist
                }
            }
        )
    }

    /** Moves a song from one position to another inside a playlist. */
    fun move(id: String, from: Int, to: Int) {
        update(
            playlists.map { playlist ->
                if (playlist.id != id || from !in playlist.entries.indices || to !in playlist.entries.indices) {
                    playlist
                } else {
                    val list = playlist.entries.toMutableList()
                    val item = list.removeAt(from)
                    list.add(to, item)
                    playlist.copy(entries = list)
                }
            }
        )
    }
}

/**
 * Reading and writing the standard playlist file format (.m3u / .m3u8).
 * A playlist file is plain text: one song per line, with optional "#" lines that carry
 * extra information such as the artist and title.
 */
object PlaylistM3u {

    /** What came out of reading a playlist file. */
    class ImportResult(
        val name: String,
        val entries: List<PlaylistEntry>,
        /** How many of the songs were matched to songs in your library. */
        val found: Int,
    )

    /** Turns a playlist into the text of an .m3u8 file. */
    fun export(playlist: Playlist): String {
        val text = StringBuilder()
        text.append("#EXTM3U\n")
        text.append("#PLAYLIST:").append(playlist.name.replace('\n', ' ')).append('\n')
        for (entry in playlist.entries) {
            val seconds = (entry.durationMs / 1000).coerceAtLeast(0)
            val label = if (entry.artist.isBlank()) entry.title else "${entry.artist} - ${entry.title}"
            text.append("#EXTINF:").append(seconds).append(',').append(label.replace('\n', ' ')).append('\n')
            text.append(entry.key).append('\n')
        }
        return text.toString()
    }

    /**
     * Reads the text of a playlist file and matches each line to a song in your library.
     * It tries, in order: the exact file path, the same file name, then the artist and title
     * written in the "#EXTINF" line. Songs that can't be matched are still kept in the playlist
     * (shown as "not found"), so nothing from the file is lost.
     */
    fun parse(text: String, library: List<Track>, fallbackName: String): ImportResult {
        val byPath = HashMap<String, Track>()
        val byFileName = HashMap<String, MutableList<Track>>()
        val byArtistAndTitle = HashMap<String, Track>()
        val byTitle = HashMap<String, Track>()
        for (track in library) {
            val path = normalizePath(track.path)
            if (path.isNotEmpty()) {
                byPath[path] = track
                byFileName.getOrPut(path.substringAfterLast('/')) { mutableListOf() }.add(track)
            }
            byArtistAndTitle.putIfAbsent(songName(track.artist, track.title), track)
            byTitle.putIfAbsent(track.title.trim().lowercase(Locale.ROOT), track)
        }

        var name = fallbackName
        var haveInfo = false
        var infoArtist = ""
        var infoTitle = ""
        var infoSeconds = -1L
        var found = 0
        val entries = ArrayList<PlaylistEntry>()

        for (rawLine in text.removePrefix("\uFEFF").lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val body = line.substringAfter(':')
                    val comma = body.indexOf(',')
                    val lengthPart = if (comma >= 0) body.substring(0, comma) else body
                    infoSeconds = lengthPart.trim().split(' ').firstOrNull()
                        ?.toDoubleOrNull()?.toLong() ?: -1L
                    val label = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    val dash = label.indexOf(" - ")
                    if (dash > 0) {
                        infoArtist = label.substring(0, dash).trim()
                        infoTitle = label.substring(dash + 3).trim()
                    } else {
                        infoArtist = ""
                        infoTitle = label
                    }
                    haveInfo = true
                } else if (line.startsWith("#PLAYLIST:", ignoreCase = true)) {
                    val named = line.substringAfter(':').trim()
                    if (named.isNotEmpty()) name = named
                }
                continue
            }

            // A song line.
            val path = normalizePath(line)
            var track: Track? = byPath[path]
            if (track == null) {
                val candidates = byFileName[path.substringAfterLast('/')]
                if (candidates != null) track = bestByEnding(candidates, path)
            }
            if (track == null && haveInfo && infoTitle.isNotEmpty()) {
                track = if (infoArtist.isNotEmpty()) {
                    byArtistAndTitle[songName(infoArtist, infoTitle)]
                } else {
                    byTitle[infoTitle.trim().lowercase(Locale.ROOT)]
                }
            }

            if (track != null) {
                entries.add(track.toEntry())
                found++
            } else {
                val cleaned = cleanPath(line)
                entries.add(
                    PlaylistEntry(
                        key = cleaned,
                        title = if (infoTitle.isNotEmpty()) infoTitle
                        else cleaned.substringAfterLast('/').substringBeforeLast('.'),
                        artist = infoArtist,
                        durationMs = if (infoSeconds > 0) infoSeconds * 1000L else 0L,
                    )
                )
            }
            haveInfo = false
            infoArtist = ""
            infoTitle = ""
            infoSeconds = -1L
        }
        return ImportResult(name, entries, found)
    }

    /** Fixes a path from a playlist file: removes "file://", decodes %20-style codes, uses "/" slashes. */
    private fun cleanPath(raw: String): String {
        var p = raw.trim()
        if (p.startsWith("file://", ignoreCase = true)) {
            p = p.substring(7)
            p = try {
                URLDecoder.decode(p.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                p
            }
        }
        return p.replace('\\', '/')
    }

    /** Same as cleanPath, but ignoring capital letters, for comparing. */
    private fun normalizePath(raw: String): String = cleanPath(raw).lowercase(Locale.ROOT)

    private fun songName(artist: String, title: String): String =
        (artist.trim() + "|" + title.trim()).lowercase(Locale.ROOT)

    /** When several songs share a file name, picks the one whose folders match the path best. */
    private fun bestByEnding(candidates: List<Track>, path: String): Track {
        val wanted = path.split('/').reversed()
        var best = candidates.first()
        var bestScore = -1
        for (candidate in candidates) {
            val have = normalizePath(candidate.path).split('/').reversed()
            var score = 0
            while (score < wanted.size && score < have.size && wanted[score] == have[score]) score++
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }
        return best
    }
}

/** Helpers for reading and writing the playlist files you pick with Android's file picker. */
object PlaylistFiles {

    private const val MAX_BYTES = 5_000_000

    /** Reads a text file. Returns null if it can't be read or is suspiciously large. */
    fun readText(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) return null
                buffer.write(chunk, 0, count)
            }
            buffer.toString("UTF-8")
        }
    } catch (e: Exception) {
        null
    }

    /** Writes text to a file the person chose. Returns true if it worked. */
    fun writeText(context: Context, uri: Uri, text: String): Boolean = try {
        val out = context.contentResolver.openOutputStream(uri, "wt")
        if (out == null) {
            false
        } else {
            out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            true
        }
    } catch (e: Exception) {
        false
    }

    /** The file's name as shown in the file picker, for example "Road trip.m3u8". */
    fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "Playlist"
    } catch (e: Exception) {
        "Playlist"
    }

    /** A safe file name for exporting, like "Road trip.m3u8". */
    fun fileNameFor(playlistName: String): String {
        val safe = playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Playlist" }
        return "$safe.m3u8"
    }
}
