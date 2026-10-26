package com.vibeplayer.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One song in the library. */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val fileName: String,
    val albumId: Long,
    val genre: String,
    val trackNumber: Int,
    val dateAdded: Long,
    val path: String,
)

/**
 * A name for the song that stays the same when its tags are edited.
 * (Android may give a rewritten file a new ID, so the ID can't be trusted for history.)
 */
val Track.key: String
    get() = path.ifBlank { uri.toString() }

/**
 * Remembers which folders you allowed VibePlayer to scan.
 * Each folder is saved as Android's own folder ID, for example "primary:Music/Rock"
 * ("primary" means the phone's internal storage).
 */
object FolderStore {
    private const val PREFS = "vibeplayer_prefs"
    private const val KEY = "scan_folders"

    fun load(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .sorted()

    fun save(context: Context, folders: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, folders.toSet())
            .apply()
    }
}

/** Turns a folder ID like "primary:Music/Rock" into text a person can read. */
fun folderLabel(docId: String): String {
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "").trim('/')
    val place = if (volume == "primary") "Internal storage" else "SD card ($volume)"
    return if (path.isEmpty()) place else "$place: $path"
}

private data class ScanFolder(val volume: String, val path: String)

private fun parseFolder(docId: String): ScanFolder {
    val volume = docId.substringBefore(':')
    val path = docId.substringAfter(':', "").trim('/')
    return ScanFolder(volume, if (path.isEmpty()) "" else "$path/")
}

/** In a database LIKE search, % and _ are wildcards. This makes them match literally. */
private fun escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Asks Android's built-in media database (MediaStore) for the songs inside your chosen folders. */
object LibraryRepository {

    suspend fun load(context: Context, folders: List<String>): List<Track> =
        withContext(Dispatchers.IO) {
            // No folders chosen means nothing to scan.
            if (folders.isEmpty()) return@withContext emptyList<Track>()

            val tracks = mutableListOf<Track>()
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection = mutableListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA,
            )
            if (Build.VERSION.SDK_INT >= 29) projection.add(MediaStore.MediaColumns.VOLUME_NAME)
            if (Build.VERSION.SDK_INT >= 30) projection.add(MediaStore.Audio.Media.GENRE)

            // Build the search: "is music" AND (in folder 1 OR in folder 2 OR ...)
            val folderClauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 29) {
                for (docId in folders) {
                    val folder = parseFolder(docId)
                    val volumeName =
                        if (folder.volume == "primary") MediaStore.VOLUME_EXTERNAL_PRIMARY
                        else folder.volume.lowercase(Locale.ROOT)
                    if (folder.path.isEmpty()) {
                        folderClauses.add("${MediaStore.MediaColumns.VOLUME_NAME} = ?")
                        args.add(volumeName)
                    } else {
                        folderClauses.add(
                            "(${MediaStore.MediaColumns.VOLUME_NAME} = ? AND " +
                                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\')"
                        )
                        args.add(volumeName)
                        args.add(escapeLike(folder.path) + "%")
                    }
                }
            }
            var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            if (folderClauses.isNotEmpty()) {
                selection += " AND (${folderClauses.joinToString(" OR ")})"
            }
            val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

            context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                selection,
                if (args.isEmpty()) null else args.toTypedArray(),
                sortOrder,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val genreCol =
                    if (Build.VERSION.SDK_INT >= 30)
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)
                    else -1
                val volumeCol =
                    if (Build.VERSION.SDK_INT >= 29)
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
                    else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val fileName = cursor.getString(nameCol) ?: ""
                    // Use the song's own storage volume so SD card songs get the right address.
                    val base =
                        if (volumeCol >= 0) MediaStore.Audio.Media.getContentUri(cursor.getString(volumeCol))
                        else collection
                    tracks.add(
                        Track(
                            id = id,
                            uri = android.content.ContentUris.withAppendedId(base, id),
                            title = clean(cursor.getString(titleCol), fileName.substringBeforeLast('.')),
                            artist = clean(cursor.getString(artistCol), "Unknown Artist"),
                            album = clean(cursor.getString(albumCol), "Unknown Album"),
                            durationMs = cursor.getLong(durationCol),
                            fileName = fileName,
                            albumId = cursor.getLong(albumIdCol),
                            genre = if (genreCol >= 0) clean(cursor.getString(genreCol), "Unknown Genre")
                            else "Unknown Genre",
                            trackNumber = cursor.getInt(trackCol),
                            dateAdded = cursor.getLong(addedCol),
                            path = cursor.getString(dataCol) ?: "",
                        )
                    )
                }
            }
            tracks
        }

    private fun clean(value: String?, fallback: String): String =
        if (value.isNullOrBlank() || value == MediaStore.UNKNOWN_STRING) fallback else value
}

/** Loads album art pictures and remembers them so scrolling stays smooth. */
object ArtworkLoader {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(context: Context, track: Track, sizePx: Int): Bitmap? {
        val key = "${track.uri}_$sizePx"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        if (Build.VERSION.SDK_INT < 29) return null

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = context.contentResolver.loadThumbnail(track.uri, Size(sizePx, sizePx), null)
                cache.put(key, bitmap)
                bitmap
            } catch (e: Exception) {
                failed.add(key)
                null
            }
        }
    }
}

/** Builds the text under the slider, like "FLAC (96kHz/24bit)". */
object AudioInfo {

    suspend fun describe(context: Context, track: Track): String = withContext(Dispatchers.IO) {
        val extension = track.fileName.substringAfterLast('.', "").uppercase(Locale.ROOT)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, track.uri)
            val bitrate = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            var sampleRate = 0
            var bitsPerSample = 0
            if (Build.VERSION.SDK_INT >= 31) {
                sampleRate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                bitsPerSample = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            }

            val details = mutableListOf<String>()
            if (sampleRate > 0) details.add(formatKhz(sampleRate))
            if (bitsPerSample > 0) {
                details.add("${bitsPerSample}bit")
            } else if (bitrate > 0) {
                details.add("${bitrate / 1000}kbps")
            }

            if (details.isEmpty()) extension else "$extension (${details.joinToString("/")})"
        } catch (e: Exception) {
            extension
        } finally {
            retriever.release()
        }
    }

    private fun formatKhz(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}kHz" else String.format(Locale.US, "%.1fkHz", hz / 1000.0)
}
