package com.bobyl.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchRecord(val filmId: String, val url: String, val title: String, val poster: String,
    val voice: String, val season: String, val episode: String, val positionMs: Long, val updated: Long = System.currentTimeMillis(),
    val quality: String = "", val subtitle: String = "", val playerPackage: String = "", val decoder: Int = 0,
    val watched: Boolean = false)

/** Only stores metadata and confirmed player position, never stream URLs or account tokens. */
class WatchHistory(context: Context, provider: String) {
    private data class Snapshot(val source: String, val records: List<WatchRecord>)
    companion object {
        private val snapshots = LinkedHashMap<String, Snapshot>(8, .75f, true)
    }
    private val prefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
    private val key = CatalogUrls.provider(provider)
    fun all(): List<WatchRecord> = runCatching {
        val source = prefs.getString(key, "[]") ?: "[]"
        synchronized(snapshots) {
            snapshots[key]?.takeIf { it.source == source }?.let { return@runCatching it.records }
        }
        val array = JSONArray(source)
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            WatchRecord(it.getString("id"), it.getString("url"), it.getString("title"), it.optString("poster"),
                it.optString("voice"), it.optString("season"), it.optString("episode"), it.optLong("position"), it.optLong("updated"),
                it.optString("quality"), it.optString("subtitle"), it.optString("player"), it.optInt("decoder"), it.optBoolean("watched"))
        } }.sortedByDescending { it.updated }.also { records ->
            synchronized(snapshots) {
                snapshots[key] = Snapshot(source, records)
                while (snapshots.size > 8) snapshots.remove(snapshots.keys.first())
            }
        }
    }.getOrDefault(emptyList())
    fun find(id: String) = all().firstOrNull { it.filmId == id }
    fun put(record: WatchRecord) = write((listOf(record) + all().filterNot { it.filmId == record.filmId }).take(500))
    fun setWatched(film: CatalogFilm, watched: Boolean): List<WatchRecord> {
        val key = historyFilmKey(film.url)
        val records = all().filter { historyFilmKey(it.url) == key }
        if (records.isEmpty() && watched) {
            // A manual status change is not a playback event and must not invent a viewing date.
            put(WatchRecord(key, film.url, film.title, film.poster, "", "", "", 0L, updated = 0L, watched = true))
        } else records.forEach { put(it.copy(watched = watched)) }
        return all()
    }
    fun remove(id: String) = write(all().filterNot { it.filmId == id })
    fun clear() { prefs.edit().remove(key).apply() }
    private fun write(records: List<WatchRecord>) {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("id", r.filmId).put("url", r.url).put("title", r.title).put("poster", r.poster)
            .put("voice", r.voice).put("season", r.season).put("episode", r.episode).put("position", r.positionMs).put("updated", r.updated)
            .put("quality", r.quality).put("subtitle", r.subtitle).put("player", r.playerPackage).put("decoder", r.decoder)
            .put("watched", r.watched)) }
        prefs.edit().putString(key, array.toString()).apply()
    }
}
