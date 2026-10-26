package com.vibeplayer.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

/** Bumped after tags change, so the song list is read again from Android's media database. */
object LibraryEvents {
    var version by mutableIntStateOf(0)
}

/** The information stored inside a song file. */
data class TagValues(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: String,
    val track: String,
    val lyrics: String,
)

/**
 * Reads and writes the tags inside song files (title, artist, lyrics and so on).
 * Works for MP3, FLAC, Opus, Ogg, M4A, WAV and more, using the jaudiotagger library.
 * Writing needs Android's "All files access", because it changes the file itself.
 */
object TagEditor {

    /** Returns the tags of a song, or null if the file can't be read. Call from a background thread. */
    fun read(track: Track): TagValues? {
        return try {
            val tag = AudioFileIO.read(File(track.path)).tag
            TagValues(
                title = text(tag, FieldKey.TITLE),
                artist = text(tag, FieldKey.ARTIST),
                album = text(tag, FieldKey.ALBUM),
                albumArtist = text(tag, FieldKey.ALBUM_ARTIST),
                genre = text(tag, FieldKey.GENRE),
                year = text(tag, FieldKey.YEAR),
                track = text(tag, FieldKey.TRACK),
                lyrics = text(tag, FieldKey.LYRICS),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun text(tag: Tag?, key: FieldKey): String =
        try {
            tag?.getFirst(key) ?: ""
        } catch (e: Exception) {
            ""
        }

    /** Writes the tags into the song file. Throws an error with a reason if it fails. */
    fun write(context: Context, track: Track, values: TagValues) {
        val audio = AudioFileIO.read(File(track.path))
        val tag = audio.getTagOrCreateAndSetDefault()
        put(tag, FieldKey.TITLE, values.title)
        put(tag, FieldKey.ARTIST, values.artist)
        put(tag, FieldKey.ALBUM, values.album)
        put(tag, FieldKey.ALBUM_ARTIST, values.albumArtist)
        put(tag, FieldKey.GENRE, values.genre)
        put(tag, FieldKey.YEAR, values.year)
        put(tag, FieldKey.TRACK, values.track)
        put(tag, FieldKey.LYRICS, values.lyrics)
        audio.commit()
        rescan(context, track)
    }

    /** Lyrics stored inside the song file, if any. Call from a background thread. */
    fun readLyrics(track: Track): String? =
        try {
            if (track.path.isBlank()) null
            else AudioFileIO.read(File(track.path)).tag?.getFirst(FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

    /** Stores lyrics inside the song file. Throws an error with a reason if it fails. */
    fun writeLyrics(context: Context, track: Track, lyrics: String, refreshList: Boolean = true) {
        val audio = AudioFileIO.read(File(track.path))
        val tag = audio.getTagOrCreateAndSetDefault()
        put(tag, FieldKey.LYRICS, lyrics)
        audio.commit()
        if (refreshList) rescan(context, track)
    }

    /** An empty box removes the field, anything else replaces it. */
    private fun put(tag: Tag, key: FieldKey, value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) {
            try {
                tag.deleteField(key)
            } catch (e: Exception) {
                // The field wasn't there in the first place.
            }
        } else {
            tag.setField(key, clean)
        }
    }

    /** Tells Android the file changed, then refreshes the song list. */
    private fun rescan(context: Context, track: Track) {
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(track.path), null) { _, _ ->
            Handler(Looper.getMainLooper()).post { LibraryEvents.version++ }
        }
    }
}
