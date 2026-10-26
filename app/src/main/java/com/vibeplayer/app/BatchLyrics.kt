package com.vibeplayer.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Downloads lyrics for every song that has none, one after the other, without asking
 * you to choose. Only a result whose length is within 3 seconds of your file is used,
 * so the lyrics should match the song. It keeps going while the app is open.
 */
object BatchLyrics {
    var running by mutableStateOf(false)
        private set
    var total by mutableIntStateOf(0)
        private set
    var done by mutableIntStateOf(0)
        private set
    var found by mutableIntStateOf(0)
        private set
    var status by mutableStateOf("")
        private set
    var summary by mutableStateOf("")
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, tracks: List<Track>, acceptPlain: Boolean, embed: Boolean) {
        if (running) return
        val app = context.applicationContext
        running = true
        total = tracks.size
        done = 0
        found = 0
        summary = ""
        status = ""

        job = scope.launch {
            var alreadyHad = 0
            var notFound = 0
            var failed = 0
            var failuresInARow = 0
            var stoppedEarly = false
            var wroteIntoFiles = false
            try {
                for (track in tracks) {
                    if (!isActive) break
                    status = track.title

                    if (LyricsRepository.hasAnyLyrics(app, track)) {
                        alreadyHad++
                        done++
                        continue
                    }

                    try {
                        val artist = splitArtists(track.artist).first()
                        var results = LrcLib.searchFields(track.title, artist)
                        if (results.isEmpty()) results = LrcLib.searchQuery("${track.title} $artist")
                        failuresInARow = 0

                        val seconds = track.durationMs / 1000.0
                        val best = results
                            .filter { !it.instrumental && abs(it.durationSec - seconds) <= 3.0 }
                            .sortedWith(
                                compareBy<LrcLibResult>(
                                    { if (it.synced != null) 0 else 1 },
                                    { abs(it.durationSec - seconds) },
                                )
                            )
                            .firstOrNull { it.synced != null || (acceptPlain && it.plain != null) }

                        val text = best?.synced ?: best?.plain
                        if (text == null) {
                            notFound++
                        } else if (LyricsRepository.save(app, track, text)) {
                            found++
                            if (embed) {
                                try {
                                    TagEditor.writeLyrics(app, track, text, refreshList = false)
                                    wroteIntoFiles = true
                                } catch (e: Exception) {
                                    failed++
                                }
                            }
                        } else {
                            failed++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed++
                        failuresInARow++
                        if (failuresInARow >= 5) {
                            stoppedEarly = true
                            break
                        }
                    }

                    done++
                    delay(350) // be gentle with the free service
                }
            } finally {
                running = false
                status = ""
                summary = (if (stoppedEarly) "Stopped: the lyrics service couldn't be reached. " else "") +
                    "$found downloaded, $alreadyHad already had lyrics, $notFound not found" +
                    (if (failed > 0) ", $failed failed." else ".")
                if (wroteIntoFiles) LibraryEvents.version++
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
