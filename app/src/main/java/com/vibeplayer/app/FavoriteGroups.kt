package com.vibeplayer.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Your favorite artists and albums (the star on their pages).
 * They are saved with the other settings, so a backup includes them.
 * (Favorite songs are separate: they live in the "Favorite songs" playlist.)
 */
object FavoriteGroups {
    private const val PREFS = "vibeplayer_extra"
    private const val KEY = "favorite_groups"
    private var loaded = false

    /** The saved favorites. The screens redraw automatically when this changes. */
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Reads the saved favorites. Safe to call more than once. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        keys = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            .orEmpty()
            .toSet()
    }

    /**
     * A name for the artist or album that stays the same between scans.
     * (Genres have no favorites, so they get no name.)
     */
    private fun keyOf(group: TrackGroup): String? = when {
        group.isArtist -> "artist:" + group.key
        group.isAlbum ->
            "album:" + group.title.lowercase(Locale.ROOT) + "|" +
                group.subtitle.substringBefore(" • ").lowercase(Locale.ROOT)
        else -> null
    }

    fun isFavorite(group: TrackGroup): Boolean {
        val key = keyOf(group) ?: return false
        return key in keys
    }

    /** Adds the artist or album to the favorites, or removes it if it is already there. */
    fun toggle(context: Context, group: TrackGroup) {
        val key = keyOf(group) ?: return
        load(context)
        keys = if (key in keys) keys - key else keys + key
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, keys)
            .apply()
    }
}
