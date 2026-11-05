package com.bobyl.tv.data

import android.content.Context
import com.bobyl.tv.data.database.AppStorage
import com.bobyl.tv.data.database.BobylDatabase
import com.bobyl.tv.data.database.PlaybackSelectionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class PlaybackSelection(val voice: String = "", val season: String = "", val episode: String = "")

/** Preserves the existing film-only scope; simple playback settings remain preferences. */
internal class PlaybackSelections private constructor(private val database: BobylDatabase) {
    constructor(context: Context) : this(AppStorage.database(context))
    companion object { internal fun withDatabase(database: BobylDatabase) = PlaybackSelections(database) }

    suspend fun read(filmId: String): PlaybackSelection = withContext(Dispatchers.IO) {
        database.storage().selection(filmId)?.let { PlaybackSelection(it.voice, it.season, it.episode) } ?: PlaybackSelection()
    }
    suspend fun save(filmId: String, selection: PlaybackSelection) = withContext(Dispatchers.IO) {
        database.storage().putSelection(PlaybackSelectionRow(filmId, selection.voice, selection.season,
            selection.episode, System.currentTimeMillis()))
    }
}
