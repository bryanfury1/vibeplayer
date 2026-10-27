package com.vibeplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// Album art: small copies are kept on the phone, so pictures show up faster
// and don't have to be fetched again after the app restarts.
// ---------------------------------------------------------------------------
object ImageDiskCache {
    private val writes = AtomicInteger(0)
    private const val NO_ART_RECHECK_MS = 7L * 24 * 60 * 60 * 1000

    private fun folder(context: Context): File {
        val dir = File(context.applicationContext.cacheDir, "art")
        dir.mkdirs()
        return dir
    }

    /** A file name made from the song and picture size, so each picture has its own copy. */
    private fun nameFor(track: Track, sizePx: Int): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${track.key}|${track.id}|$sizePx".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadFromPhone(context: Context, track: Track, sizePx: Int): Bitmap =
        context.contentResolver.loadThumbnail(track.uri, Size(sizePx, sizePx), null)

    /**
     * Gets a song's album art: from the saved copy if there is one, otherwise from Android
     * (and then saves a copy). Throws if the song has no art, just like loadThumbnail does.
     */
    fun getOrLoad(context: Context, track: Track, sizePx: Int): Bitmap {
        val app = context.applicationContext
        if (!ExtraSettings.imageCacheEnabled) return loadFromPhone(app, track, sizePx)

        val dir = folder(app)
        val name = nameFor(track, sizePx)
        val picture = File(dir, "$name.jpg")
        val noArt = File(dir, "$name.none")

        if (picture.exists()) {
            val saved = BitmapFactory.decodeFile(picture.absolutePath)
            if (saved != null) {
                picture.setLastModified(System.currentTimeMillis()) // marks it as recently used
                return saved
            }
            picture.delete()
        }
        if (noArt.exists()) {
            if (System.currentTimeMillis() - noArt.lastModified() < NO_ART_RECHECK_MS) {
                throw FileNotFoundException("This song has no album art")
            }
            noArt.delete()
        }

        try {
            val bitmap = loadFromPhone(app, track, sizePx)
            store(dir, picture, bitmap)
            return bitmap
        } catch (e: FileNotFoundException) {
            // Remember that this song has no art, so we don't keep asking.
            try {
                noArt.createNewFile()
            } catch (ignored: Exception) {
                // Not important.
            }
            throw e
        }
    }

    private fun store(dir: File, target: File, bitmap: Bitmap) {
        val temp = File(dir, target.name + ".tmp" + Thread.currentThread().id)
        try {
            FileOutputStream(temp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            if (!temp.renameTo(target)) temp.delete()
        } catch (e: Exception) {
            temp.delete()
        }
        // Every 100 new pictures, check that the cache hasn't grown past its limit.
        if (writes.incrementAndGet() % 100 == 0) trimFolder(dir)
    }

    private fun trimFolder(dir: File) {
        val files = dir.listFiles() ?: return
        var total = 0L
        for (file in files) total += file.length()
        val limit = ExtraSettings.imageCacheMb * 1_000_000L
        if (total <= limit) return
        // Delete the least recently used pictures until the cache is 80% of its limit.
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit * 8 / 10) break
            total -= file.length()
            file.delete()
        }
    }

    /** Shrinks the cache to its limit right now. */
    fun trim(context: Context) {
        trimFolder(folder(context.applicationContext))
    }

    /** How many bytes the saved pictures use. */
    fun sizeBytes(context: Context): Long {
        val files = folder(context.applicationContext).listFiles() ?: return 0L
        var total = 0L
        for (file in files) total += file.length()
        return total
    }

    fun clear(context: Context) {
        val files = folder(context.applicationContext).listFiles() ?: return
        for (file in files) file.delete()
    }
}

// ---------------------------------------------------------------------------
// Lyrics: recently opened lyrics are kept in memory (including "this song has none")
// ---------------------------------------------------------------------------

/** What was found for one song. lyrics is null when the song has no lyrics. */
class CachedLyrics(val lyrics: Lyrics?)

object LyricsCache {
    private const val MAX_SONGS = 150

    private val map = object : LinkedHashMap<String, CachedLyrics>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyrics>?): Boolean =
            size > MAX_SONGS
    }

    @Synchronized
    fun get(key: String): CachedLyrics? = map[key]

    @Synchronized
    fun put(key: String, lyrics: Lyrics?) {
        map[key] = CachedLyrics(lyrics)
    }

    @Synchronized
    fun remove(key: String) {
        map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }
}

// ---------------------------------------------------------------------------
// Lyrics download: songs that had no lyrics online are remembered for 30 days,
// so running "Download missing lyrics" again skips them.
// ---------------------------------------------------------------------------
object LyricsMisses {
    private const val FILE_NAME = "lyrics_not_found.txt"
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private var loaded: HashMap<String, Long>? = null

    private fun file(context: Context): File = File(context.applicationContext.cacheDir, FILE_NAME)

    private fun keyOf(track: Track): String = track.key.replace('\t', ' ').replace('\n', ' ')

    @Synchronized
    private fun entries(context: Context): HashMap<String, Long> {
        val existing = loaded
        if (existing != null) return existing
        val map = HashMap<String, Long>()
        try {
            val f = file(context)
            if (f.exists()) {
                for (line in f.readLines()) {
                    val tab = line.lastIndexOf('\t')
                    if (tab > 0) {
                        val time = line.substring(tab + 1).toLongOrNull()
                        if (time != null) map[line.substring(0, tab)] = time
                    }
                }
            }
        } catch (e: Exception) {
            // An unreadable list is treated as empty.
        }
        loaded = map
        return map
    }

    @Synchronized
    fun recentlyMissed(context: Context, track: Track): Boolean {
        val time = entries(context)[keyOf(track)] ?: return false
        return System.currentTimeMillis() - time < KEEP_MS
    }

    @Synchronized
    fun record(context: Context, track: Track) {
        val now = System.currentTimeMillis()
        entries(context)[keyOf(track)] = now
        try {
            file(context).appendText("${keyOf(track)}\t$now\n")
        } catch (e: Exception) {
            // It is still remembered until the app closes.
        }
    }

    @Synchronized
    fun count(context: Context): Int = entries(context).size

    @Synchronized
    fun clear(context: Context) {
        loaded = HashMap()
        try {
            file(context).delete()
        } catch (e: Exception) {
            // Nothing to do.
        }
    }
}
