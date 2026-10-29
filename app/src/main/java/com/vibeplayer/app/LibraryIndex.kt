package com.vibeplayer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The artist, album and genre lists (and a "find a song by its address" table), worked out ONCE
 * right after your music is scanned, in the background.
 *
 * Before, every screen worked these out again each time you opened it, on the main thread.
 * With thousands of songs that caused the pause when switching tabs, most of all on Home.
 */
object LibraryIndex {
    private class Built(
        val artists: List<TrackGroup>,
        val albums: List<TrackGroup>,
        val genres: List<TrackGroup>,
        val trackByUri: Map<String, Track>,
    )

    private var built: Built? = null
    private var builtSize = -1

    /** The song list the index was built for. Screens use the index only when they hold this same list. */
    @Volatile
    var source: List<Track> = emptyList()
        private set

    /** Builds the index for a freshly scanned song list. Runs off the main thread. */
    suspend fun build(library: List<Track>) {
        val result = withContext(Dispatchers.Default) {
            val byUri = HashMap<String, Track>(library.size * 2)
            for (track in library) {
                byUri[track.uri.toString()] = track
                byUri[track.key] = track
            }
            Built(artistGroups(library), albumGroups(library), genreGroups(library), byUri)
        }
        built = result
        builtSize = library.size
        source = library // set last, so a reader that sees the new list also sees the new index
    }

    /**
     * The player may keep its own copy of the song list. If it has the same songs, point the index at
     * that copy, so screens recognise it.
     */
    fun adopt(library: List<Track>) {
        if (built != null && library.size == builtSize) source = library
    }

    private fun ready(library: List<Track>): Built? {
        val index = built
        return if (index != null && library === source) index else null
    }

    fun artistsFor(library: List<Track>): List<TrackGroup> = ready(library)?.artists ?: artistGroups(library)

    fun albumsFor(library: List<Track>): List<TrackGroup> = ready(library)?.albums ?: albumGroups(library)

    fun genresFor(library: List<Track>): List<TrackGroup> = ready(library)?.genres ?: genreGroups(library)

    fun trackByUriFor(library: List<Track>): Map<String, Track> =
        ready(library)?.trackByUri ?: run {
            val map = HashMap<String, Track>()
            for (track in library) {
                map[track.uri.toString()] = track
                map[track.key] = track
            }
            map
        }
}

// These remember the answer while a screen is on screen, so they are only looked up once per visit.
@Composable
fun rememberArtists(library: List<Track>): List<TrackGroup> = remember(library) { LibraryIndex.artistsFor(library) }

@Composable
fun rememberAlbums(library: List<Track>): List<TrackGroup> = remember(library) { LibraryIndex.albumsFor(library) }

@Composable
fun rememberGenres(library: List<Track>): List<TrackGroup> = remember(library) { LibraryIndex.genresFor(library) }

@Composable
fun rememberTrackByUri(library: List<Track>): Map<String, Track> =
    remember(library) { LibraryIndex.trackByUriFor(library) }
