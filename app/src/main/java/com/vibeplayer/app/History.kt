package com.vibeplayer.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** One entry in your listening history: when it happened and which song it was (uri holds the song's file path, or an older Android song address). */
data class PlayEvent(val timeMs: Long, val uri: String)

/**
 * Your listening history. Every time a song has played for 10 seconds, one line is added
 * to a small text file inside the app's private storage. The Home screen reads it to show
 * what you played recently and what you play most.
 */
object PlayHistory {
    private var file: File? = null

    /** Every recorded play, oldest first. The screens redraw automatically when it changes. */
    var events by mutableStateOf<List<PlayEvent>>(emptyList())
        private set

    /** Reads the history file. Safe to call more than once. */
    fun load(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "play_history.txt")
        file = f
        events = try {
            if (f.exists()) f.readLines().mapNotNull { parse(it) } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(line: String): PlayEvent? {
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val time = line.substring(0, tab).toLongOrNull() ?: return null
        return PlayEvent(time, line.substring(tab + 1))
    }

    fun record(uri: String) {
        val event = PlayEvent(System.currentTimeMillis(), uri)
        events = events + event
        try {
            file?.appendText("${event.timeMs}\t$uri\n")
        } catch (e: Exception) {
            // If saving fails, the play is still counted until the app closes.
        }
    }

    /** Replaces one song name with another in every entry (used to recover lost plays). */
    fun remap(oldKey: String, newKey: String) {
        events = events.map { if (it.uri == oldKey) it.copy(uri = newKey) else it }
        rewriteFile()
    }

    /** Switches older entries to their new names. Only rewrites the file if something changed. */
    fun migrate(newKeyByOldKey: Map<String, String>) {
        var changed = false
        val updated = events.map { event ->
            val newKey = newKeyByOldKey[event.uri]
            if (newKey != null && newKey != event.uri) {
                changed = true
                event.copy(uri = newKey)
            } else {
                event
            }
        }
        if (!changed) return
        events = updated
        rewriteFile()
    }

    private fun rewriteFile() {
        try {
            file?.writeText(events.joinToString("") { "${it.timeMs}\t${it.uri}\n" })
        } catch (e: Exception) {
            // The history stays in memory until the next successful save.
        }
    }

    /** Wipes the history (for a future "clear history" setting). */
    fun clear() {
        events = emptyList()
        try {
            file?.delete()
        } catch (e: Exception) {
            // Nothing to do.
        }
    }
}
