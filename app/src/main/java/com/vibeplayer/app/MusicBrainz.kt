package com.vibeplayer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One possible match for a song, found on MusicBrainz (a free online music database). */
data class MbResult(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val year: String,
    val trackNumber: String,
    val genre: String,
    val lengthSec: Double,
    val score: Int,
)

object MusicBrainz {
    private const val BASE = "https://musicbrainz.org/ws/2"

    /** Searches for recordings with this title and artist. Either can be left empty. */
    suspend fun search(title: String, artist: String): List<MbResult> = withContext(Dispatchers.IO) {
        val parts = ArrayList<String>()
        if (title.isNotBlank()) parts.add("recording:${quote(title)}")
        if (artist.isNotBlank()) parts.add("artist:${quote(artist)}")
        if (parts.isEmpty()) return@withContext emptyList<MbResult>()
        val query = parts.joinToString(" AND ")
        val address = "$BASE/recording?query=${URLEncoder.encode(query, "UTF-8")}&fmt=json&limit=15"
        parse(httpGet(address))
    }

    /** Wraps text in quotes for MusicBrainz's search language. */
    private fun quote(text: String): String =
        "\"" + text.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun httpGet(address: String): String? {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "VibePlayer/1.0 (personal-use Android music player)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("MusicBrainz answered with error $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** Text from a JSON field, or "" when it is missing or null. */
    private fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

    /** Joins artist credits like "Brent Faiyaz" + " feat. " + "A$AP Rocky". */
    private fun credit(array: JSONArray?): String {
        if (array == null) return ""
        val text = StringBuilder()
        for (i in 0 until array.length()) {
            val part = array.optJSONObject(i) ?: continue
            val name = part.str("name").ifBlank { part.optJSONObject("artist")?.str("name") ?: "" }
            text.append(name).append(part.str("joinphrase"))
        }
        return text.toString().trim()
    }

    /** The most popular tag, used as a genre suggestion. */
    private fun topTag(tags: JSONArray?): String {
        if (tags == null) return ""
        var bestName = ""
        var bestCount = -1
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONObject(i) ?: continue
            val count = tag.optInt("count", 0)
            if (count > bestCount) {
                bestCount = count
                bestName = tag.str("name")
            }
        }
        return bestName.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    private fun parse(json: String?): List<MbResult> {
        if (json.isNullOrBlank()) return emptyList()
        val recordings = JSONObject(json).optJSONArray("recordings") ?: return emptyList()
        val results = ArrayList<MbResult>()
        for (i in 0 until recordings.length()) {
            val recording = recordings.optJSONObject(i) ?: continue
            val title = recording.str("title")
            val artist = credit(recording.optJSONArray("artist-credit"))
            val lengthSec = recording.optDouble("length", 0.0).let { if (it.isNaN()) 0.0 else it / 1000.0 }
            val score = recording.optInt("score", 0)
            val genre = topTag(recording.optJSONArray("tags"))
            val releases = recording.optJSONArray("releases")

            if (releases == null || releases.length() == 0) {
                results.add(
                    MbResult(title, artist, "", "", recording.str("first-release-date").take(4), "", genre, lengthSec, score)
                )
                continue
            }
            for (j in 0 until minOf(releases.length(), 4)) {
                val release = releases.optJSONObject(j) ?: continue
                val number = release.optJSONArray("media")
                    ?.optJSONObject(0)
                    ?.optJSONArray("track")
                    ?.optJSONObject(0)
                    ?.str("number")
                    ?.filter { it.isDigit() }
                    ?: ""
                results.add(
                    MbResult(
                        title = title,
                        artist = artist,
                        album = release.str("title"),
                        albumArtist = credit(release.optJSONArray("artist-credit")).ifBlank { artist },
                        year = release.str("date").take(4),
                        trackNumber = number,
                        genre = genre,
                        lengthSec = lengthSec,
                        score = score,
                    )
                )
            }
        }
        return results
    }
}
