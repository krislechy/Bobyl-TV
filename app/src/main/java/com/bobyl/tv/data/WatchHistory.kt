package com.bobyl.tv.data

import android.content.Context
import com.bobyl.tv.data.database.AppStorage
import com.bobyl.tv.data.database.BobylDatabase
import com.bobyl.tv.data.database.HistoryRow
import com.bobyl.tv.data.database.FilmMetadataStore

data class WatchRecord(val filmId: String, val url: String, val title: String, val poster: String,
    val voice: String, val season: String, val episode: String, val positionMs: Long, val updated: Long = System.currentTimeMillis(),
    val quality: String = "", val subtitle: String = "", val playerPackage: String = "", val decoder: Int = 0,
    val watched: Boolean = false)

/** Only stores metadata and confirmed player position, never stream URLs or account tokens. */
class WatchHistory private constructor(private val database: BobylDatabase, provider: String) {
    constructor(context: Context, provider: String) : this(AppStorage.database(context), provider)
    companion object {
        internal fun withDatabase(database: BobylDatabase, provider: String) = WatchHistory(database, provider)
    }
    private val key = CatalogUrls.provider(provider)
    private val dao get() = database.storage()
    /** All synchronous entry points retain their IO-only contract; Room enforces it. */
    fun all(): List<WatchRecord> = labelled(dao.history(key))
    fun find(id: String): WatchRecord? = dao.historyRecord(key, id)?.let { labelled(listOf(it)).single() }
    fun forFilm(urlOrKey: String): List<WatchRecord> = labelled(dao.historyFilm(key, historyFilmKey(urlOrKey)))
    private fun labelled(rows: List<HistoryRow>): List<WatchRecord> {
        val metadata = FilmMetadataStore(database)
        val labels = rows.asSequence().filter { it.title.isBlank() || it.poster.isBlank() }.map { it.filmKey }
            .distinct().associateWith { metadata.historyLabels(key, it) }
        return rows.map { row ->
            val record = row.record()
            labels[row.filmKey]?.let { (title, poster) ->
                record.copy(title = record.title.ifBlank { title }, poster = record.poster.ifBlank { poster })
            } ?: record
        }
    }
    fun put(record: WatchRecord) = database.runInTransaction {
        putInTransaction(record)
    }
    private fun putInTransaction(record: WatchRecord) {
        val rank = dao.historyRank(key) + 1
        dao.putHistory(HistoryRow(key, record.filmId, historyFilmKey(record.url), record.url, record.title,
            record.poster, record.voice, record.season, record.episode, record.positionMs, record.updated,
            record.quality, record.subtitle, record.playerPackage, record.decoder, record.watched, rank))
        // The just-written record is retained even when its timestamp is old (manual watched uses 0).
        dao.trimHistory(key, record.filmId)
    }
    fun setWatched(film: CatalogFilm, watched: Boolean): List<WatchRecord> = database.runInTransaction<List<WatchRecord>> {
        setWatchedInTransaction(film, watched)
        all()
    }
    fun setFilmWatched(film: CatalogFilm, watched: Boolean): List<WatchRecord> = database.runInTransaction<List<WatchRecord>> {
        setWatchedInTransaction(film, watched)
        forFilm(film.url)
    }
    private fun setWatchedInTransaction(film: CatalogFilm, watched: Boolean) {
        val filmKey = historyFilmKey(film.url)
        if (dao.historyFilm(key, filmKey).isEmpty()) {
            // A manual status change is not a playback event and must not invent a viewing date.
            if (watched) putInTransaction(WatchRecord(filmKey, film.url, film.title, film.poster, "", "", "", 0L, updated = 0L, watched = true))
        } else {
            dao.setWatched(key, filmKey, watched)
        }
    }
    fun remove(id: String) = dao.removeHistory(key, id)
    fun clear() = dao.clearHistory(key)
    fun clearCount(): Int = database.runInTransaction<Int> {
        val count = dao.historyCount(key)
        dao.clearHistory(key)
        count
    }
}

private fun HistoryRow.record() = WatchRecord(recordId, url, title, poster, voice, season, episode,
    positionMs, updated, quality, subtitle, playerPackage, decoder, watched)
