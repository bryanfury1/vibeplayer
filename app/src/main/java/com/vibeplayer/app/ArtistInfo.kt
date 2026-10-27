package com.vibeplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** A short biography and where it came from. */
class ArtistBioInfo(val text: String, val pageUrl: String)

/**
 * Artist pictures (from Deezer's free public search) and short biographies (from Wikipedia).
 * Everything found is saved on the phone, so each artist is only looked up once.
 * Pictures and bios live in the same cache folder as album art, so the cache size limit
 * and the "Clear" button in Settings cover them too.
 */
object ArtistInfo {
    private const val USER_AGENT = "VibePlayer/1.0 (personal Android music player)"
    private const val PICTURE_RECHECK_MS = 14L * 24 * 60 * 60 * 1000
    private const val BIO_KEEP_MS = 60L * 24 * 60 * 60 * 1000
    private const val BIO_RECHECK_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_IMAGE_BYTES = 2_000_000

    // At most 3 lookups at the same time, so scrolling a long list doesn't flood the services.
    private val limiter = Semaphore(3)
    private val downloads = AtomicInteger(0)

    // Recently shown pictures stay in memory (up to about 24 MB).
    private val pictureMemory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val musicWords = Regex(
        "\\b(singer|rapper|musician|band|songwriter|vocalist|guitarist|drummer|pianist|composer|" +
            "producer|DJ|duo|group|trio|quartet|orchestra|recording artist|music)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun folder(context: Context): File {
        val dir = File(context.applicationContext.cacheDir, "art")
        dir.mkdirs()
        return dir
    }

    private fun fileKey(name: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(name.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Lower case, no accents, "&" read as "and", only letters and digits, for comparing names. */
    private fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase(Locale.ROOT).replace("&", "and").filter { it.isLetterOrDigit() }
    }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    /** Forgets the pictures kept in memory (the saved copies are cleared separately). */
    fun clearMemory() {
        pictureMemory.evictAll()
    }

    // -----------------------------------------------------------------------
    // Pictures
    // -----------------------------------------------------------------------

    /** The artist's picture, or null if there is none (or it can't be fetched right now). */
    suspend fun loadPicture(context: Context, name: String, sizePx: Int): Bitmap? {
        val big = sizePx > 256
        val id = fileKey(name)
        val memoryKey = id + (if (big) "_b" else "_m")
        val inMemory = pictureMemory.get(memoryKey)
        if (inMemory != null) return inMemory

        val app = context.applicationContext
        val bitmap = withContext(Dispatchers.IO) { readOrDownloadPicture(app, id, name, big) }
        if (bitmap != null) pictureMemory.put(memoryKey, bitmap)
        return bitmap
    }

    private suspend fun readOrDownloadPicture(app: Context, id: String, name: String, big: Boolean): Bitmap? {
        val dir = folder(app)
        val file = File(dir, "artist_${id}_${if (big) "b" else "m"}.jpg")
        val none = File(dir, "artist_$id.none")

        val saved = readSavedPicture(file)
        if (saved != null) return saved
        if (none.exists()) {
            if (System.currentTimeMillis() - none.lastModified() < PICTURE_RECHECK_MS) return null
            none.delete()
        }
        return limiter.withPermit {
            readSavedPicture(file) ?: downloadPicture(app, file, none, name, big)
        }
    }

    private fun readSavedPicture(file: File): Bitmap? {
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            file.delete()
            return null
        }
        file.setLastModified(System.currentTimeMillis()) // marks it as recently used
        return bitmap
    }

    private fun downloadPicture(app: Context, file: File, none: File, name: String, big: Boolean): Bitmap? {
        try {
            val json = httpGetText("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}") ?: return null
            val root = JSONObject(json)
            if (root.has("error")) return null // busy or over its limit: try again another time

            val data = root.optJSONArray("data")
            val wanted = normalize(name)
            var url = ""
            if (data != null) {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    if (normalize(item.optString("name")) == wanted) {
                        url = item.optString(if (big) "picture_big" else "picture_medium")
                        break
                    }
                }
            }
            // No match, or only Deezer's grey placeholder picture: remember that, so we don't keep asking.
            if (url.isBlank() || url.contains("/artist//")) {
                none.createNewFile()
                return null
            }

            val bytes = httpGetBytes(url) ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.writeBytes(bytes)
            // Every 50 new pictures, check that the cache hasn't grown past its limit.
            if (downloads.incrementAndGet() % 50 == 0) ImageDiskCache.trim(app)
            return bitmap
        } catch (e: Exception) {
            return null // no internet or a hiccup: try again next time
        }
    }

    // -----------------------------------------------------------------------
    // Biographies
    // -----------------------------------------------------------------------

    private class BioFile(val info: ArtistBioInfo?)

    private class BioResult(val info: ArtistBioInfo?, val definitive: Boolean)

    /** A short biography of the artist, or null if none was found. */
    suspend fun loadBio(context: Context, name: String): ArtistBioInfo? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val file = File(folder(app), "artist_${fileKey(name)}.bio")

        val saved = readBioFile(file)
        if (saved != null) return@withContext saved.info

        limiter.withPermit {
            val again = readBioFile(file)
            if (again != null) return@withPermit again.info
            val result = fetchBio(name)
            if (result.definitive) writeBioFile(file, result.info)
            result.info
        }
    }

    private fun readBioFile(file: File): BioFile? {
        if (!file.exists()) return null
        try {
            val obj = JSONObject(file.readText())
            val found = obj.optBoolean("found")
            val keepFor = if (found) BIO_KEEP_MS else BIO_RECHECK_MS
            if (System.currentTimeMillis() - file.lastModified() < keepFor) {
                val info = if (found) ArtistBioInfo(obj.getString("text"), obj.optString("url")) else null
                return BioFile(info)
            }
        } catch (e: Exception) {
            // A damaged file is simply deleted below.
        }
        file.delete()
        return null
    }

    private fun writeBioFile(file: File, info: ArtistBioInfo?) {
        try {
            val obj = JSONObject()
            obj.put("found", info != null)
            if (info != null) {
                obj.put("text", info.text)
                obj.put("url", info.pageUrl)
            }
            file.writeText(obj.toString())
        } catch (e: Exception) {
            // Not saved: it will be fetched again next time.
        }
    }

    private fun fetchBio(name: String): BioResult {
        try {
            val query = enc("\"$name\" (musician OR singer OR band OR rapper OR group)")
            val searchJson = httpGetText(
                "https://en.wikipedia.org/w/api.php?action=query&list=search&srlimit=5&format=json&srsearch=$query"
            ) ?: return BioResult(null, false)
            val results = JSONObject(searchJson).optJSONObject("query")?.optJSONArray("search")
                ?: return BioResult(null, true)

            val wanted = normalize(name)
            var checked = 0
            for (i in 0 until results.length()) {
                val title = results.optJSONObject(i)?.optString("title").orEmpty()
                // "Drake (musician)" counts as "Drake". The name must match exactly, so a different
                // artist with a similar name is never shown by mistake.
                if (normalize(title.substringBefore(" (")) != wanted) continue
                checked++
                if (checked > 3) break

                val address = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                    enc(title.replace(' ', '_')).replace("+", "%20")
                val summaryJson = httpGetText(address) ?: continue
                val summary = JSONObject(summaryJson)
                if (summary.optString("type") != "standard") continue
                val extract = summary.optString("extract").trim()
                val about = summary.optString("description") + " " + extract
                if (extract.isEmpty() || !musicWords.containsMatchIn(about)) continue

                val pageUrl = summary.optJSONObject("content_urls")
                    ?.optJSONObject("desktop")?.optString("page").orEmpty()
                return BioResult(ArtistBioInfo(extract, pageUrl), true)
            }
            return BioResult(null, true)
        } catch (e: Exception) {
            return BioResult(null, false)
        }
    }

    // -----------------------------------------------------------------------
    // Downloading
    // -----------------------------------------------------------------------

    private fun open(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    /** The text at a web address. Null if the page doesn't exist (404). Other problems throw. */
    private fun httpGetText(address: String): String? {
        val connection = open(address)
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("Answered with error $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /** The bytes of a picture. Null if it doesn't exist. Pictures over 2 MB are refused. */
    private fun httpGetBytes(address: String): ByteArray? {
        val connection = open(address)
        try {
            val code = connection.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("Answered with error $code")
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw IOException("Picture is too large")
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }
}
