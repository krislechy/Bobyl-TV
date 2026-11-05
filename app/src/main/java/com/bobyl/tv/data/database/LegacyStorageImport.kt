package com.bobyl.tv.data.database

import android.content.Context
import com.bobyl.tv.data.CatalogUrls
import com.bobyl.tv.data.historyFilmKey
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

/** This is a preferences import, not an invented Room version-0 migration. Originals stay intact. */
internal class LegacyStorageImport(private val database: BobylDatabase) {
    companion object { const val Marker = "legacy_preferences_v1" }

    suspend fun migrate(context: Context) = migrate(
        context.getSharedPreferences("watch_history", Context.MODE_PRIVATE).all,
        context.getSharedPreferences("personal_ratings", Context.MODE_PRIVATE).all,
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE).all,
    )

    internal suspend fun migrate(history: Map<String, *>, ratings: Map<String, *>, preferences: Map<String, *>) {
        val dao = database.storage()
        if (dao.meta(Marker) != null) return
        val archives = mutableListOf<LegacyImportRow>()
        fun archive(source: String, key: String, raw: Any?, reason: String) {
            archives += LegacyImportRow(source, key, raw?.toString() ?: "null", reason)
        }
        val historyRows = mutableListOf<HistoryRow>()
        val historySources = mutableMapOf<String, MutableList<Pair<String, String>>>()
        history.forEach { (key, raw) ->
            try {
                val provider = CatalogUrls.provider(key)
                val source = raw as? String ?: error("Expected JSON string")
                historySources.getOrPut(provider) { mutableListOf() }.add(key to source)
                val array = JSONArray(source)
                val seen = mutableSetOf<String>()
                for (index in 0 until array.length()) {
                    try {
                        val value = array.getJSONObject(index)
                        val id = value.getString("id")
                        val url = value.getString("url")
                        val row = HistoryRow(provider, id, historyFilmKey(url), url, value.getString("title"),
                            value.optString("poster"), value.optString("voice"), value.optString("season"),
                            value.optString("episode"), value.optLong("position"), value.optLong("updated"),
                            value.optString("quality"), value.optString("subtitle"), value.optString("player"),
                            value.optInt("decoder"), value.optBoolean("watched"), (array.length() - index).toLong())
                        if (seen.add(id)) historyRows += row
                        else archive("watch_history", key, raw, "Duplicate record ID; original JSON retained")
                    } catch (_: Exception) { archive("watch_history", key, raw, "Malformed history record at index $index") }
                }
            } catch (_: Exception) { archive("watch_history", key, raw, "Malformed provider/history JSON") }
        }
        val ratingRows = mutableListOf<PersonalRatingRow>()
        val legacyRatings = mutableListOf<LegacyRatingRow>()
        ratings.forEach { (key, raw) ->
            val parts = key.split('|')
            val value = raw as? Int
            try {
                require(value != null)
                if (parts.size == 4 && parts[0] == "v2") {
                    require(parts[2] == "guest" || parts[2].matches(Regex("user-[0-9a-f]{64}")))
                    require(parts[3].isNotEmpty() && parts[3].all(Char::isDigit) && value in 1..10)
                    ratingRows += PersonalRatingRow(CatalogUrls.provider(parts[1]), parts[2], parts[3], value)
                } else {
                    // Unknown ownership is preserved without inventing an account or a shared fallback.
                    legacyRatings += LegacyRatingRow(key, value)
                }
            } catch (_: Exception) { archive("personal_ratings", key, raw, "Malformed rating entry") }
        }
        val selectionValues = mutableMapOf<String, MutableMap<String, String>>()
        preferences.forEach { (key, raw) ->
            val prefix = key.substringBefore(':')
            if (prefix !in listOf("lastVoice", "lastSeason", "lastEpisode") || ':' !in key) return@forEach
            val id = key.substringAfter(':')
            if (id.isBlank() || raw !is String) archive("playback_selections", key, raw, "Malformed playback selection")
            else selectionValues.getOrPut(id) { mutableMapOf() }[prefix] = raw
        }
        val operation = currentCoroutineContext()
        operation.ensureActive()
        database.runInTransaction {
            operation.ensureActive()
            if (dao.meta(Marker) == null) {
                val knownIds = historyRows.map { it.provider }.distinct().associateWith { provider ->
                    dao.history(provider).map { it.recordId }.toMutableSet()
                }
                historyRows.forEach { row ->
                    operation.ensureActive()
                    if (knownIds.getValue(row.provider).add(row.recordId)) dao.putHistory(row)
                }
                knownIds.keys.forEach { provider ->
                    val excess = dao.history(provider).drop(500)
                    if (excess.isNotEmpty()) {
                        historySources[provider].orEmpty().forEach { (key, source) ->
                            archive("watch_history", key, source, "History exceeds 500 records; full original JSON retained")
                        }
                        excess.forEach { dao.removeHistory(provider, it.recordId) }
                    }
                }
                ratingRows.forEach { row -> if (dao.rating(row.provider, row.owner, row.filmId) == null) dao.putRating(row) }
                legacyRatings.forEach(dao::archiveRating)
                selectionValues.forEach { (id, values) ->
                    if (dao.selection(id) == null) dao.putSelection(PlaybackSelectionRow(id, values["lastVoice"].orEmpty(),
                        values["lastSeason"].orEmpty(), values["lastEpisode"].orEmpty(), 0L))
                }
                archives.forEach(dao::archiveImport)
                operation.ensureActive()
                dao.putMeta(StorageMeta(Marker, "complete"))
            }
        }
    }
}
